package net.Dispatcher.web.live;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.content.simulator.ScheduleCompiler;
import net.Dispatcher.content.simulator.SimTopology;
import net.Dispatcher.web.graph.WebGraphStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Samples live train positions and roster metadata on the server thread every
 * {@code Web Live Sample Ticks}. Positions map through {@link SimTopology#locate} onto the
 * store's current graph version — trains whose network has no (or a stale) entry simply carry
 * no position this frame. Published frames are immutable snapshots.
 */
public final class LiveTrainSampler {
    public record TrainPos(UUID id, UUID graphId, int edgeId, double offset, double speed, int dim) {}

    /** A train standing at a station this frame — rolled-through waypoints never qualify. */
    public record StationStop(UUID trainId, UUID stationId) {}

    /**
     * {@code owner} is the resolved player name, "" when absent or unresolvable.
     * {@code line} and {@code category} are the CRN travel section the train is
     * currently inside, "" without CRN or outside a section.
     */
    public record RosterEntry(UUID id, String name, UUID graphId, double length, int carriages,
                              boolean doubleEnded, String state, String scheduleTitle,
                              String destination, String currentStation, String owner,
                              String line, String category) {}

    public static final class Frame {
        public final long gameTick;
        public final long dayTime;
        public final double dayTimeRate;
        public final long wallMs;
        public final Map<String, Integer> graphVersions;
        public final List<TrainPos> positions;
        public final List<StationStop> stops;

        Frame(long gameTick, long dayTime, double dayTimeRate, long wallMs,
              Map<String, Integer> graphVersions, List<TrainPos> positions,
              List<StationStop> stops) {
            this.gameTick = gameTick;
            this.dayTime = dayTime;
            this.dayTimeRate = dayTimeRate;
            this.wallMs = wallMs;
            this.graphVersions = graphVersions;
            this.positions = positions;
            this.stops = stops;
        }
    }

    private volatile Frame latest = new Frame(0, 0, 0, 0, Map.of(), List.of(), List.of());
    private volatile List<RosterEntry> roster = List.of();
    private volatile int rosterVersion;
    private int lastRosterHash;
    private int tickCounter;
    /** Owner UUID → player name; server-thread only, so a plain HashMap is enough. */
    private final Map<UUID, String> ownerNames = new HashMap<>();
    /**
     * Decaying per-train max |speed| observed (blocks/tick) — evidence for the
     * snapshotter that a train is powered even when its fuel counter reads 0
     * at snapshot time. Half-life ≈ 23 min at the 1 s sample cadence.
     */
    private final java.util.Map<UUID, Double> speedFloors = new java.util.concurrent.ConcurrentHashMap<>();

    /** Snapshot of the observed speed floors, for projection prepares. */
    public Map<UUID, Double> speedFloors() {
        return Map.copyOf(speedFloors);
    }

    public Frame latest() {
        return latest;
    }

    public List<RosterEntry> roster() {
        return roster;
    }

    public int rosterVersion() {
        return rosterVersion;
    }

    /**
     * SERVER THREAD, every tick; internally gated to the configured cadence.
     * @return the fresh frame when a sample was taken this tick, else null.
     */
    public Frame sample(MinecraftServer server, WebGraphStore store) {
        if (++tickCounter < DispatcherConfig.COMMON.WebLiveSampleTicks.get()) return null;
        tickCounter = 0;

        List<TrainPos> positions = new ArrayList<>();
        List<StationStop> stops = new ArrayList<>();
        List<RosterEntry> entries = new ArrayList<>();
        int hash = 1;

        // Resolved once per sample, not per train: both are small map builds
        // over CRN's settings, and this runs on the server thread.
        Map<String, String> lineNames = net.Dispatcher.compat.CrnCompat.trainLineNames();
        Map<String, String> categoryNames = net.Dispatcher.compat.CrnCompat.trainCategoryNames();

        java.util.Set<UUID> present = new java.util.HashSet<>();
        for (Train train : Create.RAILWAYS.sided(server.overworld()).trains.values()) {
            if (train.graph == null || train.carriages.isEmpty()) continue;
            present.add(train.id);
            double observed = Math.abs(train.speed);
            speedFloors.merge(train.id, observed,
                    (previous, current) -> Math.max(previous * 0.9995, current));
            WebGraphStore.Entry entry = store.get(train.graph.id);

            if (entry != null && !entry.tooLarge()) {
                TravellingPoint head = train.carriages.get(0).getLeadingPoint();
                if (head != null && head.node1 != null && head.node2 != null) {
                    SimTopology.Location location =
                            entry.topology().locate(head.node1.getNetId(), head.node2.getNetId(), head.position);
                    if (location != null) {
                        int dim = entry.graph().nodes.get(entry.graph().edges.get(location.edgeId()).from).dimension;
                        positions.add(new TrainPos(train.id, train.graph.id, location.edgeId(),
                                location.offset(), train.speed, dim));
                    }
                }
            }

            // A stop needs a standstill — SnR waypoints roll through at speed and
            // must read as routing, not service (mirrors the sim diagram's
            // waypoint exemption for foreign-visit line breaks).
            GlobalStation currentStation = train.getCurrentStation();
            if (currentStation != null && Math.abs(train.speed) < 0.05)
                stops.add(new StationStop(train.id, currentStation.id));

            RosterEntry rosterEntry = describe(train, currentStation, ownerName(server, train.owner),
                    lineNames, categoryNames);
            entries.add(rosterEntry);
            hash = 31 * hash + rosterEntry.hashCode();
        }

        speedFloors.keySet().retainAll(present);
        entries.sort(java.util.Comparator.comparing(e -> e.id().toString()));
        if (hash != lastRosterHash) {
            lastRosterHash = hash;
            roster = List.copyOf(entries);
            rosterVersion++;
        }

        Frame frame = new Frame(
                server.overworld().getGameTime(),
                server.overworld().getDayTime(),
                net.Dispatcher.content.simulator.DayTimeClocks.currentRate(server.overworld()),
                System.currentTimeMillis(),
                store.versionMap(),
                List.copyOf(positions),
                List.copyOf(stops));
        latest = frame;
        return frame;
    }

    /**
     * The owner's player name, "" when the train has no owner or the name is not
     * known here. Cache-only by design: {@code GameProfileCache.get(UUID)} is a map
     * lookup, never a session-server call, so this stays safe on the server thread.
     * Successful resolutions are remembered for the server's lifetime — names change
     * rarely, and a stable string keeps the roster hash from churning. Failures are
     * not cached, so an owner who has never joined resolves as soon as they do.
     */
    private String ownerName(MinecraftServer server, UUID owner) {
        if (owner == null) return "";
        String cached = ownerNames.get(owner);
        if (cached != null) return cached;
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        GameProfileCache cache = server.getProfileCache();
        String name = online != null ? online.getGameProfile().getName()
                : cache == null ? ""
                : cache.get(owner).map(GameProfile::getName).orElse("");
        if (!name.isEmpty()) ownerNames.put(owner, name);
        return name;
    }

    private static RosterEntry describe(Train train, GlobalStation current, String owner,
                                        Map<String, String> lineNames,
                                        Map<String, String> categoryNames) {
        double length = 2;
        for (var carriage : train.carriages) length += carriage.bogeySpacing;
        for (int spacing : train.carriageSpacing) length += spacing;

        String state;
        if (train.derailed) state = "DERAILED";
        else if (train.runtime.getSchedule() == null) state = "MANUAL";
        else if (train.runtime.completed) state = "COMPLETED";
        else if (train.runtime.paused) state = "PAUSED";
        else if (train.navigation != null && train.navigation.destination != null) state = "RUNNING";
        else state = "IDLE";

        GlobalStation destination = train.navigation != null ? train.navigation.destination : null;
        String title = train.runtime.getSchedule() != null && train.runtime.currentTitle != null
                ? train.runtime.currentTitle : "";

        net.minecraft.nbt.CompoundTag section = ScheduleCompiler.travelSectionAt(
                train.runtime.getSchedule(), train.runtime.currentEntry);

        return new RosterEntry(train.id, train.name.getString(), train.graph.id, Math.round(length * 10) / 10.0,
                train.carriages.size(), train.doubleEnded, state, title,
                destination != null ? destination.name : "", current != null ? current.name : "",
                owner, ScheduleCompiler.lineName(section, lineNames),
                ScheduleCompiler.categoryName(section, categoryNames));
    }
}
