package io.fathereye.panel.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manages the dedicated Forge server as a child process.
 *
 * <ul>
 *   <li>{@link #start()} — fork java with the configured JVM args; pump stdout</li>
 *   <li>{@link #stop()} — write "stop\n" to stdin, wait up to 30 s for graceful
 *       shutdown, escalate to {@code Process.destroyForcibly()}</li>
 *   <li>{@link #restart()} — stop then start</li>
 *   <li>{@link #sendCommand(String)} — write a command to the server console</li>
 * </ul>
 *
 * <p>The watchdog (TPS heartbeat tracking) lives in {@link Watchdog}.
 */
public final class ServerLauncher {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-ServerLauncher");
    private static final long STOP_GRACE_MS = 30_000L;
    /**
     * After {@link Process#destroyForcibly()} the OS may take a moment to
     * actually flush file handles. Poll the world lock for up to this long
     * before declaring it permanently held.
     */
    private static final long LOCK_RETRY_MS = 15_000L;
    private static final long LOCK_RETRY_INTERVAL_MS = 500L;

    public enum State { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

    /** Pnl-42 (2026-04-26): swappable launch spec. Was {@code final},
     *  but the pre-boot configuration dialog needs to apply RAM / JVM
     *  arg edits before the next launch without rebuilding the whole
     *  launcher (which would lose stdoutSink / stateSink wiring).
     *  {@link #updateSpec(ServerLaunchSpec)} mutates the field and
     *  refuses while the server is running, so a half-baked relaunch
     *  is impossible. */
    private volatile ServerLaunchSpec spec;
    private final Consumer<String> stdoutSink;
    private final Consumer<State> stateSink;

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private volatile Process process;
    private volatile BufferedWriter stdinWriter;
    private volatile Thread pumpThread;
    /** Pnl-51 (2026-04-26): Win32 Job Object owning every spawned
     *  server JVM. The Job Object's KILL_ON_JOB_CLOSE flag means the
     *  OS terminates assigned processes the moment this handle is
     *  released, which happens automatically when the panel JVM
     *  dies. Replaces the unreliable "shutdown hook only" guarantee
     *  -- TaskKill /F or a panel crash bypassed the hook and left
     *  orphan server JVMs. Created lazily on first start; null on
     *  non-Windows or when CreateJobObject fails. */
    private volatile WindowsJobObject jobObject;

    public ServerLauncher(ServerLaunchSpec spec,
                          Consumer<String> stdoutSink,
                          Consumer<State> stateSink) {
        this.spec = spec;
        this.stdoutSink = stdoutSink;
        this.stateSink = stateSink;
    }

    public State state() { return state.get(); }
    public ServerLaunchSpec spec() { return spec; }

    /**
     * Pnl-42: install a new launch spec. Only valid while the server
     * is in STOPPED or CRASHED state -- mid-run changes would not
     * affect the live JVM anyway and would silently mislead the
     * operator on the next restart. Throws
     * {@link IllegalStateException} otherwise.
     */
    public synchronized void updateSpec(ServerLaunchSpec next) {
        if (next == null) throw new IllegalArgumentException("spec must not be null");
        State s = state.get();
        if (s != State.STOPPED && s != State.CRASHED) {
            throw new IllegalStateException(
                    "Cannot update launch spec while server is " + s + "; stop first.");
        }
        this.spec = next;
    }

    public synchronized void start() throws IOException {
        if (state.get() != State.STOPPED && state.get() != State.CRASHED) {
            throw new IllegalStateException("server is " + state.get() + ", cannot start");
        }

        // Pre-flight: world/session.lock must not be held by another process.
        // Forge writes the lock at MinecraftServer.Main.main; if a previous
        // server crashed without releasing it, the new server fails on a
        // FileChannel write with "another process has locked a portion of
        // the file" and exits 7 seconds in. Surface a clear error here
        // rather than wait for the cryptic Forge stack trace.
        //
        // Poll for up to LOCK_RETRY_MS: when the watchdog restarts via
        // destroyForcibly, the OS may take 500–3000 ms to release file
        // handles, and a tight tryLock here would race the kill and abort
        // the restart loop the user actually wanted.
        java.nio.file.Path lock = spec.workingDir.resolve("world").resolve("session.lock");
        if (java.nio.file.Files.exists(lock)) {
            long deadline = System.currentTimeMillis() + LOCK_RETRY_MS;
            boolean acquired = false;
            int attempt = 0;
            while (System.currentTimeMillis() < deadline) {
                attempt++;
                if (canAcquireLock(lock)) { acquired = true; break; }
                try {
                    Thread.sleep(LOCK_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!acquired) {
                throw new IOException(
                        "world/session.lock is held by another process after " +
                        (LOCK_RETRY_MS / 1000) + " s of polling (" + attempt +
                        " attempts). A previous Minecraft server may still be running, " +
                        "or it crashed without releasing the lock. Close any orphaned " +
                        "java.exe processes or delete " + lock + " manually, then retry.");
            }
            if (attempt > 1) {
                LOG.info("session.lock cleared after {} attempts (~{} ms wait).",
                        attempt, attempt * LOCK_RETRY_INTERVAL_MS);
            }
        }

        setState(State.STARTING);

        ProcessBuilder pb = new ProcessBuilder(spec.buildCommand())
                .directory(spec.workingDir.toFile())
                .redirectErrorStream(true);
        process = pb.start();
        stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // Pnl-51 (2026-04-26): assign the freshly-spawned JVM to a
        // Job Object configured with KILL_ON_JOB_CLOSE so the OS
        // itself reaps the server if the panel dies. Lazily created
        // on first start; reused across restarts so the same job
        // owns every JVM this launcher has ever spawned.
        // Pnl-51 (audit fix): a creation OR assign failure means
        // orphan protection is OFF for this JVM. Push the warning
        // via stdoutSink so it surfaces in the in-app console (not
        // just panel.log) -- the user explicitly asked for the
        // no-orphans guarantee, so they need to see when it isn't
        // active.
        boolean orphanProtectionOK = false;
        if (jobObject == null) {
            jobObject = WindowsJobObject.create();
        }
        if (jobObject != null) {
            try {
                orphanProtectionOK = jobObject.assign(process.pid());
            } catch (Throwable t) {
                LOG.warn("Job Object assign failed for pid {}", process.pid(), t);
            }
        }
        if (!orphanProtectionOK && WindowsJobObject.isSupported()) {
            String warn = "WARNING: Win32 Job Object orphan-protection FAILED for pid " + process.pid()
                    + ". If the panel is force-killed, this server JVM may survive as an orphan. "
                    + "Stop the server via the panel's Stop button before closing the panel.";
            LOG.warn(warn);
            try { stdoutSink.accept(warn); } catch (Throwable ignored) {}
        }

        pumpThread = new Thread(this::pumpStdout, "FatherEye-ServerStdout");
        pumpThread.setDaemon(true);
        pumpThread.start();

        Thread watcher = new Thread(() -> {
            try {
                int code = process.waitFor();
                LOG.info("Server exited with code {}", code);
                setState(code == 0 ? State.STOPPED : State.CRASHED);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "FatherEye-ServerExitWatcher");
        watcher.setDaemon(true);
        watcher.start();

        // Pnl-44 (2026-04-26): do NOT transition to RUNNING here. The
        // previous code flipped state to RUNNING the moment the JVM
        // process spawned, but on a 100+ mod modpack the server takes
        // 5-10 minutes after spawn to actually finish FMLServerStartedEvent
        // and start serving connections. The user reported "it says
        // server running but nothing is happening" during this window.
        // Leave state at STARTING; the App promotes to RUNNING via
        // {@link #markRunning()} only after the bridge handshake
        // completes, which is the real "ready to serve" signal.
        LOG.info("Server JVM spawned (pid={}); state stays STARTING until bridge handshake completes.", process.pid());
    }

    /**
     * Pnl-44 (2026-04-26): promote STARTING -> RUNNING. Called from the
     * panel's connect path after the bridge handshake succeeds, which
     * is the real "ready to serve" signal on a heavily-modded server
     * (FMLServerStartedEvent has fired and the bridge has registered
     * its IPC listener). Idempotent: a second call from RUNNING is a
     * no-op; calls from any state other than STARTING / RUNNING are
     * ignored (e.g. if the panel reconnects to a server that someone
     * else started, state would already be STOPPED on this launcher
     * because we never spawned the JVM, and we should stay STOPPED).
     */
    public synchronized void markRunning() {
        State s = state.get();
        if (s == State.STARTING) {
            setState(State.RUNNING);
        }
    }

    public synchronized void stop() {
        stopInternal(STOP_GRACE_MS);
    }

    /**
     * Pnl-33: fast-shutdown variant. Bounded grace period so the panel's
     * JVM-shutdown-hook doesn't exceed Windows' ~10 s shutdown allowance
     * (which would TerminateProcess the panel mid-stop and orphan the
     * server). Sends /stop, waits the bounded window, force-kills the
     * server JVM AND its descendants if it's still alive.
     */
    public synchronized void stopFast() {
        stopInternal(7_000L);
    }

    private void stopInternal(long graceMs) {
        Process p = process;
        if (p == null || !p.isAlive()) {
            setState(State.STOPPED);
            return;
        }
        setState(State.STOPPING);
        try {
            sendCommand("stop");
        } catch (IOException ioe) {
            LOG.warn("Failed to send /stop: {}", ioe.getMessage());
        }
        try {
            if (!p.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                LOG.warn("Server did not exit within {} ms, forcing.", graceMs);
                forceKillTree(p);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            forceKillTree(p);
        }
        process = null;
        stdinWriter = null;
        setState(State.STOPPED);
    }

    public void restart() throws IOException {
        stop();
        start();
    }

    /**
     * Pnl-33: kill the server process AND any descendants. Plain
     * destroyForcibly() on Windows tears down only the named process;
     * any helper processes the JVM might have spawned (rare for vanilla
     * MC, but Forge mod loaders sometimes fork) survive as orphans. The
     * descendants() walk is from JDK 9+, available throughout the
     * panel's JDK 17 runtime.
     */
    private static void forceKillTree(Process p) {
        try {
            p.descendants().forEach(ph -> {
                try { ph.destroyForcibly(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
        try { p.destroyForcibly(); } catch (Throwable ignored) {}
    }

    public synchronized void sendCommand(String command) throws IOException {
        BufferedWriter w = stdinWriter;
        if (w == null) throw new IOException("server stdin not available");
        w.write(command);
        w.write('\n');
        w.flush();
    }

    /**
     * Try to grab an exclusive write lock on the file. If we can, no other
     * process is holding a Forge session lock — return true (and release
     * the test lock immediately). If we can't, it's held — return false.
     * Treats unreadable / permission errors as "can't acquire" → surface
     * the clearer "lock held" error rather than a generic IO failure.
     */
    private boolean canAcquireLock(java.nio.file.Path lock) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lock.toFile(), "rw");
             java.nio.channels.FileChannel ch = raf.getChannel()) {
            java.nio.channels.FileLock fl = ch.tryLock();
            if (fl == null) return false;
            try { fl.release(); } catch (IOException ignored) {}
            return true;
        } catch (IOException | RuntimeException t) {
            // Narrow per auditor A#6: don't swallow OutOfMemoryError or
            // other Errors. IOException covers AccessDenied / file-open
            // failures; RuntimeException covers OverlappingFileLockException
            // (JVM refusing because we already hold a competing lock) and
            // any IllegalArgumentException from tryLock with weird inputs.
            return false;
        }
    }

    private void pumpStdout() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                stdoutSink.accept(line);
            }
        } catch (IOException ioe) {
            LOG.info("stdout pump exiting: {}", ioe.getMessage());
        }
    }

    private void setState(State s) {
        State old = state.getAndSet(s);
        if (old != s) {
            try { stateSink.accept(s); } catch (Throwable ignored) {}
        }
    }
}
