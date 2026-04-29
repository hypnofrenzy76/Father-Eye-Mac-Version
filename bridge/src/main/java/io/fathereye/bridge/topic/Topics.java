package io.fathereye.bridge.topic;

/**
 * Stable topic names. Both panel and bridge agree on these strings; they
 * are part of the wire protocol contract.
 */
public final class Topics {

    public static final String TPS               = "tps_topic";
    public static final String MODS_IMPACT       = "mods_impact_topic";
    public static final String PLAYERS           = "players_topic";
    public static final String MOBS              = "mobs_topic";
    public static final String CHUNKS            = "chunks_topic";
    public static final String CONSOLE_LOG       = "console_log";
    public static final String EVENT_CHAT        = "event_chat";
    public static final String EVENT_JOIN        = "event_join";
    public static final String EVENT_LEAVE       = "event_leave";
    public static final String EVENT_CRASH       = "event_crash";
    public static final String EVENT_ALERT       = "event_alert";
    public static final String EVENT_SHUTDOWN    = "event_shutdown";

    private Topics() {}
}
