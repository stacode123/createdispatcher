package net.Dispatcher.web.deploy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.Dispatcher.DispatcherMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Append-only deploy journal at {@code <world>/realism/web-audit.jsonl} — one JSON object
 * per line, written on the IO thread so a slow disk never stalls a tick. Deploy is the one
 * web action that changes the running world, so every attempt is recorded, successful or
 * not. Rotates to {@code web-audit.1.jsonl} past {@link #MAX_BYTES} (one generation kept).
 */
public final class AuditLog {
    /** Rotate at 4 MB; roughly 30k deploy lines. */
    static final long MAX_BYTES = 4L * 1024 * 1024;
    /** Tail window read for {@code /api/audit} — the endpoint never loads the whole journal. */
    private static final int TAIL_BYTES = 256 * 1024;

    private static volatile AuditLog instance;

    /** The journal for this server, created on first use. */
    public static synchronized AuditLog of(MinecraftServer server) {
        AuditLog current = instance;
        if (current != null && current.server == server) return current;
        if (current != null) current.io.shutdown();
        current = new AuditLog(server);
        instance = current;
        return current;
    }

    private final MinecraftServer server;
    private final Path file;
    private final Path rotated;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dispatcher-Web-IO");
        thread.setDaemon(true);
        return thread;
    });

    private AuditLog(MinecraftServer server) {
        this.server = server;
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("createdispatcher");
        this.file = directory.resolve("web-audit.jsonl");
        this.rotated = directory.resolve("web-audit.1.jsonl");
    }

    /** Any thread: queues one line. The wall-clock stamp is taken here, not on the IO thread. */
    public void append(JsonObject entry) {
        entry.addProperty("ts", System.currentTimeMillis());
        String line = entry.toString();
        io.execute(() -> write(line));
    }

    private void write(String line) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_BYTES)
                Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            DispatcherMod.LOGGER.error("Dispatcher web: could not write the deploy audit log", e);
        }
    }

    /**
     * The newest {@code limit} entries, newest first. Reads at most the last
     * {@value #TAIL_BYTES} bytes, so the journal can grow without making this expensive;
     * a partial first line from that cut is dropped.
     */
    public JsonArray tail(int limit) {
        JsonArray out = new JsonArray();
        Deque<String> lines = new ArrayDeque<>();
        try {
            if (!Files.exists(file)) return out;
            long size = Files.size(file);
            long from = Math.max(0, size - TAIL_BYTES);
            byte[] buffer = new byte[(int) Math.min(size - from, TAIL_BYTES)];
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(from);
                raf.readFully(buffer);
            }
            String text = new String(buffer, StandardCharsets.UTF_8);
            String[] split = text.split("\n");
            for (int i = (from > 0 ? 1 : 0); i < split.length; i++) {
                if (split[i].isBlank()) continue;
                lines.addLast(split[i]);
                if (lines.size() > limit) lines.removeFirst();
            }
        } catch (IOException e) {
            DispatcherMod.LOGGER.error("Dispatcher web: could not read the deploy audit log", e);
            return out;
        }
        while (!lines.isEmpty()) {
            String line = lines.removeLast();
            try {
                out.add(JsonParser.parseString(line).getAsJsonObject());
            } catch (Exception ignored) {
                // a truncated tail line — skip it rather than fail the whole request
            }
        }
        return out;
    }
}
