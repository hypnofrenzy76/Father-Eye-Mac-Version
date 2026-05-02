package io.fathereye.agent.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

/**
 * JSON-Schema declarations for the five client-side tools. Mirrors the
 * Python claude_agent.py CLI verbatim so behavior stays identical between
 * the GUI and the terminal.
 *
 * <p>Built with {@link Tool.InputSchema.Properties}'s
 * {@code putAdditionalProperty} freeform-map API — the SDK doesn't expose
 * typed property builders, so each property's schema is constructed as a
 * {@code Map} and wrapped in {@link JsonValue#from}.
 */
public final class ToolSchemas {

    private ToolSchemas() {}

    public static List<Tool> all() {
        return List.of(readFile(), writeFile(), editFile(), listDir(), bash());
    }

    private static Tool readFile() {
        return Tool.builder()
                .name("read_file")
                .description("Read the text contents of a file.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path",
                                        JsonValue.from(Map.of(
                                                "type", "string",
                                                "description", "Path to the file.")))
                                .build())
                        .required(List.of("path"))
                        .build())
                .build();
    }

    private static Tool writeFile() {
        return Tool.builder()
                .name("write_file")
                .description("Create a new file or overwrite an existing one. Prefer edit_file for existing files.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path",
                                        JsonValue.from(Map.of("type", "string")))
                                .putAdditionalProperty("content",
                                        JsonValue.from(Map.of("type", "string")))
                                .build())
                        .required(List.of("path", "content"))
                        .build())
                .build();
    }

    private static Tool editFile() {
        return Tool.builder()
                .name("edit_file")
                .description("Replace exact text in a file. old_string must appear exactly once. Read the file first to confirm uniqueness.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path",
                                        JsonValue.from(Map.of("type", "string")))
                                .putAdditionalProperty("old_string",
                                        JsonValue.from(Map.of(
                                                "type", "string",
                                                "description", "Text to find. Must be unique in the file.")))
                                .putAdditionalProperty("new_string",
                                        JsonValue.from(Map.of(
                                                "type", "string",
                                                "description", "Replacement text.")))
                                .build())
                        .required(List.of("path", "old_string", "new_string"))
                        .build())
                .build();
    }

    private static Tool listDir() {
        return Tool.builder()
                .name("list_dir")
                .description("List directory contents.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path",
                                        JsonValue.from(Map.of(
                                                "type", "string",
                                                "description", "Defaults to '.'")))
                                .build())
                        .build())
                .build();
    }

    private static Tool bash() {
        return Tool.builder()
                .name("bash")
                .description("Run a shell command in the working directory. Use for git, build, test, grep, find. Not for file edits — use edit_file.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("command",
                                        JsonValue.from(Map.of("type", "string")))
                                .putAdditionalProperty("description",
                                        JsonValue.from(Map.of(
                                                "type", "string",
                                                "description", "One-line summary of what this does.")))
                                .build())
                        .required(List.of("command"))
                        .build())
                .build();
    }
}
