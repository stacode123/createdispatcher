package net.Dispatcher.content.trains.schedule.presets;

import com.simibubi.create.content.trains.schedule.Schedule;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The schedule preset library: one JSON file per preset under
 * {@code <world>/realism/presets/}. All mutations run on the server thread
 * (packets arrive there; web callers marshal via {@code server.execute});
 * disk writes go to a single IO thread. Reads are safe from any thread —
 * the map is concurrent and {@link Preset}s immutable.
 */
public final class PresetStore {
    /** Mirror of {@code AdvancedScheduleSavePacket}'s decode cap. */
    public static final long MAX_SNBT_BYTES = 262144;
    /** Nesting cap for folder paths — deep trees are unusable in a 280px panel. */
    public static final int MAX_FOLDER_DEPTH = 4;

    /** Mutation failure with a stable key: chat = {@code realism.preset.error.<key>}, web = error code. */
    public static final class PresetException extends Exception {
        public final String key;

        public PresetException(String key, String detail) {
            super(detail);
            this.key = key;
        }
    }

    private static volatile PresetStore instance;

    /** The store for this server, created (and loaded from disk) on first use. */
    public static synchronized PresetStore of(MinecraftServer server) {
        PresetStore current = instance;
        if (current != null && current.server == server)
            return current;
        if (current != null)
            current.io.shutdown();
        current = new PresetStore(server);
        instance = current;
        return current;
    }

    private final MinecraftServer server;
    private final Path directory;
    private final Map<UUID, Preset> presets = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dispatcher-Web-IO");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Runnable changeListener;

    private PresetStore(MinecraftServer server) {
        this.server = server;
        this.directory = server.getWorldPath(LevelResource.ROOT).resolve("createdispatcher").resolve("presets");
        load();
    }

    private void load() {
        if (!Files.isDirectory(directory))
            return;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    Preset preset = PresetJson.fromJson(Files.readString(path, StandardCharsets.UTF_8));
                    presets.put(preset.id(), preset);
                } catch (Exception e) {
                    DispatcherMod.LOGGER.error("Skipping unreadable preset file {}", path, e);
                }
            });
        } catch (IOException e) {
            DispatcherMod.LOGGER.error("Could not list preset directory {}", directory, e);
        }
        if (!presets.isEmpty())
            DispatcherMod.LOGGER.info("Loaded {} schedule preset(s)", presets.size());
    }

    /** Fired after every successful mutation (server thread). */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    public Preset get(UUID id) {
        return presets.get(id);
    }

    public int size() {
        return presets.size();
    }

    /** Name-sorted snapshot. */
    public List<Preset> list() {
        List<Preset> list = new ArrayList<>(presets.values());
        list.sort(Comparator.comparing(Preset::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Preset::id));
        return list;
    }

    public Preset create(String name, String source, CompoundTag scheduleTag) throws PresetException {
        return create(name, "", source, scheduleTag);
    }

    public Preset create(String name, String folder, String source, CompoundTag scheduleTag)
            throws PresetException {
        if (presets.size() >= DispatcherConfig.COMMON.WebPresetMaxCount.get())
            throw new PresetException("preset_full", "library holds the configured maximum");
        Sanitized sanitized = sanitize(scheduleTag);
        long now = System.currentTimeMillis();
        Preset preset = new Preset(UUID.randomUUID(), validName(name), normalizeFolder(folder),
                source, now, now, sanitized.entries, sanitized.snbt);
        presets.put(preset.id(), preset);
        persist(preset);
        fireChanged();
        return preset;
    }

    public Preset rename(UUID id, String name) throws PresetException {
        Preset previous = require(id);
        Preset renamed = new Preset(previous.id(), validName(name), previous.folder(),
                previous.source(), previous.createdMs(), System.currentTimeMillis(),
                previous.entries(), previous.scheduleSnbt());
        presets.put(id, renamed);
        persist(renamed);
        fireChanged();
        return renamed;
    }

    /** Files a preset under {@code folder}; blank unfiles it. */
    public Preset move(UUID id, String folder) throws PresetException {
        Preset previous = require(id);
        Preset moved = new Preset(previous.id(), previous.name(), normalizeFolder(folder),
                previous.source(), previous.createdMs(), System.currentTimeMillis(),
                previous.entries(), previous.scheduleSnbt());
        presets.put(id, moved);
        persist(moved);
        fireChanged();
        return moved;
    }

    /** Re-files every preset under {@code from} (and its children) to {@code to}. */
    public int moveFolder(String from, String to) throws PresetException {
        String source = normalizeFolder(from);
        String target = normalizeFolder(to);
        if (source.isEmpty() || source.equals(target))
            return 0;
        int moved = 0;
        for (Preset preset : list()) {
            String folder = preset.folder();
            if (!folder.equals(source) && !folder.startsWith(source + "/"))
                continue;
            String suffix = folder.substring(source.length());
            move(preset.id(), target.isEmpty() && suffix.startsWith("/")
                    ? suffix.substring(1) : target + suffix);
            moved++;
        }
        return moved;
    }

    public Preset updateSchedule(UUID id, CompoundTag scheduleTag) throws PresetException {
        Preset previous = require(id);
        Sanitized sanitized = sanitize(scheduleTag);
        Preset updated = new Preset(previous.id(), previous.name(), previous.folder(),
                previous.source(), previous.createdMs(), System.currentTimeMillis(),
                sanitized.entries, sanitized.snbt);
        presets.put(id, updated);
        persist(updated);
        fireChanged();
        return updated;
    }

    public void delete(UUID id) throws PresetException {
        require(id);
        presets.remove(id);
        io.execute(() -> {
            try {
                Files.deleteIfExists(directory.resolve(id + ".json"));
            } catch (IOException e) {
                DispatcherMod.LOGGER.error("Could not delete preset file {}", id, e);
            }
        });
        fireChanged();
    }

    /** The preset's schedule as a fresh tag, or null when its SNBT no longer parses. */
    public CompoundTag scheduleTag(Preset preset) {
        try {
            return TagParser.parseTag(preset.scheduleSnbt());
        } catch (Exception e) {
            DispatcherMod.LOGGER.error("Stored preset {} has unparsable SNBT", preset.id(), e);
            return null;
        }
    }

    private Preset require(UUID id) throws PresetException {
        Preset preset = id == null ? null : presets.get(id);
        if (preset == null)
            throw new PresetException("not_found", "no preset with that id");
        return preset;
    }

    private record Sanitized(String snbt, int entries) {}

    /** The AdvancedScheduleSavePacket template: full Create round trip, then size/entry gates. */
    private static Sanitized sanitize(CompoundTag scheduleTag) throws PresetException {
        Schedule schedule;
        CompoundTag sanitized;
        try {
            schedule = Schedule.fromTag(scheduleTag);
            sanitized = schedule.write();
        } catch (Exception e) {
            throw new PresetException("preset_invalid", "schedule failed Create's round trip");
        }
        if (schedule.entries.isEmpty())
            throw new PresetException("preset_empty", "schedule has no entries");
        String snbt = sanitized.toString();
        if (snbt.getBytes(StandardCharsets.UTF_8).length > MAX_SNBT_BYTES)
            throw new PresetException("preset_invalid", "schedule exceeds the size cap");
        return new Sanitized(snbt, schedule.entries.size());
    }

    /** Folder paths are a display convenience — keep them tame and canonical. */
    public static String normalizeFolder(String folder) throws PresetException {
        if (folder == null)
            return "";
        StringBuilder path = new StringBuilder();
        int segments = 0;
        for (String raw : folder.replace('\\', '/').split("/")) {
            String segment = raw.trim();
            if (segment.isEmpty())
                continue;
            if (++segments > MAX_FOLDER_DEPTH)
                throw new PresetException("bad_folder", "at most " + MAX_FOLDER_DEPTH + " levels");
            if (segment.length() > 40)
                throw new PresetException("bad_folder", "folder names must be 1-40 characters");
            for (int i = 0; i < segment.length(); i++)
                if (segment.charAt(i) < 0x20)
                    throw new PresetException("bad_folder", "control characters are not allowed");
            if (path.length() > 0)
                path.append('/');
            path.append(segment);
        }
        return path.toString();
    }

    private static String validName(String name) throws PresetException {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 60)
            throw new PresetException("bad_name", "names must be 1-60 characters");
        return trimmed;
    }

    private void persist(Preset preset) {
        String json = PresetJson.toJson(preset);
        io.execute(() -> {
            try {
                Files.createDirectories(directory);
                Path file = directory.resolve(preset.id() + ".json");
                Path tmp = directory.resolve(preset.id() + ".json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                DispatcherMod.LOGGER.error("Could not persist preset {}", preset.id(), e);
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
            DispatcherMod.LOGGER.error("Preset change listener failed", t);
        }
    }
}
