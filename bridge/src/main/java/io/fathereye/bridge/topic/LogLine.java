package io.fathereye.bridge.topic;

/** Wire payload for {@link Topics#CONSOLE_LOG}. */
public final class LogLine {

    public long tsMs;
    public String level;
    public String logger;
    public String msg;
    public String thread;

    public LogLine() {}
}
