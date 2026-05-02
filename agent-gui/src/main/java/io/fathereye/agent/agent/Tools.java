package io.fathereye.agent.agent;

/**
 * Tool result type used by {@link AgentService.Listener#onToolResult}.
 *
 * <p>Historical note: this class previously contained pure-Java
 * implementations of read_file / write_file / edit_file / list_dir / bash
 * that ran client-side from a manual tool loop driving the Anthropic API.
 * The agent backend now delegates to the Claude Code CLI subprocess,
 * which owns its own (broader) tool surface — Read/Write/Edit/Bash/Glob/
 * Grep — so the local implementations were dropped. {@code Result} is
 * kept as the in-process value type that {@link AgentService} hands to
 * {@link io.fathereye.agent.ui.ToolCallCard} for rendering.
 */
public final class Tools {

    private Tools() {}

    /** One tool result. {@code error=true} marks a failed call (red bar in the UI). */
    public record Result(String content, boolean error) {
        public static Result ok(String s) { return new Result(s, false); }
        public static Result err(String s) { return new Result(s, true); }
    }
}
