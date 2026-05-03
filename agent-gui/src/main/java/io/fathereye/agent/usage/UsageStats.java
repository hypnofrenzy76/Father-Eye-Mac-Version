package io.fathereye.agent.usage;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cumulative usage counters for the current app process. The Claude.ai
 * subscription doesn't expose a "X messages remaining" endpoint we can
 * hit, so we instead surface the only signal Claude Code emits:
 * per-turn token usage and (where available) USD cost. Adding these up
 * gives the user a per-session running total — useful as a "I should
 * probably stop now" signal even if it's not the same as a quota gauge.
 *
 * <p>{@link #ingestResult} parses a Claude Code {@code result} event
 * (see stream-json output format) and increments the counters.
 *
 * <p>Counters are atomic so the AgentService background thread can
 * write while the FX thread reads.
 */
public final class UsageStats {

    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cacheCreationTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();
    /** Cost in micro-dollars (USD * 1_000_000) so the running total stays integer. */
    private final AtomicLong costMicroUsd = new AtomicLong();
    private final AtomicLong turns = new AtomicLong();

    /** Reset on Sign Out / app launch. We don't persist usage across launches. */
    public void reset() {
        inputTokens.set(0);
        outputTokens.set(0);
        cacheCreationTokens.set(0);
        cacheReadTokens.set(0);
        costMicroUsd.set(0);
        turns.set(0);
    }

    /** Update from a Claude Code stream-json {@code result} event. */
    public void ingestResult(JsonNode event) {
        if (event == null) return;
        JsonNode usage = event.path("usage");
        if (usage.isObject()) {
            inputTokens.addAndGet(usage.path("input_tokens").asLong(0));
            outputTokens.addAndGet(usage.path("output_tokens").asLong(0));
            cacheCreationTokens.addAndGet(usage.path("cache_creation_input_tokens").asLong(0));
            cacheReadTokens.addAndGet(usage.path("cache_read_input_tokens").asLong(0));
        }
        double cost = event.path("total_cost_usd").asDouble(0);
        if (cost > 0) costMicroUsd.addAndGet(Math.round(cost * 1_000_000));
        turns.incrementAndGet();
    }

    public long totalTokens() {
        return inputTokens.get() + outputTokens.get() + cacheCreationTokens.get() + cacheReadTokens.get();
    }

    public long inputTokens() { return inputTokens.get(); }
    public long outputTokens() { return outputTokens.get(); }
    public long cacheCreation() { return cacheCreationTokens.get(); }
    public long cacheRead() { return cacheReadTokens.get(); }
    public long turns() { return turns.get(); }
    public double costUsd() { return costMicroUsd.get() / 1_000_000.0; }

    /** Compact display: "12.4K tokens · $0.05 · 5 turns". */
    public String shortDisplay() {
        return String.format("%s tokens · $%.2f · %d turn%s",
                fmtTokens(totalTokens()), costUsd(), turns(), turns() == 1 ? "" : "s");
    }

    /** Full breakdown for settings/about: input, output, cache reads, cache writes. */
    public String detailedDisplay() {
        return String.format(
                "Input:           %s tokens%n"
                + "Output:          %s tokens%n"
                + "Cache writes:    %s tokens%n"
                + "Cache reads:     %s tokens%n"
                + "Total:           %s tokens%n"
                + "Cost:            $%.4f%n"
                + "Turns:           %d",
                fmtTokens(inputTokens.get()),
                fmtTokens(outputTokens.get()),
                fmtTokens(cacheCreationTokens.get()),
                fmtTokens(cacheReadTokens.get()),
                fmtTokens(totalTokens()),
                costUsd(),
                turns.get());
    }

    private static String fmtTokens(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.2fM", n / 1_000_000.0);
    }
}
