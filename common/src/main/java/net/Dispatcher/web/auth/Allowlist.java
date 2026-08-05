package net.Dispatcher.web.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.Dispatcher.DispatcherMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * config/createdispatcher/allowlist.json — {"users": {"<discordId>": {"tier": "viewer", "note": "name"}}}.
 * The published map is replaced wholesale on load. Reads are lock-free off the volatile map;
 * mutators are synchronized, because auto-enrolment ({@link #enroll}) runs on an HTTP thread
 * while the admin commands and the mtime poll run on the server thread.
 */
public final class Allowlist {
    public record Entry(String discordId, Tier tier, String note) {}

    private final Path file;
    private volatile Map<String, Entry> users = Map.of();
    private volatile long loadedMtime = -1;

    public Allowlist(Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        if (!Files.exists(file)) {
            save(Map.of());
        }
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Entry> loaded = new HashMap<>();
        if (root.has("users")) {
            for (Map.Entry<String, com.google.gson.JsonElement> user : root.getAsJsonObject("users").entrySet()) {
                JsonObject value = user.getValue().getAsJsonObject();
                Tier tier = Tier.parse(value.has("tier") ? value.get("tier").getAsString() : null);
                String note = value.has("note") ? value.get("note").getAsString() : "";
                loaded.put(user.getKey(), new Entry(user.getKey(), tier, note));
            }
        }
        users = Map.copyOf(loaded);
        loadedMtime = mtime();
    }

    /** Cheap mtime poll from the tick handler; reloads when the file changed on disk. */
    public boolean pollReload() {
        try {
            long current = mtime();
            if (current == loadedMtime) return false;
            load();
            return true;
        } catch (Exception e) {
            DispatcherMod.LOGGER.error("Dispatcher web: failed to reload allowlist", e);
            return false;
        }
    }

    public Tier tierOf(String discordId) {
        Entry entry = users.get(discordId);
        return entry == null ? Tier.NONE : entry.tier();
    }

    public List<Entry> entries() {
        List<Entry> list = new ArrayList<>(users.values());
        list.sort(Comparator.comparing(Entry::discordId));
        return list;
    }

    public int size() {
        return users.size();
    }

    public synchronized void put(String discordId, Tier tier, String note) throws IOException {
        Map<String, Entry> updated = new HashMap<>(users);
        updated.put(discordId, new Entry(discordId, tier, note));
        save(updated);
        users = Map.copyOf(updated);
        loadedMtime = mtime();
    }

    /**
     * Files a first-time visitor at the configured default tier. Does nothing and returns
     * false when the id already has an entry — including one at {@link Tier#NONE}, which is
     * how an admin blocks somebody for good on a server with auto-enrolment on. The check
     * and the write are one atomic step so two simultaneous first logins cannot both enrol.
     */
    public synchronized boolean enroll(String discordId, Tier tier, String note) throws IOException {
        if (tier == Tier.NONE || users.containsKey(discordId)) return false;
        put(discordId, tier, note);
        return true;
    }

    /** True when this id has an entry at all, whatever its tier. */
    public boolean known(String discordId) {
        return users.containsKey(discordId);
    }

    public synchronized boolean remove(String discordId) throws IOException {
        if (!users.containsKey(discordId)) return false;
        Map<String, Entry> updated = new HashMap<>(users);
        updated.remove(discordId);
        save(updated);
        users = Map.copyOf(updated);
        loadedMtime = mtime();
        return true;
    }

    private long mtime() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private void save(Map<String, Entry> toSave) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject usersJson = new JsonObject();
        toSave.values().stream().sorted(Comparator.comparing(Entry::discordId)).forEach(entry -> {
            JsonObject value = new JsonObject();
            value.addProperty("tier", entry.tier().name().toLowerCase());
            value.addProperty("note", entry.note());
            usersJson.add(entry.discordId(), value);
        });
        JsonObject root = new JsonObject();
        root.add("users", usersJson);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
