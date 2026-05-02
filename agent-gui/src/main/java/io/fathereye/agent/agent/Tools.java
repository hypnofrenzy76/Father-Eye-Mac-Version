package io.fathereye.agent.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java tool implementations. No JavaFX, no Anthropic SDK — these
 * are called from the agent loop's background thread and return a result
 * string that gets fed back to Claude as the tool_result content.
 *
 * <p>The five tools mirror the Python claude_agent.py CLI exactly so a
 * user moving between the two has no behavior surprises.
 */
public final class Tools {

    private Tools() {}

    /** One tool result. {@code error=true} marks the tool_result as is_error. */
    public record Result(String content, boolean error) {
        public static Result ok(String s) { return new Result(s, false); }
        public static Result err(String s) { return new Result(s, true); }
    }

    public static Result execute(String name, Map<String, Object> args, Path cwd) {
        try {
            return switch (name) {
                case "read_file" -> readFile(args);
                case "write_file" -> writeFile(args);
                case "edit_file" -> editFile(args);
                case "list_dir" -> listDir(args);
                case "bash" -> bash(args, cwd);
                default -> Result.err("unknown tool: " + name);
            };
        } catch (Exception e) {
            return Result.err("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Path resolvePath(String p) {
        if (p == null) throw new IllegalArgumentException("path is required");
        String expanded = p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
        return Paths.get(expanded).toAbsolutePath().normalize();
    }

    private static Result readFile(Map<String, Object> args) throws IOException {
        Path p = resolvePath((String) args.get("path"));
        return Result.ok(Files.readString(p));
    }

    private static Result writeFile(Map<String, Object> args) throws IOException {
        Path p = resolvePath((String) args.get("path"));
        String content = (String) args.getOrDefault("content", "");
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return Result.ok("Wrote " + content.length() + " bytes to " + p);
    }

    private static Result editFile(Map<String, Object> args) throws IOException {
        Path p = resolvePath((String) args.get("path"));
        String oldStr = (String) args.get("old_string");
        String newStr = (String) args.getOrDefault("new_string", "");
        if (oldStr == null || oldStr.isEmpty()) {
            return Result.err("old_string is required and must be non-empty");
        }
        String text = Files.readString(p);
        int first = text.indexOf(oldStr);
        if (first < 0) return Result.err("old_string not found in " + p);
        int second = text.indexOf(oldStr, first + 1);
        if (second >= 0) return Result.err("old_string appears more than once in " + p + "; needs to be unique");
        Files.writeString(p, text.substring(0, first) + newStr + text.substring(first + oldStr.length()));
        return Result.ok("Edited " + p);
    }

    private static Result listDir(Map<String, Object> args) throws IOException {
        String pathArg = (String) args.getOrDefault("path", ".");
        Path p = resolvePath(pathArg);
        if (!Files.isDirectory(p)) return Result.err(p + " is not a directory");
        List<String> entries = new ArrayList<>();
        try (var stream = Files.list(p)) {
            stream.sorted(Comparator.comparing(Path::getFileName))
                  .forEach(child -> entries.add(
                          child.getFileName().toString() + (Files.isDirectory(child) ? "/" : "")));
        }
        return Result.ok(entries.isEmpty() ? "(empty)" : String.join("\n", entries));
    }

    private static Result bash(Map<String, Object> args, Path cwd) throws IOException, InterruptedException {
        String command = (String) args.get("command");
        if (command == null || command.isEmpty()) return Result.err("command is required");
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-lc", command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            return Result.err("timed out after 300s");
        }
        String stdout = new String(proc.getInputStream().readAllBytes());
        String stderr = new String(proc.getErrorStream().readAllBytes());
        StringBuilder out = new StringBuilder();
        if (!stdout.isEmpty()) out.append("STDOUT:\n").append(stdout);
        if (!stderr.isEmpty()) {
            if (out.length() > 0) out.append("\n");
            out.append("STDERR:\n").append(stderr);
        }
        out.append("\n[exit ").append(proc.exitValue()).append(']');
        return new Result(out.toString(), proc.exitValue() != 0);
    }
}
