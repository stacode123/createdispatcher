package net.Dispatcher.web;

import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-only file locations. Secrets and the allowlist deliberately live outside any
 * ForgeConfigSpec: COMMON tomls ship in client installs and SERVER-type configs sync to clients.
 */
public final class WebPaths {
    private WebPaths() {}

    public static Path configDir() throws IOException {
        Path dir = Platform.getConfigFolder().resolve("createdispatcher");
        // Before creating it — the carry-over only runs while the new directory is absent.
        LegacyMigration.migrateConfigDir(dir);
        Files.createDirectories(dir);
        return dir;
    }

    public static Path secretsFile() throws IOException {
        return configDir().resolve("secrets.json");
    }

    public static Path allowlistFile() throws IOException {
        return configDir().resolve("allowlist.json");
    }
}
