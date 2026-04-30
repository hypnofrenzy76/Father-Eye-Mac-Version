package io.fathereye.panel.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persistent rolling metrics history backed by SQLite (WAL mode).
 *
 * <p>Schema (v1):
 * <pre>
 *   metric_raw  (ts_ms, topic, payload BLOB)         -- last 24h, full resolution
 *   metric_1m   (ts_ms, topic, avg_payload BLOB)     -- last 7d, 1-minute averages
 *   metric_1h   (ts_ms, topic, avg_payload BLOB)     -- forever, 1-hour averages
 *   event_log   (ts_ms, kind, payload BLOB)
 * </pre>
 *
 * <p>Writes are queued onto a single-thread executor; the publisher thread
 * never blocks on disk I/O. Downsampling runs every 5 minutes via a
 * scheduled task.
 */
public final class MetricsDb {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-MetricsDb");
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    public static Path defaultPath() {
        return io.fathereye.panel.util.PlatformPaths.appDataDir().resolve("metrics.sqlite");
    }

    private final Path dbPath;
    private final Connection conn;
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>(8192);
    private final ScheduledExecutorService scheduler;
    private volatile boolean stopped = false;
    private final Thread writerThread;
    private final java.util.concurrent.atomic.AtomicLong droppedOnFullQueue = new java.util.concurrent.atomic.AtomicLong(0);

    public MetricsDb(Path dbPath) throws SQLException {
        this.dbPath = dbPath;
        try { Files.createDirectories(dbPath.getParent()); } catch (Exception ignored) {}
        // SQLite-jdbc connection. WAL mode for concurrent reader/writer.
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toString());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS metric_raw (ts_ms INTEGER NOT NULL, topic TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(topic, ts_ms))");
            st.execute("CREATE TABLE IF NOT EXISTS metric_1m  (ts_ms INTEGER NOT NULL, topic TEXT NOT NULL, avg_payload BLOB NOT NULL, PRIMARY KEY(topic, ts_ms))");
            st.execute("CREATE TABLE IF NOT EXISTS metric_1h  (ts_ms INTEGER NOT NULL, topic TEXT NOT NULL, avg_payload BLOB NOT NULL, PRIMARY KEY(topic, ts_ms))");
            st.execute("CREATE TABLE IF NOT EXISTS event_log  (ts_ms INTEGER NOT NULL, kind TEXT NOT NULL, payload BLOB NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS event_log_ts ON event_log(ts_ms)");
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FatherEye-MetricsScheduler");
            t.setDaemon(true);
            return t;
        });

        // Drain the queue on the SAME single-thread executor so writes,
        // downsample, and retention trim are serialised against one another.
        this.writerThread = new Thread(this::drainLoop, "FatherEye-MetricsWriter");
        this.writerThread.setDaemon(true);
        this.writerThread.start();

        // Schedule downsample/trim on the writer thread by submitting to the
        // queue rather than calling them off-thread. We accomplish that by
        // posting a special "control" Pending that runs the operation when
        // drained — but it's cleaner to just keep the dedicated scheduler
        // and submit DB work to a single-thread executor; here we use
        // submit-with-await semantics so the scheduler waits its turn.
        scheduler.scheduleAtFixedRate(() -> runOnDb(this::downsample), 5, 5, TimeUnit.MINUTES);
        scheduler.scheduleAtFixedRate(() -> runOnDb(this::trimRetention), 1, 60, TimeUnit.MINUTES);
    }

    /** Submit a DB-thread-only Runnable; the writer drains the queue. */
    private void runOnDb(Runnable r) {
        queue.offer(Pending.control(r));
    }

    public Path path() { return dbPath; }

    /**
     * Pnl-36: snapshot reader for session-end evaluations. Returns every
     * stored snapshot for {@code topic} in the [{@code fromMs},
     * {@code toMs}] range, decoded back to JsonNode. Runs synchronously
     * on the calling thread but must funnel through the writer thread to
     * avoid the sqlite-jdbc single-connection thread-confinement issue;
     * for evaluation use we accept the brief stall.
     */
    public java.util.List<com.fasterxml.jackson.databind.JsonNode> querySnapshots(
            String topic, long fromMs, long toMs) {
        java.util.concurrent.CompletableFuture<java.util.List<com.fasterxml.jackson.databind.JsonNode>> f =
                new java.util.concurrent.CompletableFuture<>();
        runOnDb(() -> {
            java.util.List<com.fasterxml.jackson.databind.JsonNode> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT payload FROM metric_raw WHERE topic = ? AND ts_ms BETWEEN ? AND ? ORDER BY ts_ms")) {
                ps.setString(1, topic);
                ps.setLong(2, fromMs);
                ps.setLong(3, toMs);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] bytes = rs.getBytes(1);
                        if (bytes != null && bytes.length > 0) {
                            try { out.add(CBOR.readTree(bytes)); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("query {} failed: {}", topic, e.getMessage());
            }
            f.complete(out);
        });
        try { return f.get(15, TimeUnit.SECONDS); }
        catch (Exception e) {
            LOG.warn("query {} timed out", topic);
            return java.util.Collections.emptyList();
        }
    }

    public java.util.List<com.fasterxml.jackson.databind.JsonNode> queryEvents(
            String kind, long fromMs, long toMs) {
        java.util.concurrent.CompletableFuture<java.util.List<com.fasterxml.jackson.databind.JsonNode>> f =
                new java.util.concurrent.CompletableFuture<>();
        runOnDb(() -> {
            java.util.List<com.fasterxml.jackson.databind.JsonNode> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT payload FROM event_log WHERE kind = ? AND ts_ms BETWEEN ? AND ? ORDER BY ts_ms")) {
                ps.setString(1, kind);
                ps.setLong(2, fromMs);
                ps.setLong(3, toMs);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] bytes = rs.getBytes(1);
                        if (bytes != null && bytes.length > 0) {
                            try { out.add(CBOR.readTree(bytes)); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("query event {} failed: {}", kind, e.getMessage());
            }
            f.complete(out);
        });
        try { return f.get(15, TimeUnit.SECONDS); }
        catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    public void writeMetric(String topic, JsonNode payload) {
        try {
            byte[] bytes = CBOR.writeValueAsBytes(payload);
            if (!queue.offer(new Pending(System.currentTimeMillis(), topic, bytes, false))) {
                if (droppedOnFullQueue.incrementAndGet() % 100 == 1) {
                    LOG.warn("MetricsDb queue full, dropping {} entries (count: {})", topic, droppedOnFullQueue.get());
                }
            }
        } catch (Exception e) {
            // best-effort
        }
    }

    public void writeEvent(String kind, JsonNode payload) {
        try {
            byte[] bytes = CBOR.writeValueAsBytes(payload);
            if (!queue.offer(new Pending(System.currentTimeMillis(), kind, bytes, true))) {
                droppedOnFullQueue.incrementAndGet();
            }
        } catch (Exception e) {}
    }

    private void drainLoop() {
        List<Pending> batch = new ArrayList<>(256);
        while (!stopped) {
            try {
                Pending head = queue.poll(2, TimeUnit.SECONDS);
                if (head == null) continue;
                batch.add(head);
                queue.drainTo(batch, 255);
                processBatch(batch);
                batch.clear();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Final flush — drain whatever is still queued so we don't lose the
        // last second of metrics on shutdown.
        List<Pending> tail = new ArrayList<>(queue.size());
        queue.drainTo(tail);
        if (!tail.isEmpty()) processBatch(tail);
    }

    private void processBatch(List<Pending> batch) {
        // Split control runnables from data writes; run controls inline,
        // batch the rest into a single transaction.
        List<Pending> data = new ArrayList<>(batch.size());
        for (Pending p : batch) {
            if (p.control != null) {
                try { p.control.run(); } catch (Throwable t) { LOG.warn("metrics control task failed", t); }
            } else {
                data.add(p);
            }
        }
        if (!data.isEmpty()) flush(data);
    }

    private void flush(List<Pending> batch) {
        try (PreparedStatement ins = conn.prepareStatement("INSERT OR REPLACE INTO metric_raw(ts_ms,topic,payload) VALUES(?,?,?)");
             PreparedStatement insE = conn.prepareStatement("INSERT INTO event_log(ts_ms,kind,payload) VALUES(?,?,?)")) {
            conn.setAutoCommit(false);
            for (Pending p : batch) {
                if (p.isEvent) {
                    insE.setLong(1, p.tsMs); insE.setString(2, p.topic); insE.setBytes(3, p.payload); insE.addBatch();
                } else {
                    ins.setLong(1, p.tsMs); ins.setString(2, p.topic); ins.setBytes(3, p.payload); ins.addBatch();
                }
            }
            ins.executeBatch();
            insE.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            LOG.warn("metrics flush failed: {}", e.getMessage());
            try { conn.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Roll metric_raw entries older than 24h into 1-minute averages. */
    private void downsample() {
        long cutoff = System.currentTimeMillis() - 24L * 3600_000L;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT topic, (ts_ms / 60000) * 60000 AS bucket, payload FROM metric_raw WHERE ts_ms < ? ORDER BY topic, bucket");
             PreparedStatement ins = conn.prepareStatement(
                "INSERT OR REPLACE INTO metric_1m(ts_ms, topic, avg_payload) VALUES(?,?,?)");
             PreparedStatement del = conn.prepareStatement("DELETE FROM metric_raw WHERE ts_ms < ?")) {
            sel.setLong(1, cutoff);
            // For simplicity we keep the LATEST payload in each bucket as the
            // representative value. True averaging across CBOR-encoded payloads
            // is type-specific; latest-wins is good enough for monitoring.
            try (java.sql.ResultSet rs = sel.executeQuery()) {
                String prevTopic = null; long prevBucket = -1; byte[] last = null;
                while (rs.next()) {
                    String topic = rs.getString(1);
                    long bucket = rs.getLong(2);
                    byte[] payload = rs.getBytes(3);
                    if (prevTopic != null && (!prevTopic.equals(topic) || prevBucket != bucket)) {
                        ins.setLong(1, prevBucket); ins.setString(2, prevTopic); ins.setBytes(3, last); ins.executeUpdate();
                    }
                    prevTopic = topic; prevBucket = bucket; last = payload;
                }
                if (prevTopic != null) {
                    ins.setLong(1, prevBucket); ins.setString(2, prevTopic); ins.setBytes(3, last); ins.executeUpdate();
                }
            }
            del.setLong(1, cutoff);
            int removed = del.executeUpdate();
            if (removed > 0) LOG.info("downsampled {} raw metric rows -> 1m bucket", removed);
        } catch (SQLException e) {
            LOG.warn("downsample failed: {}", e.getMessage());
        }
    }

    /** Drop 1m entries older than 7d. (1h retention: forever for now.)
     *  Pnl-59 (2026-04-27): also trim the event_log table by
     *  eventLogRetentionDays (default 30 d). Without this, the
     *  table grew unbounded across long-running panel sessions:
     *  every chat line, join, leave, alert, and crash accumulated
     *  forever, eventually pushing metrics.sqlite into the tens of
     *  MiB and slowing down dashboard queries that scan event_log.
     *  Setting eventLogRetentionDays to 0 disables event trimming
     *  for users who want a permanent audit log. */
    private void trimRetention() {
        long now = System.currentTimeMillis();
        long cutoff7d = now - 7L * 24 * 3600_000L;
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM metric_1m WHERE ts_ms < ?")) {
            del.setLong(1, cutoff7d);
            del.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("trim retention failed: {}", e.getMessage());
        }
        long evDays = eventLogRetentionDays;
        if (evDays > 0) {
            long cutoffEv = now - evDays * 86_400_000L;
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM event_log WHERE ts_ms < ?")) {
                del.setLong(1, cutoffEv);
                int removed = del.executeUpdate();
                if (removed > 0) LOG.info("trimmed {} event_log rows older than {} d", removed, evDays);
            } catch (SQLException e) {
                LOG.warn("event_log trim failed: {}", e.getMessage());
            }
        }
    }

    /** Pnl-59: caller-configurable retention window (days) for the
     *  event_log table. Set to 0 to disable trimming. */
    private volatile long eventLogRetentionDays = 30L;
    public void setEventLogRetentionDays(long d) { this.eventLogRetentionDays = Math.max(0L, d); }

    public void close() {
        stopped = true;
        // Stop the scheduler first so it doesn't enqueue new control tasks.
        scheduler.shutdownNow();
        // Writer thread observes 'stopped' on next poll, drains tail, exits.
        try { writerThread.join(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException ignored) {}
        try { conn.close(); } catch (SQLException ignored) {}
    }

    private static final class Pending {
        final long tsMs;
        final String topic;
        final byte[] payload;
        final boolean isEvent;
        final Runnable control; // non-null = run on the writer thread (downsample/trim/etc.)
        Pending(long tsMs, String topic, byte[] payload, boolean isEvent) {
            this.tsMs = tsMs; this.topic = topic; this.payload = payload; this.isEvent = isEvent; this.control = null;
        }
        private Pending(Runnable control) {
            this.tsMs = 0; this.topic = null; this.payload = null; this.isEvent = false; this.control = control;
        }
        static Pending control(Runnable r) { return new Pending(r); }
    }
}
