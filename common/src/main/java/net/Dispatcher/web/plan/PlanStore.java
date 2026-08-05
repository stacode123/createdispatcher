package net.Dispatcher.web.plan;

import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
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
 * The planned-timetable library: one JSON file per plan under
 * {@code <world>/realism/plans/}. Unlike presets, nothing here touches Create or the
 * level — a plan is pure browser-side intent — so mutations run wherever the request
 * lands (guarded by this object's monitor) and only disk writes hop to the IO thread.
 * Reads are safe from any thread: the map is concurrent and {@link Plan}s immutable.
 */
public final class PlanStore {

    /** Mutation failure with a stable key, surfaced as the web error code. */
    public static final class PlanException extends Exception {
        public final String key;

        public PlanException(String key, String detail) {
            super(detail);
            this.key = key;
        }
    }

    private static volatile PlanStore instance;

    /** The store for this server, created (and loaded from disk) on first use. */
    public static synchronized PlanStore of(MinecraftServer server) {
        PlanStore current = instance;
        if (current != null && current.server == server)
            return current;
        if (current != null)
            current.io.shutdown();
        current = new PlanStore(server);
        instance = current;
        return current;
    }

    private final MinecraftServer server;
    private final Path directory;
    private final Map<UUID, Plan> plans = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dispatcher-Web-IO");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Runnable changeListener;

    private PlanStore(MinecraftServer server) {
        this.server = server;
        this.directory = server.getWorldPath(LevelResource.ROOT).resolve("createdispatcher").resolve("plans");
        load();
    }

    private void load() {
        if (!Files.isDirectory(directory))
            return;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    Plan plan = PlanJson.fromJson(Files.readString(path, StandardCharsets.UTF_8));
                    if (plan.id() != null)
                        plans.put(plan.id(), plan);
                } catch (Exception e) {
                    DispatcherMod.LOGGER.error("Skipping unreadable plan file {}", path, e);
                }
            });
        } catch (IOException e) {
            DispatcherMod.LOGGER.error("Could not list plan directory {}", directory, e);
        }
        if (!plans.isEmpty())
            DispatcherMod.LOGGER.info("Loaded {} planned timetable(s)", plans.size());
    }

    /** Fired after every successful mutation. */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    public Plan get(UUID id) {
        return id == null ? null : plans.get(id);
    }

    /** Name-sorted snapshot. */
    public List<Plan> list() {
        List<Plan> list = new ArrayList<>(plans.values());
        list.sort(Comparator.comparing(Plan::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Plan::id));
        return list;
    }

    /** Saves under a fresh id. */
    public synchronized Plan create(Plan body, String author) throws PlanException {
        if (plans.size() >= DispatcherConfig.COMMON.WebPlanMaxCount.get())
            throw new PlanException("plan_full", "library holds the configured maximum");
        validate(body);
        long now = System.currentTimeMillis();
        Plan plan = PlanJson.withIdentity(body, UUID.randomUUID(), author, now, now);
        plans.put(plan.id(), plan);
        persist(plan);
        fireChanged();
        return plan;
    }

    /** Overwrites an existing plan's content, keeping its id, author and creation time. */
    public synchronized Plan update(UUID id, Plan body) throws PlanException {
        Plan previous = require(id);
        validate(body);
        Plan plan = PlanJson.withIdentity(body, id, previous.author(), previous.createdMs(),
                System.currentTimeMillis());
        plans.put(id, plan);
        persist(plan);
        fireChanged();
        return plan;
    }

    public synchronized void delete(UUID id) throws PlanException {
        require(id);
        plans.remove(id);
        io.execute(() -> {
            try {
                Files.deleteIfExists(directory.resolve(id + ".json"));
            } catch (IOException e) {
                DispatcherMod.LOGGER.error("Could not delete plan file {}", id, e);
            }
        });
        fireChanged();
    }

    private Plan require(UUID id) throws PlanException {
        Plan plan = get(id);
        if (plan == null)
            throw new PlanException("not_found", "no plan with that id");
        return plan;
    }

    /** Size gates only — a plan referencing a since-deleted train or preset stays loadable. */
    private static void validate(Plan plan) throws PlanException {
        String name = plan.name() == null ? "" : plan.name().trim();
        if (name.isEmpty() || name.length() > 60)
            throw new PlanException("bad_name", "names must be 1-60 characters");
        if (plan.assignments().size() > 2000 || plan.keeps().size() > 2000
                || plan.removals().size() > 2000)
            throw new PlanException("plan_too_large", "too many train rows in one plan");
    }

    private void persist(Plan plan) {
        String json = PlanJson.toJson(plan).toString();
        io.execute(() -> {
            try {
                Files.createDirectories(directory);
                Path file = directory.resolve(plan.id() + ".json");
                Path tmp = directory.resolve(plan.id() + ".json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                DispatcherMod.LOGGER.error("Could not persist plan {}", plan.id(), e);
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
            DispatcherMod.LOGGER.error("Plan change listener failed", t);
        }
    }
}
