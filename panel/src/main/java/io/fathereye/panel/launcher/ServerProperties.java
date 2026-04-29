package io.fathereye.panel.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pnl-42 (2026-04-26): minimal {@code server.properties} parser / writer.
 *
 * <p>Vanilla Minecraft writes this file with a header comment, optional
 * blank lines, and {@code key=value} lines. {@link java.util.Properties}
 * parses it correctly but loses ordering and discards comments. This
 * helper preserves the original line list verbatim and only rewrites
 * the lines whose keys we explicitly mutate, so saving the file does
 * not reorder or strip the operator's comments.
 *
 * <p>Edge cases handled:
 * <ul>
 *   <li>Lines starting with {@code #} or {@code !} (Properties spec)
 *       are treated as comments and preserved.</li>
 *   <li>Blank lines are preserved.</li>
 *   <li>Whitespace around {@code =} is tolerated on read; on write we
 *       always emit {@code key=value} (no whitespace) to match
 *       Minecraft's own writer.</li>
 *   <li>Backslash-escaped continuations are NOT supported. The vanilla
 *       server doesn't emit them, and supporting the full Properties
 *       grammar would balloon this class for no real win.</li>
 *   <li>{@link #set(String, String)} on a key that doesn't exist
 *       APPENDS a new line at the end so callers can introduce keys.</li>
 * </ul>
 */
public final class ServerProperties {

    private final Path path;
    /** Raw lines, mutated in-place when {@link #set(String, String)} is
     *  called. Comments and blank lines stay untouched. */
    private final List<String> lines;
    /** Insertion-ordered map of key to the line index in {@link #lines}
     *  that currently holds it. Rebuilt whenever a key is added. */
    private final Map<String, Integer> keyToLineIndex;

    private ServerProperties(Path path, List<String> lines, Map<String, Integer> idx) {
        this.path = path;
        this.lines = lines;
        this.keyToLineIndex = idx;
    }

    /**
     * Read {@code server.properties} from disk. Returns an empty
     * instance (no lines, no keys) if the file does not exist, so a
     * brand-new server folder still works in the dialog.
     */
    public static ServerProperties load(Path path) throws IOException {
        List<String> lines;
        if (Files.exists(path)) {
            lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        } else {
            lines = new ArrayList<>();
        }
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String key = parseKey(lines.get(i));
            if (key != null) idx.put(key, i);
        }
        return new ServerProperties(path, lines, idx);
    }

    /** Path the instance was loaded from (and {@link #save()} writes to). */
    public Path path() { return path; }

    /**
     * Get the value for {@code key}, or {@code null} if not present.
     * Trims surrounding whitespace from the value.
     */
    public String get(String key) {
        Integer i = keyToLineIndex.get(key);
        if (i == null) return null;
        return parseValue(lines.get(i));
    }

    /**
     * Set the value for {@code key}. If the key already exists, its
     * line is rewritten in place (preserving its position in the file).
     * If not, a new {@code key=value} line is appended at the end.
     * Newlines inside the value are stripped (they would corrupt the
     * file format).
     */
    public void set(String key, String value) {
        if (key == null || key.isEmpty()) return;
        String safeValue = value == null ? "" : value.replace("\r", "").replace("\n", " ");
        Integer i = keyToLineIndex.get(key);
        String newLine = key + "=" + safeValue;
        if (i != null) {
            lines.set(i, newLine);
        } else {
            lines.add(newLine);
            keyToLineIndex.put(key, lines.size() - 1);
        }
    }

    /**
     * Snapshot of all key-value pairs in file order. Returned map is
     * a copy; mutations on it do not affect the underlying file.
     */
    public Map<String, String> asOrderedMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : keyToLineIndex.entrySet()) {
            out.put(e.getKey(), parseValue(lines.get(e.getValue())));
        }
        return out;
    }

    /** Defensive view of the keys, in file order. */
    public List<String> orderedKeys() {
        return Collections.unmodifiableList(new ArrayList<>(keyToLineIndex.keySet()));
    }

    /** Write the (possibly mutated) lines back to {@link #path}. */
    public void save() throws IOException {
        // Use a single byte buffer to avoid platform-line-ending mismatches
        // on Windows when reading back: Minecraft tolerates either, but
        // we keep the file LF-terminated for git-friendliness.
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String parseKey(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        char c0 = trimmed.charAt(0);
        if (c0 == '#' || c0 == '!') return null;
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        // Properties files allow ':' or '=' as separator. Use whichever
        // appears first to be tolerant.
        int sep = (eq >= 0 && (colon < 0 || eq < colon)) ? eq
                : (colon >= 0 ? colon : -1);
        if (sep <= 0) return null;
        String key = line.substring(0, sep).trim();
        return key.isEmpty() ? null : key;
    }

    private static String parseValue(String line) {
        if (line == null) return "";
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        int sep = (eq >= 0 && (colon < 0 || eq < colon)) ? eq
                : (colon >= 0 ? colon : -1);
        if (sep < 0) return "";
        return line.substring(sep + 1).trim();
    }
}
