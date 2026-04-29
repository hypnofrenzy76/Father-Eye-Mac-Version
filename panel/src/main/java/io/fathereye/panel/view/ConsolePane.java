package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Read-only server log view with a command bar at the bottom.
 *
 * <p>Pnl-38 (2026-04-26): the previous implementation appended every
 * inbound log line directly to the {@link TextArea} via
 * {@code appendText} on a fresh {@code Platform.runLater} per line.
 * On a heavily-modded server emitting hundreds of lines per second the
 * FX thread spent more time copying the TextArea's backing String than
 * processing events. Replaced with a lock-free MPSC queue from the
 * reader thread plus a 10 Hz AnimationTimer that batches drained lines,
 * applies a hard maximum-line-count window, and rewrites the TextArea
 * via a single {@code setText} call.
 *
 * <p>Pnl-40 (2026-04-26): the user reported "log keeps skipping to top
 * and not always following latest entries when i scroll". Two
 * underlying bugs:
 * <ul>
 *   <li>{@link TextArea#setText(String)} resets {@code scrollTop} to 0
 *       on every call. Every 100 ms tick we threw the user back to
 *       the top.</li>
 *   <li>{@link TextArea#positionCaret(int)} moves the caret but does
 *       not reliably scroll the viewport to keep the caret visible,
 *       so even with the sticky-bottom guard the view drifted away
 *       from the latest line.</li>
 *   <li>The sticky-bottom check ({@code caretPosition == length})
 *       only updates when the user clicks or presses keys; the wheel
 *       and scrollbar leave caretPosition unchanged. So scrolling up
 *       didn't disengage auto-follow.</li>
 * </ul>
 *
 * <p>Fixes:
 * <ol>
 *   <li>Use {@link TextArea#setScrollTop(double)} with
 *       {@link Double#MAX_VALUE} (clamped by the skin to the max
 *       scroll position) instead of {@code positionCaret} to actually
 *       follow the bottom.</li>
 *   <li>Add an explicit "Auto-scroll" {@link CheckBox} (default ON).
 *       When ON, every tick rewrites the TextArea and snaps to
 *       bottom. When OFF, the TextArea is frozen, lines still drain
 *       into the bounded ring, and toggling ON triggers an immediate
 *       render + snap to bottom.</li>
 *   <li>"tail -f" style auto-pause: wheel and scrollbar drags that
 *       move the viewport below the bottom automatically uncheck
 *       Auto-scroll, so the user can wheel up to read history without
 *       having to find a control first. Scrolling back to the bottom
 *       re-checks Auto-scroll, which fires the same render-and-snap
 *       so the next tick continues following.</li>
 * </ol>
 * Combined: most of the time the user does nothing and the panel
 * tails the latest line. The moment they wheel up, the panel pauses;
 * the moment they reach bottom again, it resumes.
 */
public final class ConsolePane {

    /** Hard cap on retained lines. ~600 visible at typical font size. */
    private static final int MAX_LINES = 600;
    /** Per-flush ceiling on lines drained from the reader queue, so a
     *  burst of thousands of lines per second can't starve other FX
     *  events. Excess lines stay in the queue until the next tick. */
    private static final int MAX_DRAIN_PER_TICK = 200;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss");

    private final TextArea logArea = new TextArea();
    private final TextField commandInput = new TextField();
    private final Button sendBtn = new Button("Run");
    /** Pnl-40: explicit auto-scroll toggle. Default ON so the panel
     *  tails the latest entries out of the box; the user un-checks to
     *  pause and read history. */
    private final CheckBox autoScrollChk = new CheckBox("Auto-scroll");
    /** Pnl-40: dropped-line counter shown in the toolbar so the user
     *  knows when MAX_LINES is being hit and the start of the
     *  in-memory ring is rolling. */
    private final Label droppedLabel = new Label("Dropped: 0");
    private final VBox root = new VBox(4);

    /** Lines buffered on the reader thread, drained on the FX thread. */
    private final ConcurrentLinkedQueue<String> incoming = new ConcurrentLinkedQueue<>();
    /** Bounded ring of retained lines; FX-thread-only. */
    private final ArrayDeque<String> retained = new ArrayDeque<>(MAX_LINES + MAX_DRAIN_PER_TICK);
    private final AtomicLong dropped = new AtomicLong(0);
    /** Pnl-40: dirty flag is true when the ring has changed since the
     *  last successful render. While auto-scroll is OFF the ring keeps
     *  ticking but the TextArea stays stale; this flag triggers a
     *  catch-up render the moment the user re-enables auto-scroll. */
    private boolean dirty = false;

    private Consumer<String> commandSubmitHandler = cmd -> {};

    public ConsolePane() {
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");

        commandInput.setPromptText("Type a console command and press Enter…");
        commandInput.setStyle("-fx-font-family: 'Consolas', monospace;");
        commandInput.setOnAction(e -> submit());
        sendBtn.setOnAction(e -> submit());

        // Pnl-40: top toolbar with auto-scroll toggle + dropped-line
        // counter. Sits above the log so it stays visible regardless
        // of scroll position.
        autoScrollChk.setSelected(true);
        autoScrollChk.selectedProperty().addListener((obs, ov, nv) -> {
            if (nv) {
                // OFF -> ON: snap to latest immediately. The user
                // expects "I'm caught up now" when they re-enable.
                renderAndSnap();
            }
            // ON -> OFF: leave the TextArea in its current state.
            // The ring keeps draining in the background.
        });
        // Pnl-46 (2026-04-26): the Pnl-40 tail-f auto-pause listener
        // is removed. It was supposed to uncheck Auto-scroll when the
        // user wheeled up, but on the FIRST render (before the
        // TextArea has laid out, e.g. when the Console tab is not the
        // active tab) setScrollTop(Double.MAX_VALUE) was stored
        // verbatim instead of being clamped, so lastKnownMaxScroll
        // captured MAX_VALUE. On the next layout pass the actual
        // scrollTop clamped to a real value (say 100), the listener
        // computed (100 >= MAX_VALUE - 8) == false, and silently
        // auto-unchecked Auto-scroll. The user reported "console is
        // appearing as empty unless i click auto scroll, it just
        // needs to auto scroll automatically". Manual control via
        // the checkbox is still available; mouse wheel no longer
        // auto-pauses (uncheck the box first to read history).
        droppedLabel.setStyle("-fx-text-fill: #888;");
        HBox toolbar = new HBox(8, autoScrollChk, droppedLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 8, 0, 8));

        HBox cmdBar = new HBox(6, new Label(">"), commandInput, sendBtn);
        cmdBar.setPadding(new Insets(4, 8, 4, 8));
        HBox.setHgrow(commandInput, Priority.ALWAYS);

        root.setPadding(new Insets(4));
        root.getChildren().addAll(toolbar, logArea, cmdBar);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        // Pnl-38: 10 Hz batched flush. AnimationTimer.handle runs on the
        // FX thread; we use an explicit min-period instead of every-frame
        // because the TextArea repaint cost dwarfs the logic cost.
        AnimationTimer pump = new AnimationTimer() {
            long lastTick = 0;
            @Override public void handle(long nowNanos) {
                if (nowNanos - lastTick < 100_000_000L) return; // 10 Hz
                lastTick = nowNanos;
                drainAndRepaint();
            }
        };
        pump.start();
    }

    public VBox root() { return root; }

    public void setOnCommandSubmit(Consumer<String> handler) {
        this.commandSubmitHandler = handler;
    }

    private void submit() {
        String cmd = commandInput.getText();
        if (cmd == null || cmd.trim().isEmpty()) return;
        commandSubmitHandler.accept(cmd.trim());
        commandInput.clear();
    }

    /**
     * Called from the IPC reader thread (or wherever a log line is
     * surfaced). Cheap: just enqueues the formatted line. Repaint
     * happens on the FX thread via the AnimationTimer.
     */
    public void onLogLine(JsonNode payload) {
        if (payload == null) return;
        long tsMs = payload.path("tsMs").asLong(System.currentTimeMillis());
        String level = payload.path("level").asText("INFO");
        String logger = payload.path("logger").asText("?");
        String msg = payload.path("msg").asText("");
        String thread = payload.path("thread").asText("");

        String line = String.format("%s [%-5s] [%s] %s%s%n",
                TS.format(new Date(tsMs)), level,
                thread.isEmpty() ? logger : thread,
                logger.isEmpty() ? "" : "(" + logger + ") ",
                msg);
        incoming.add(line);
    }

    private void drainAndRepaint() {
        if (!Platform.isFxApplicationThread()) return;
        // Pnl-40: ALWAYS drain into the ring, even when auto-scroll is
        // off. Otherwise pausing the view also pauses the bridge
        // log-reader, which corks the IPC pipe and stalls every other
        // topic. The ring is the single source of truth; the TextArea
        // is just a (sometimes paused) projection of it.
        int taken = 0;
        boolean any = false;
        String line;
        while (taken < MAX_DRAIN_PER_TICK && (line = incoming.poll()) != null) {
            retained.add(line);
            taken++; any = true;
            if (retained.size() > MAX_LINES) {
                retained.pollFirst();
                dropped.incrementAndGet();
            }
        }
        if (any) dirty = true;
        if (any) {
            // Update the dropped counter at most once per tick, only
            // when something actually moved.
            droppedLabel.setText("Dropped: " + dropped.get());
        }
        // Pnl-40: skip the TextArea rewrite while paused. The ring
        // keeps growing; renderAndSnap() catches up the moment the
        // user re-enables auto-scroll.
        if (!autoScrollChk.isSelected()) return;
        if (!dirty) return;
        renderAndSnap();
    }

    /**
     * Pnl-40: rewrite the TextArea from the retained ring and force
     * the viewport to the bottom. Must run on the FX thread. Also
     * called from the auto-scroll listener when transitioning OFF to
     * ON, to catch up to whatever the ring contains right now.
     */
    private void renderAndSnap() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::renderAndSnap);
            return;
        }
        // Build the buffer in one allocation. ~600 lines x ~120 chars
        // each ~= 70 KB, small enough that setText is cheap.
        StringBuilder sb = new StringBuilder(MAX_LINES * 96);
        for (String s : retained) sb.append(s);
        logArea.setText(sb.toString());
        // Pnl-40: setScrollTop with a saturating value reliably
        // scrolls to the bottom. The skin clamps to (contentHeight -
        // viewportHeight). positionCaret(end) was the previous
        // attempt and was unreliable: it moves the caret but the
        // viewport often stayed at scrollTop=0 from the setText reset.
        // Pnl-46: scheduled twice -- once now, once on the next pulse
        // -- because if the TextArea has not been laid out yet (e.g.
        // the Console tab is not the active tab on the first render),
        // the immediate setScrollTop is stored verbatim and not
        // clamped. Re-applying after the layout pass guarantees we
        // land at the actual bottom once the viewport has measured
        // itself.
        logArea.setScrollTop(Double.MAX_VALUE);
        Platform.runLater(() -> logArea.setScrollTop(Double.MAX_VALUE));
        dirty = false;
    }
}
