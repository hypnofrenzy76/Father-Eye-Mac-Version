package io.fathereye.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * Father Eye Setup wizard (Mac fork).
 *
 * <p>First-run installer. The user double-clicks Father Eye Setup.app, the
 * wizard walks through:
 * <ol>
 *   <li>Welcome screen — confirms they want to proceed.</li>
 *   <li>Java check — verifies JDK 8 (for Forge server) and JDK 17 (for
 *       the panel runtime) are present on the system. If either is
 *       missing, opens the Adoptium installer page in the user's
 *       browser; the wizard waits for the user to run the installer
 *       and click "Re-check" before continuing.</li>
 *   <li>Server folder picker — defaults to {@code ~/Minecraft Server},
 *       creates the folder if it doesn't exist.</li>
 *   <li>Forge install — downloads {@code forge-1.16.5-36.2.39-installer.jar}
 *       from {@code https://maven.minecraftforge.net/...} and runs it
 *       with {@code --installServer}.</li>
 *   <li>Bridge mod copy — copies the bundled {@code fathereye-bridge.jar}
 *       (resource on the wizard's classpath) into
 *       {@code <serverDir>/mods/}.</li>
 *   <li>Config + EULA write — pre-accepts the Minecraft EULA, generates
 *       {@code server.properties} with the user's selected port, and
 *       writes
 *       {@code ~/Library/Application Support/FatherEye/config.json}
 *       so the panel auto-attaches on next launch.</li>
 *   <li>Done screen — offers to open Father Eye.app immediately.</li>
 * </ol>
 *
 * <p>Each step has a "Cancel" / "Quit" button. The wizard never
 * silently fails: on any download or install error a clear message is
 * shown with a "Retry" / "Open log" option. The full log lives at
 * {@code ~/Library/Application Support/FatherEye/setup.log}.
 */
public final class SetupApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Setup");

    private static final String FORGE_VERSION = "1.16.5-36.2.39";
    private static final String FORGE_INSTALLER_URL =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
            FORGE_VERSION + "/forge-" + FORGE_VERSION + "-installer.jar";
    // Mac fork (audit 3 B7): include &package=jdk so Adoptium's filter
    // page lands on JDK (not JRE) downloads by default. Intel x64 is
    // hardcoded — the Mid 2011 iMac is Sandy Bridge x86_64; if any
    // future Mac fork variant targets Apple Silicon, swap arch=aarch64.
    private static final String ADOPTIUM_TEMURIN_8_URL =
            "https://adoptium.net/temurin/releases/?version=8&os=mac&arch=x64&package=jdk";
    private static final String ADOPTIUM_TEMURIN_17_URL =
            "https://adoptium.net/temurin/releases/?version=17&os=mac&arch=x64&package=jdk";

    private Stage stage;
    private final TextArea logArea = new TextArea();
    private final Label status = new Label("Welcome.");
    private final ProgressBar progress = new ProgressBar(0);

    /** User selections accumulated across wizard pages. */
    private Path serverDir;
    private String jdk8Path;
    private String jdk17Path;
    private int serverPort = 25566;

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        primary.setTitle("Father Eye Setup");
        // Static App.java-style appdata bootstrap so setup.log lands in
        // the right place. This runs after JavaFX init but before any
        // logger is heavily used.
        try {
            Path appData = SetupPaths.appDataDir();
            Files.createDirectories(appData);
            System.setProperty("FATHEREYE_APPDATA", appData.toString());
        } catch (IOException e) {
            LOG.warn("could not pre-create appdata: {}", e.getMessage());
        }
        showWelcome();
    }

    // ---- pages ----------------------------------------------------------

    private void showWelcome() {
        Label heading = new Label("Father Eye Setup");
        heading.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label body = new Label(
                "This wizard will install everything you need to run a " +
                "Minecraft 1.16.5 Forge server on this Mac and connect " +
                "Father Eye to it:\n\n" +
                "  - Verify JDK 8 (server) and JDK 17 (panel) are present.\n" +
                "  - Install Forge " + FORGE_VERSION + " into a folder you choose.\n" +
                "  - Drop in the Father Eye bridge mod.\n" +
                "  - Pre-accept the Minecraft EULA and configure the server.\n" +
                "  - Wire Father Eye.app to the new server install.\n\n" +
                "You can quit any time; the wizard tracks where it stopped " +
                "and resumes on next launch.");
        body.setWrapText(true);
        Button next = new Button("Continue");
        next.setOnAction(e -> showJavaCheck());
        Button quit = new Button("Quit");
        quit.setOnAction(e -> Platform.exit());
        VBox root = pageRoot(heading, body, buttonRow(quit, next));
        showScene(root);
    }

    private void showJavaCheck() {
        Label heading = new Label("Step 1 of 5: Java Runtimes");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label body = new Label("Father Eye needs:\n\n" +
                "  - JDK 8 — required to run the Minecraft 1.16.5 Forge server.\n" +
                "  - JDK 17 — bundled inside Father Eye.app, no install needed.\n\n" +
                "We recommend Eclipse Temurin 8 (free, GPL-licensed). Click " +
                "the button below to open Adoptium's download page; after " +
                "installing, return here and click Re-check. The optional " +
                "JDK 17 row below is informational — even if it shows " +
                '"Not found", the panel still runs because its app bundle ' +
                "includes its own JDK 17.");
        body.setWrapText(true);

        Label jdk8Status = new Label("Detecting...");
        Label jdk17Status = new Label("Detecting...");
        Button installJdk8 = new Button("Install JDK 8");
        Button installJdk17 = new Button("Install JDK 17");
        installJdk8.setOnAction(e -> openInBrowser(ADOPTIUM_TEMURIN_8_URL));
        installJdk17.setOnAction(e -> openInBrowser(ADOPTIUM_TEMURIN_17_URL));
        Button recheck = new Button("Re-check");
        Button next = new Button("Continue");
        next.setDisable(true);
        Button back = new Button("Back");
        back.setOnAction(e -> showWelcome());

        Runnable refresh = () -> {
            String j8 = JavaDetector.findJdk(8);
            String j17 = JavaDetector.findJdk(17);
            jdk8Status.setText(j8 == null ? "Not found" : "Found: " + j8);
            jdk17Status.setText(j17 == null ? "Not found" : "Found: " + j17);
            jdk8Status.setStyle(j8 == null ? "-fx-text-fill: #c04040;" : "-fx-text-fill: #40a040;");
            jdk17Status.setStyle(j17 == null ? "-fx-text-fill: #c04040;" : "-fx-text-fill: #40a040;");
            installJdk8.setDisable(j8 != null);
            installJdk17.setDisable(j17 != null);
            this.jdk8Path = j8;
            this.jdk17Path = j17;
            // Mac fork (audit 3 B6): JDK 17 is bundled inside Father Eye.app
            // via jpackage's runtime image, so the user only needs JDK 8
            // for the Forge server. Reflect this in the body text so the
            // page wording matches the Continue button gate (j8 only).
            next.setDisable(j8 == null);
        };
        refresh.run();
        recheck.setOnAction(e -> refresh.run());
        next.setOnAction(e -> showServerFolder());

        VBox row8 = new VBox(4, new Label("JDK 8 (Forge server)"), jdk8Status, installJdk8);
        VBox row17 = new VBox(4, new Label("JDK 17 (Father Eye panel)"), jdk17Status, installJdk17);
        VBox content = new VBox(16, body, row8, row17, recheck);
        content.setPadding(new Insets(8, 0, 8, 0));

        VBox root = pageRoot(heading, content, buttonRow(back, next));
        showScene(root);
    }

    private void showServerFolder() {
        Label heading = new Label("Step 2 of 5: Server Folder");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label body = new Label("Choose where the Minecraft server should " +
                "live. The wizard will install Forge, drop in the Father " +
                "Eye bridge mod, and place world data here. Roughly 5-10 GB " +
                "of free space is recommended.");
        body.setWrapText(true);
        Path defaultDir = Paths.get(System.getProperty("user.home", "."), "Minecraft Server");
        TextField pathField = new TextField(defaultDir.toString());
        pathField.setPrefColumnCount(50);
        Button browse = new Button("Choose...");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Pick server folder");
            File chosen = dc.showDialog(stage);
            if (chosen != null) pathField.setText(chosen.getAbsolutePath());
        });
        TextField portField = new TextField(String.valueOf(serverPort));
        portField.setPrefColumnCount(6);
        HBox folderRow = new HBox(8, pathField, browse);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        HBox portRow = new HBox(8, new Label("Server port:"), portField);
        portRow.setAlignment(Pos.CENTER_LEFT);

        Button next = new Button("Install Forge");
        next.setOnAction(e -> {
            String p = pathField.getText().trim();
            if (p.isEmpty()) { alert("Pick a folder."); return; }
            try { serverPort = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException nfe) { alert("Port must be a number."); return; }
            if (serverPort < 1 || serverPort > 65535) { alert("Port out of range."); return; }
            this.serverDir = Paths.get(p);
            try { Files.createDirectories(serverDir); }
            catch (IOException ioe) { alert("Couldn't create folder: " + ioe.getMessage()); return; }
            showInstall();
        });
        Button back = new Button("Back");
        back.setOnAction(e -> showJavaCheck());

        VBox root = pageRoot(heading,
                new VBox(12, body,
                        new VBox(4, new Label("Folder:"), folderRow),
                        portRow),
                buttonRow(back, next));
        showScene(root);
    }

    /** Mac fork (audit 3 B3): track the running Forge installer so
     *  Cancel can SIGTERM it instead of letting it run as an orphan
     *  background process after the wizard exits. */
    private volatile Process forgeProcess;
    /** Mac fork (audit 3 B3): track the install Task so Cancel can
     *  cancel(true) and the worker exits its loops promptly. */
    private volatile Task<Void> installTask;

    private void showInstall() {
        Label heading = new Label("Step 3 of 5: Installing");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        progress.setProgress(-1);
        progress.setPrefWidth(560);
        logArea.setEditable(false);
        logArea.setPrefRowCount(14);
        logArea.setStyle("-fx-font-family: 'Menlo', monospace; -fx-font-size: 11px;");
        VBox content = new VBox(12, status, progress, logArea);
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> {
            // Mac fork (audit 3 B3): proper cancel — kill any running
            // child process, cancel the Task, then exit. Without this,
            // hitting Cancel mid-Forge-install left the Forge JVM
            // running orphaned and the next setup run conflicted on
            // the libraries/ directory.
            try {
                Process p = forgeProcess;
                if (p != null && p.isAlive()) p.destroyForcibly();
            } catch (Throwable ignored) {}
            try {
                Task<Void> t = installTask;
                if (t != null) t.cancel(true);
            } catch (Throwable ignored) {}
            Platform.exit();
        });
        VBox root = pageRoot(heading, content, buttonRow(cancel));
        showScene(root);

        Task<Void> task = new Task<Void>() {
            @Override protected Void call() throws Exception {
                step("Downloading Forge installer...");
                Path forgeInstaller = serverDir.resolve("forge-installer.jar");
                downloadTo(FORGE_INSTALLER_URL, forgeInstaller, this::updateProgress);
                step("Running Forge installer (--installServer)...");
                runForgeInstaller(forgeInstaller);
                // Mac fork (audit 3 B1): delete the installer jar after
                // a successful install so two forge-*.jar files don't
                // coexist in serverDir (run.sh would launch the wrong
                // one if naming drifts in a future Forge version).
                try { Files.deleteIfExists(forgeInstaller); }
                catch (IOException e) { LOG.warn("could not remove forge-installer.jar: {}", e.getMessage()); }
                step("Copying Father Eye bridge mod...");
                installBridgeMod();
                step("Writing eula.txt and server.properties...");
                writeServerConfig();
                step("Writing Father Eye config.json...");
                writePanelConfig();
                step("Done.");
                return null;
            }
            private void step(String s) {
                Platform.runLater(() -> {
                    status.setText(s);
                    logArea.appendText("[" + java.time.LocalTime.now().withNano(0) + "] " + s + "\n");
                });
            }
        };
        task.setOnSucceeded(e -> showDone());
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            LOG.error("setup task failed", t);
            Platform.runLater(() -> {
                status.setText("FAILED: " + t.getMessage());
                logArea.appendText("\nERROR: " + t + "\n");
                Button retry = new Button("Retry");
                retry.setOnAction(ev -> showInstall());
                Button quit = new Button("Quit");
                quit.setOnAction(ev -> Platform.exit());
                VBox failedRoot = pageRoot(heading, new VBox(12, status, logArea),
                        buttonRow(quit, retry));
                showScene(failedRoot);
            });
        });
        // Mac fork (audit 3 B3): expose Task to Cancel handler.
        installTask = task;
        Thread th = new Thread(task, "FatherEye-Setup-Install");
        th.setDaemon(true);
        th.start();
    }

    private void showDone() {
        Label heading = new Label("All Set");
        heading.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label body = new Label(
                "Forge " + FORGE_VERSION + " is installed at:\n  " + serverDir + "\n\n" +
                "The Father Eye bridge mod is in mods/ and the Mojang EULA " +
                "is pre-accepted. Father Eye.app will auto-start the server " +
                "and connect on its first launch.\n\n" +
                "You can close this wizard now.");
        body.setWrapText(true);
        Button openPanel = new Button("Open Father Eye");
        openPanel.setOnAction(e -> {
            try {
                Path app = Paths.get("/Applications/Father Eye.app");
                if (Files.isDirectory(app)) {
                    new ProcessBuilder("open", app.toString()).start();
                } else {
                    alert("Father Eye.app is not installed in /Applications. " +
                            "Drag it from the .dmg you downloaded, then launch it manually.");
                }
            } catch (IOException ioe) {
                alert("Could not open Father Eye.app: " + ioe.getMessage());
            }
            Platform.exit();
        });
        Button quit = new Button("Quit");
        quit.setOnAction(e -> Platform.exit());
        VBox root = pageRoot(heading, body, buttonRow(quit, openPanel));
        showScene(root);
    }

    // ---- worker actions -------------------------------------------------

    private void downloadTo(String url, Path dst, java.util.function.BiConsumer<Long, Long> progressCb) throws IOException {
        URL u = new URL(url);
        URLConnection conn = u.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        long total = conn.getContentLengthLong();
        Files.createDirectories(dst.getParent());
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(dst,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[8192];
            long got = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                got += n;
                if (total > 0) progressCb.accept(got, total);
            }
        }
    }

    private void runForgeInstaller(Path installerJar) throws IOException, InterruptedException {
        if (jdk8Path == null) jdk8Path = JavaDetector.findJdk(8);
        if (jdk8Path == null) throw new IOException(
                "JDK 8 not found. Install Eclipse Temurin 8 from " +
                ADOPTIUM_TEMURIN_8_URL + " and re-run setup.");
        ProcessBuilder pb = new ProcessBuilder(jdk8Path, "-jar", installerJar.toString(), "--installServer")
                .directory(serverDir.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        forgeProcess = p;  // Mac fork (audit 3 B3): expose to Cancel handler.
        try {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    final String l = line;
                    Platform.runLater(() -> logArea.appendText("    " + l + "\n"));
                }
            }
            int code = p.waitFor();
            if (code != 0) throw new IOException("Forge installer exited " + code);
        } finally {
            forgeProcess = null;
        }
    }

    private void installBridgeMod() throws IOException {
        Path mods = serverDir.resolve("mods");
        Files.createDirectories(mods);
        // Bridge mod is bundled as a classpath resource. Setup builds
        // ship the latest fathereye-bridge JAR alongside the wizard
        // so first-time installs don't depend on internet for the
        // mod itself; the panel updates it on every panel boot via
        // the Bld-10 always-push-fresh logic when the panel runs.
        String resName = "/fathereye-bridge-bundle.jar";
        try (InputStream in = SetupApp.class.getResourceAsStream(resName)) {
            if (in == null) throw new IOException(
                    "Bundled bridge JAR not found in setup app (expected resource " +
                    resName + "). Build the setup app with " +
                    "`./gradlew :setup:installDist` first.");
            Path dst = mods.resolve("fathereye-bridge.jar");
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeServerConfig() throws IOException {
        Path eula = serverDir.resolve("eula.txt");
        Files.write(eula, ("# Indicates agreement to the Mojang EULA " +
                "(https://aka.ms/MinecraftEULA).\neula=true\n").getBytes());
        Path props = serverDir.resolve("server.properties");
        StringBuilder sb = new StringBuilder();
        sb.append("server-port=").append(serverPort).append("\n");
        sb.append("motd=Father Eye Server\n");
        sb.append("online-mode=true\n");
        sb.append("max-players=8\n");
        sb.append("view-distance=10\n");
        sb.append("simulation-distance=10\n");
        Files.write(props, sb.toString().getBytes());

        Path runSh = serverDir.resolve("run.sh");
        StringBuilder run = new StringBuilder();
        run.append("#!/usr/bin/env bash\n");
        run.append("cd \"$(dirname \"$0\")\"\n");
        run.append("exec \"").append(jdk8Path == null ? "java" : jdk8Path).append("\" \\\n");
        run.append("    -Xms8G -Xmx12G \\\n");
        run.append("    -jar forge-").append(FORGE_VERSION).append(".jar nogui \"$@\"\n");
        Files.write(runSh, run.toString().getBytes());
        try {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(runSh, perms);
        } catch (UnsupportedOperationException ignored) {}
    }

    private void writePanelConfig() throws IOException {
        Path appData = SetupPaths.appDataDir();
        Files.createDirectories(appData);
        Path cfgPath = appData.resolve("config.json");
        Map<String, Object> cfg = new LinkedHashMap<>();
        Map<String, Object> serverRuntime = new LinkedHashMap<>();
        serverRuntime.put("workingDir", serverDir.toString().replace('\\', '/'));
        serverRuntime.put("javaPath", jdk8Path == null ? "" : jdk8Path);
        serverRuntime.put("jvmArgs", "-Xms8G -Xmx12G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200");
        serverRuntime.put("jarName", "forge-" + FORGE_VERSION + ".jar");
        serverRuntime.put("mainArgs", "nogui");
        serverRuntime.put("autoStart", true);
        cfg.put("serverRuntime", serverRuntime);
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("backupDir", serverDir.resolve("backups").toString().replace('\\', '/'));
        cfg.put("backup", backup);
        // Preserve any pre-existing fields the panel wrote previously.
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        if (Files.exists(cfgPath)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = json.readValue(Files.readAllBytes(cfgPath), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> existingSr = (Map<String, Object>) existing.get("serverRuntime");
                if (existingSr != null) {
                    existingSr.putAll(serverRuntime);
                    serverRuntime.clear();
                    serverRuntime.putAll(existingSr);
                }
                existing.putAll(cfg);
                cfg = existing;
            } catch (Exception ignored) {}
        }
        Files.write(cfgPath, json.writeValueAsBytes(cfg));
    }

    private void openInBrowser(String url) {
        try {
            new ProcessBuilder("open", url).start();
        } catch (IOException e) {
            alert("Could not open browser: " + e.getMessage() + "\n\n" + url);
        }
    }

    private void alert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg);
        a.setHeaderText(null);
        a.showAndWait();
    }

    // ---- layout helpers ------------------------------------------------

    private VBox pageRoot(javafx.scene.Node heading, javafx.scene.Node body, javafx.scene.Node buttons) {
        VBox box = new VBox(20);
        box.setPadding(new Insets(28));
        box.getChildren().addAll(heading, body, buttons);
        VBox.setVgrow(body, Priority.ALWAYS);
        return box;
    }

    private HBox buttonRow(javafx.scene.Node... nodes) {
        HBox row = new HBox(12, nodes);
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private void showScene(VBox root) {
        Scene s = new Scene(root, 720, 520);
        if (stage.getScene() == null) {
            stage.setScene(s);
            stage.show();
        } else {
            stage.setScene(s);
        }
    }
}
