package net.Dispatcher.web;

import net.Dispatcher.DispatcherMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * One-shot carry-over from the Create Realism builds that shipped this feature before it became its
 * own mod. Those wrote secrets and the allowlist to {@code config/realism-web/} and presets, plans,
 * folders, calibration and the deploy audit to {@code <world>/realism/}.
 *
 * <p>Copies rather than moves, and only when the new location does not exist yet — so an admin can
 * still boot the old jar, and running this twice is a no-op.
 */
public final class LegacyMigration {
    private LegacyMigration() {}

    /** {@code config/realism-web/} -> {@code config/createdispatcher/}. */
    static void migrateConfigDir(Path target) {
        Path legacy = target.getParent().resolve("realism-web");
        copyTree(legacy, target, "config");
    }

    /** {@code <world>/realism/} -> {@code <world>/createdispatcher/}. */
    public static void migrateWorldDir(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        copyTree(root.resolve("realism"), root.resolve("createdispatcher"), "world");
    }

    private static void copyTree(Path from, Path to, String what) {
        try {
            if (!Files.isDirectory(from) || Files.exists(to))
                return;
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, to.resolve(from.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
            DispatcherMod.LOGGER.info("Carried over Create Realism {} data from {} to {}", what, from, to);
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Could not carry over Create Realism {} data from {}", what, from, t);
        }
    }
}
