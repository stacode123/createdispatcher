package net.Dispatcher.web.folder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.content.trains.schedule.presets.PresetStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Which folder each train is filed under — the roster's counterpart to preset folders.
 * A train is a Create entity we cannot annotate, so the mapping lives beside the world as
 * {@code <world>/realism/train-folders.json} (one flat {trainUuid: path} object) and is
 * shared by every planner, like the preset and plan libraries.
 *
 * <p>Purely organizational: nothing in the simulator reads it. Entries for trains that no
 * longer exist are harmless and are pruned when the file is next written.
 */
public final class TrainFolders {

    /** Sanity bound on the file — far above any real roster. */
    private static final int MAX_ENTRIES = 5000;

    private static volatile TrainFolders instance;

    public static synchronized TrainFolders of(MinecraftServer server) {
        TrainFolders current = instance;
        if (current != null && current.server == server)
            return current;
        if (current != null)
            current.io.shutdown();
        current = new TrainFolders(server);
        instance = current;
        return current;
    }

    private final MinecraftServer server;
    private final Path file;
    /** Sorted so the written file has a stable, diffable order. */
    private final Map<UUID, String> folders = new TreeMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dispatcher-Web-IO");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Runnable changeListener;

    private TrainFolders(MinecraftServer server) {
        this.server = server;
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("createdispatcher")
                .resolve("train-folders.json");
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file))
            return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject entries = json.has("folders") ? json.getAsJsonObject("folders") : json;
            for (String key : entries.keySet()) {
                try {
                    folders.put(UUID.fromString(key), entries.get(key).getAsString());
                } catch (Exception ignored) {
                    // a stale or hand-edited key costs one train's filing, not the file
                }
            }
        } catch (Exception e) {
            DispatcherMod.LOGGER.error("Could not read train folders from {}", file, e);
        }
        if (!folders.isEmpty())
            DispatcherMod.LOGGER.info("Loaded folders for {} train(s)", folders.size());
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    public synchronized Map<UUID, String> snapshot() {
        return new TreeMap<>(folders);
    }

    /**
     * Files one train; a blank folder unfiles it. Returns the normalized path.
     * Throws {@link PresetStore.PresetException} — the same {@code bad_folder} key the
     * preset side uses, so both surfaces report identically.
     */
    public synchronized String set(UUID trainId, String folder) throws PresetStore.PresetException {
        String normalized = PresetStore.normalizeFolder(folder);
        if (normalized.isEmpty()) {
            if (folders.remove(trainId) == null)
                return "";
        } else {
            if (normalized.equals(folders.get(trainId)))
                return normalized;
            if (folders.size() >= MAX_ENTRIES && !folders.containsKey(trainId))
                throw new PresetStore.PresetException("folders_full", "too many filed trains");
            folders.put(trainId, normalized);
        }
        persist();
        fireChanged();
        return normalized;
    }

    /** Re-files every train under {@code from} (and its children) to {@code to}. */
    public synchronized int moveFolder(String from, String to) throws PresetStore.PresetException {
        String source = PresetStore.normalizeFolder(from);
        String target = PresetStore.normalizeFolder(to);
        if (source.isEmpty() || source.equals(target))
            return 0;
        Map<UUID, String> updates = new TreeMap<>();
        for (Map.Entry<UUID, String> entry : folders.entrySet()) {
            String folder = entry.getValue();
            if (!folder.equals(source) && !folder.startsWith(source + "/"))
                continue;
            String suffix = folder.substring(source.length());
            String moved = target.isEmpty() && suffix.startsWith("/")
                    ? suffix.substring(1) : target + suffix;
            updates.put(entry.getKey(), PresetStore.normalizeFolder(moved));
        }
        if (updates.isEmpty())
            return 0;
        for (Map.Entry<UUID, String> update : updates.entrySet()) {
            if (update.getValue().isEmpty()) folders.remove(update.getKey());
            else folders.put(update.getKey(), update.getValue());
        }
        persist();
        fireChanged();
        return updates.size();
    }

    /**
     * Applies many targets in one pass (the deployer auto-sort button): each entry's
     * value becomes its folder path, a blank value unfiling it. A folder already holding
     * its target stays untouched. Persists and notifies once for the whole batch, not
     * once per train, so a large reorg is one write and one SSE broadcast.
     *
     * @return how many trains actually changed
     */
    public synchronized int bulkSet(Map<UUID, String> targets) throws PresetStore.PresetException {
        if (targets.isEmpty())
            return 0;
        int changed = 0;
        for (Map.Entry<UUID, String> target : targets.entrySet()) {
            String normalized = PresetStore.normalizeFolder(target.getValue());
            if (normalized.isEmpty()) {
                if (folders.remove(target.getKey()) != null)
                    changed++;
            } else if (!normalized.equals(folders.get(target.getKey()))) {
                folders.put(target.getKey(), normalized);
                changed++;
            }
        }
        if (changed > 0) {
            if (folders.size() > MAX_ENTRIES)
                throw new PresetStore.PresetException("folders_full", "too many filed trains");
            persist();
            fireChanged();
        }
        return changed;
    }

    private void persist() {
        JsonObject entries = new JsonObject();
        folders.forEach((id, folder) -> entries.addProperty(id.toString(), folder));
        JsonObject json = new JsonObject();
        json.add("folders", entries);
        String text = json.toString();
        io.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                DispatcherMod.LOGGER.error("Could not persist train folders", e);
            }
        });
    }

    private void fireChanged() {
        Runnable listener = changeListener;
        if (listener == null)
            return;
        try {
            listener.run();
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Train folder change listener failed", t);
        }
    }
}
