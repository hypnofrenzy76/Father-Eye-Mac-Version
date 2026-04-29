package io.fathereye.bridge.ipc;

import io.fathereye.bridge.topic.Topics;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-session subscription set. Topic names map to bit positions; the
 * underlying {@code volatile long} is updated by the reader thread and read
 * by the publisher thread without locking.
 */
public final class Subscriptions {

    private static final Map<String, Integer> BITS = new HashMap<>();
    static {
        BITS.put(Topics.TPS,             0);
        BITS.put(Topics.MODS_IMPACT,     1);
        BITS.put(Topics.PLAYERS,         2);
        BITS.put(Topics.MOBS,            3);
        BITS.put(Topics.CHUNKS,          4);
        BITS.put(Topics.CONSOLE_LOG,     5);
        BITS.put(Topics.EVENT_CHAT,      6);
        BITS.put(Topics.EVENT_JOIN,      7);
        BITS.put(Topics.EVENT_LEAVE,     8);
        BITS.put(Topics.EVENT_CRASH,     9);
        BITS.put(Topics.EVENT_ALERT,    10);
        BITS.put(Topics.EVENT_SHUTDOWN, 11);
    }

    private volatile long mask = 0L;

    public void subscribe(String topic) {
        Integer b = BITS.get(topic);
        if (b != null) mask |= (1L << b);
    }

    public void unsubscribe(String topic) {
        Integer b = BITS.get(topic);
        if (b != null) mask &= ~(1L << b);
    }

    public boolean isSubscribed(String topic) {
        Integer b = BITS.get(topic);
        if (b == null) return false;
        return (mask & (1L << b)) != 0L;
    }

    public boolean any() { return mask != 0L; }

    public void clear() { mask = 0L; }

    public static boolean isKnownTopic(String topic) {
        return BITS.containsKey(topic);
    }
}
