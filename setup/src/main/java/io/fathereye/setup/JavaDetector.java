package io.fathereye.setup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort detection of installed JDKs on macOS. Walks
 * {@code /Library/Java/JavaVirtualMachines/*}, runs the candidate's
 * {@code java -version}, parses the output for a major version, and
 * returns the first installation matching the requested major.
 *
 * <p>Why not just use {@code /usr/libexec/java_home -v 1.8}? Because
 * Apple's java_home wrapper is unreliable when the user has multiple
 * JDKs installed but their {@code .plist} bundle metadata is not in
 * Apple's expected format (Adoptium installs are fine, but third-party
 * tarballs are not). Walking the directory directly is robust.
 */
public final class JavaDetector {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?:openjdk|java) version \"((?:1\\.)?(\\d+))");

    private JavaDetector() {}

    /**
     * Find a JDK with the requested major version (8, 11, 17). Returns
     * the absolute path to the {@code java} binary, or null if no
     * matching install was found.
     */
    public static String findJdk(int wantMajor) {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean mac = os.startsWith("mac") || os.contains("darwin") || os.contains("os x");
        if (mac) {
            Path root = Paths.get("/Library/Java/JavaVirtualMachines");
            if (Files.isDirectory(root)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                    for (Path entry : stream) {
                        Path java = entry.resolve("Contents/Home/bin/java");
                        if (Files.isExecutable(java)) {
                            int got = readMajor(java);
                            if (got == wantMajor) return java.toString();
                        }
                    }
                } catch (Exception ignored) {}
            }
            // Fallback: ask Apple's java_home wrapper.
            Path apple = Paths.get("/usr/libexec/java_home");
            if (Files.isExecutable(apple)) {
                String v = wantMajor == 8 ? "1.8" : String.valueOf(wantMajor);
                try {
                    Process p = new ProcessBuilder(apple.toString(), "-v", v).start();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line = r.readLine();
                        if (line != null && !line.isEmpty()) {
                            Path java = Paths.get(line.trim(), "bin", "java");
                            if (Files.isExecutable(java)) return java.toString();
                        }
                    }
                    p.waitFor();
                } catch (Exception ignored) {}
            }
        }
        // $JAVA_HOME (works on every platform).
        String jhome = System.getenv("JAVA_HOME");
        if (jhome != null && !jhome.isEmpty()) {
            Path java = Paths.get(jhome, "bin", os.startsWith("windows") ? "java.exe" : "java");
            if (Files.isExecutable(java)) {
                int got = readMajor(java);
                if (got == wantMajor) return java.toString();
            }
        }
        return null;
    }

    private static int readMajor(Path java) {
        try {
            Process p = new ProcessBuilder(java.toString(), "-version")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = VERSION_PATTERN.matcher(line);
                    if (m.find()) {
                        String prefix = m.group(1);
                        // 1.8 -> major 8; 17.0.x / 11.0.x -> already major.
                        if (prefix.startsWith("1.")) return Integer.parseInt(m.group(2));
                        return Integer.parseInt(m.group(2));
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return -1;
    }
}
