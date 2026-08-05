package net.Dispatcher.web.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.mixin.mixinaccesors.NavigationAccessor;
import net.Dispatcher.content.simulator.SimTopology;
import net.createmod.catnip.data.Couple;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.content.simulator.core.SimGraph;
import net.Dispatcher.content.simulator.core.SimPathfinder;
import net.Dispatcher.web.graph.WebGraphStore;
import net.Dispatcher.web.live.LiveTrainSampler;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Flags trains whose remaining route is far longer than the shortest path to their destination
 * (misrouted via one-way loops, closed junctions...). Candidates are captured on the server
 * thread from the sampler frame; pathfinding runs on the analyzer thread over the store's
 * immutable SimGraph — at most one batch in flight, ≤ 50 searches per cycle, ≥ 30 s per train.
 */
public final class DetourAnalyzer {
    private static final long PER_TRAIN_INTERVAL_MS = 30_000;
    private static final int MAX_BATCH = 50;

    private record Candidate(UUID trainId, String name, UUID graphId, int graphVersion,
                             int edge, double off, UUID destId, String destName,
                             double actual, double x, double z, String dim, long tick,
                             int[] routedEdges) {}

    /** One occupant of an edge at capture time — cause attribution intersects these with the ignored shortest path. */
    private record Occupant(UUID id, String name, double speed) {}

    private record Job(List<Candidate> candidates, Map<UUID, Map<Integer, List<Occupant>>> occupancy) {}

    private final ConcurrentHashMap<UUID, Long> lastCheckMs = new ConcurrentHashMap<>();
    private volatile boolean jobRunning;

    /** SERVER THREAD, after each sampler frame. */
    public void capture(MinecraftServer server, WebGraphStore store, NotificationHub hub,
                        LiveTrainSampler.Frame frame, ExecutorService analyzer, long gameTick) {
        DispatcherConfig.Common config = DispatcherConfig.COMMON;
        int minBlocks = config.WebDetourMinBlocks.get();
        long now = System.currentTimeMillis();

        Map<UUID, LiveTrainSampler.TrainPos> positions = new HashMap<>();
        for (LiveTrainSampler.TrainPos pos : frame.positions) positions.put(pos.id(), pos);

        Map<UUID, String> names = new HashMap<>();
        for (Train train : Create.RAILWAYS.sided(server.overworld()).trains.values())
            names.put(train.id, train.name.getString());
        Map<UUID, Map<Integer, List<Occupant>>> occupancy = new HashMap<>();
        for (LiveTrainSampler.TrainPos pos : frame.positions)
            occupancy.computeIfAbsent(pos.graphId(), k -> new HashMap<>())
                    .computeIfAbsent(pos.edgeId(), k -> new ArrayList<>())
                    .add(new Occupant(pos.id(), names.getOrDefault(pos.id(), "?"), pos.speed()));

        List<Candidate> batch = new ArrayList<>();
        for (Train train : Create.RAILWAYS.sided(server.overworld()).trains.values()) {
            String id = "detour:" + train.id;
            if (train.graph == null || train.navigation == null || train.navigation.destination == null
                    || train.navigation.distanceToDestination < minBlocks) {
                if (hub.isActive(id)) hub.resolve(id, gameTick);
                continue;
            }
            if (jobRunning || batch.size() >= MAX_BATCH) continue;
            if (now - lastCheckMs.getOrDefault(train.id, 0L) < PER_TRAIN_INTERVAL_MS) continue;
            LiveTrainSampler.TrainPos pos = positions.get(train.id);
            if (pos == null) continue;
            WebGraphStore.Entry entry = store.get(train.graph.id);
            if (entry == null || entry.tooLarge()) continue;
            MonitorPositions.Pos world = MonitorPositions.locate(store, train);
            if (world == null) continue;
            lastCheckMs.put(train.id, now);
            batch.add(new Candidate(train.id, train.name.getString(), train.graph.id, entry.version(),
                    pos.edgeId(), pos.offset(), train.navigation.destination.id,
                    train.navigation.destination.name, train.navigation.distanceToDestination,
                    world.x(), world.z(), world.dim(), gameTick,
                    collapseRoute(((NavigationAccessor) train.navigation).getCurrentPath(), entry.topology())));
        }
        if (!batch.isEmpty()) {
            jobRunning = true;
            Job job = new Job(batch, occupancy);
            analyzer.execute(() -> {
                try {
                    run(job, store, hub);
                } catch (Throwable t) {
                    DispatcherMod.LOGGER.error("Dispatcher web: detour analysis failed", t);
                } finally {
                    jobRunning = false;
                }
            });
        }
    }

    /** ANALYZER THREAD. */
    private void run(Job job, WebGraphStore store, NotificationHub hub) {
        double ratioThreshold = DispatcherConfig.COMMON.WebDetourRatio.get();
        for (Candidate candidate : job.candidates()) {
            WebGraphStore.Entry entry = store.get(candidate.graphId());
            if (entry == null || entry.tooLarge() || entry.version() != candidate.graphVersion()) continue;
            SimGraph.StationTarget target = entry.simGraph().findStation(candidate.destId());
            String id = "detour:" + candidate.trainId();
            if (target == null) continue;
            SimPathfinder.Path shortest = SimPathfinder.find(entry.simGraph(), candidate.edge(),
                    candidate.off(), List.of(target), false, -1, 0, SimPathfinder.Penalties.NONE);
            if (shortest == null) continue;
            double ratio = candidate.actual() / Math.max(1, shortest.distance());
            if (ratio > ratioThreshold) {
                JsonObject data = new JsonObject();
                data.addProperty("actualBlocks", Math.round(candidate.actual()));
                data.addProperty("shortestBlocks", Math.round(shortest.distance()));
                data.addProperty("ratio", Math.round(ratio * 100) / 100.0);
                // both routes as collapsed-graph edge lists so the map (and replays) can draw
                // "the way it went" vs "the direct way" — ids valid only for routesGraphVersion
                data.addProperty("routesGraphVersion", candidate.graphVersion());
                JsonArray routed = new JsonArray();
                for (int edge : candidate.routedEdges()) routed.add(edge);
                data.add("routedEdges", routed);
                JsonArray direct = new JsonArray();
                for (int edge : shortest.edges()) direct.add(edge);
                data.add("directEdges", direct);
                String suffix = attributeCause(job, candidate, entry, shortest, hub, data);
                hub.raiseOrUpdate(new WebNotification(id, WebNotification.Kind.DETOUR,
                        WebNotification.Severity.WARN,
                        candidate.name() + " is detouring to " + candidate.destName() + ": "
                                + Math.round(candidate.actual()) + " b routed vs "
                                + Math.round(shortest.distance()) + " b direct ("
                                + Math.round(ratio * 10) / 10.0 + "×)" + suffix,
                        List.of(new WebNotification.TrainRef(candidate.trainId(), candidate.name())),
                        candidate.graphId(), candidate.x(), candidate.z(), candidate.dim(),
                        candidate.tick(), candidate.tick(), null, data));
            } else if (hub.isActive(id)) {
                hub.resolve(id, candidate.tick());
            }
        }
    }

    /**
     * Why is the direct route being avoided? Intersects the ignored shortest path (and its twin
     * edges — a blocker on single track blocks both directions) with the frame's occupants:
     * stationary trains on the corridor → "blocked by X"; several slow ones → "congested".
     * Router hysteresis and pure reservation blockages stay unattributed by design.
     */
    private static String attributeCause(Job job, Candidate candidate, WebGraphStore.Entry entry,
                                         SimPathfinder.Path shortest, NotificationHub hub, JsonObject data) {
        Map<Integer, List<Occupant>> occupancy = job.occupancy().get(candidate.graphId());
        if (occupancy == null) return "";
        Set<Integer> corridor = new HashSet<>();
        int[] edges = shortest.edges();
        for (int i = 1; i < edges.length; i++) {
            corridor.add(edges[i]);
            int opposite = entry.simGraph().edge(edges[i]).oppositeId;
            if (opposite >= 0) corridor.add(opposite);
        }
        List<String> blockers = new ArrayList<>();
        String related = null;
        int slowMoving = 0;
        for (int edge : corridor) {
            for (Occupant occupant : occupancy.getOrDefault(edge, List.of())) {
                if (occupant.id().equals(candidate.trainId())) continue;
                if (Math.abs(occupant.speed()) < 0.05) {
                    if (blockers.size() < 3 && !blockers.contains(occupant.name()))
                        blockers.add(occupant.name());
                    if (related == null) related = findRelated(hub, occupant.id());
                } else if (Math.abs(occupant.speed()) < 0.3) {
                    slowMoving++;
                }
            }
        }
        if (!blockers.isEmpty()) {
            data.addProperty("cause", "blocked_by");
            com.google.gson.JsonArray names = new com.google.gson.JsonArray();
            for (String name : blockers) names.add(name);
            data.add("blockers", names);
            if (related != null) data.addProperty("relatedNotification", related);
            return " — direct route blocked by " + String.join(", ", blockers);
        }
        if (slowMoving >= 2) {
            data.addProperty("cause", "congested");
            return " — direct route congested";
        }
        return "";
    }

    /** Create's chosen micro-edge path → collapsed-graph edge ids (deduped, capped). Server thread. */
    private static int[] collapseRoute(List<Couple<TrackNode>> path, SimTopology topology) {
        if (path == null || path.isEmpty()) return new int[0];
        List<Integer> edges = new ArrayList<>();
        for (Couple<TrackNode> couple : path) {
            if (couple == null || couple.getFirst() == null || couple.getSecond() == null) continue;
            SimTopology.Location location =
                    topology.locate(couple.getFirst().getNetId(), couple.getSecond().getNetId(), 0);
            if (location == null) continue;
            if (edges.isEmpty() || edges.get(edges.size() - 1) != location.edgeId())
                edges.add(location.edgeId());
            if (edges.size() >= 500) break;
        }
        int[] result = new int[edges.size()];
        for (int i = 0; i < result.length; i++) result[i] = edges.get(i);
        return result;
    }

    private static String findRelated(NotificationHub hub, UUID trainId) {
        String waitId = "wait:" + trainId;
        if (hub.isActive(waitId)) return waitId;
        for (WebNotification notification : hub.active())
            if (notification.kind() == WebNotification.Kind.DEADLOCK)
                for (WebNotification.TrainRef ref : notification.trains())
                    if (ref.id().equals(trainId)) return notification.id();
        return null;
    }
}
