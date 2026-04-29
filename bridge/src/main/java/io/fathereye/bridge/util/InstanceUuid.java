package io.fathereye.bridge.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Per-server-instance UUID. Generated on first boot, persisted under the
 * world's serverconfig directory. The pipe name and marker filename derive
 * from this.
 */
public final class InstanceUuid {

    private InstanceUuid() {}

    public static UUID loadOrCreate(Path serverConfigDir) throws IOException {
        Files.createDirectories(serverConfigDir);
        Path file = serverConfigDir.resolve("fathereye-instance.uuid");
        if (Files.exists(file)) {
            String txt = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            try {
                return UUID.fromString(txt);
            } catch (IllegalArgumentException ignored) {
                // fall through and regenerate
            }
        }
        UUID id = UUID.randomUUID();
        Files.write(file, id.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return id;
    }
}
