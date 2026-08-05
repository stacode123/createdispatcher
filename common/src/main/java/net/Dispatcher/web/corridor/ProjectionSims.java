package net.Dispatcher.web.corridor;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.content.simulator.HeadlessSimService;
import net.Dispatcher.content.simulator.core.SimDiagram;
import net.Dispatcher.content.simulator.core.SimResult;
import net.Dispatcher.content.simulator.core.SimTrainSpec;
import net.Dispatcher.web.graph.WebGraphStore;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Cached no-override projection runs per graph, feeding the live diagrams' plan overlay:
 * "where the current trains and schedules are headed from here". Recomputed when older than
 * {@code Web Projection Stale Seconds} or when the graph version moves. prepare runs on the
 * server thread, the engine on the caller-supplied sim executor.
 */
public final class ProjectionSims {

    /** Game-hours of look-ahead for the plan overlay (capped by the sim horizon config). */
    private static final int PROJECTION_HORIZON_HOURS = 12;
    private static final int SAMPLE_STRIDE = 100;
    private static final int MAX_LINES = 60;
    private static final int MAX_POINTS_PER_SEGMENT = 300;

    public record Projection(UUID graphId, int graphVersion, long startGameTick,
                             long startDayTime, double dayTimeRate, SimResult result,
                             List<SimTrainSpec> specs,
                             net.Dispatcher.content.simulator.core.SimGraph graph,
                             long builtAtMs) {}

    private record PlanJson(long projectionBuiltAtMs, String corridorKey, String json) {}

    private final Map<UUID, Projection> projections = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlanJson> planCache = new ConcurrentHashMap<>();

    /** The cached projection for this graph version, however old — null when absent. */
    public Projection get(UUID graphId, int graphVersion) {
        Projection projection = projections.get(graphId);
        return projection != null && projection.graphVersion() == graphVersion ? projection : null;
    }

    public boolean isStale(Projection projection) {
        return System.currentTimeMillis() - projection.builtAtMs()
                > DispatcherConfig.COMMON.WebProjectionStaleSeconds.get() * 1000L;
    }

    /** Schedule a (re)compute unless one is already running. Any thread. */
    public void requestCompute(MinecraftServer server, WebGraphStore store, UUID graphId,
                               ExecutorService simExecutor,
                               net.Dispatcher.web.live.LiveTrainSampler sampler,
                               net.Dispatcher.web.live.HistoryRing history,
                               PlanCalibration calibration) {
        if (!inFlight.add(graphId)) return;
        server.execute(() -> {
            try {
                WebGraphStore.Entry entry = store.get(graphId);
                if (entry == null || entry.tooLarge() || entry.simGraph() == null) {
                    inFlight.remove(graphId);
                    return;
                }
                TrackGraph trackGraph =
                        Create.RAILWAYS.sided(server.overworld()).trackNetworks.get(graphId);
                if (trackGraph == null) {
                    inFlight.remove(graphId);
                    return;
                }
                int horizonHours = Math.min(PROJECTION_HORIZON_HOURS,
                        DispatcherConfig.COMMON.WebSimMaxHorizonHours.get());
                HeadlessSimService.Prepared prepared = HeadlessSimService.prepare(
                        server.overworld(), trackGraph, entry.topology(), entry.simGraph(),
                        horizonHours, null, sampler.speedFloors());
                int version = entry.version();
                long wallCap = DispatcherConfig.COMMON.WebSimWallCapSeconds.get() * 1000L;
                long waitConflictTicks = DispatcherConfig.COMMON.SimWaitConflictSeconds.get() * 20L;
                long headwayTicks = DispatcherConfig.COMMON.SimHeadwaySeconds.get() * 20L;
                simExecutor.execute(() -> {
                    try {
                        SimResult result = HeadlessSimService.run(prepared, SAMPLE_STRIDE,
                                wallCap, waitConflictTicks, headwayTicks);
                        Projection previous = projections.put(graphId, new Projection(graphId,
                                version, prepared.startGameTick(), prepared.clock().startDayTime(),
                                prepared.clock().dayTimeRate(), result,
                                prepared.specs(), prepared.graph(),
                                System.currentTimeMillis()));
                        planCache.remove(graphId);
                        // The outgoing projection has now been answered by
                        // reality — score it to keep the drift calibration
                        // current (snapshots are immutable, any thread).
                        if (previous != null && previous.graphVersion() == version
                                && calibration != null && history != null)
                            calibration.score(previous,
                                    history.snapshotFor(graphId, version),
                                    prepared.startGameTick());
                    } catch (Throwable t) {
                        DispatcherMod.LOGGER.error("Dispatcher web: projection sim failed for {}",
                                graphId, t);
                    } finally {
                        inFlight.remove(graphId);
                    }
                });
            } catch (Throwable t) {
                inFlight.remove(graphId);
                DispatcherMod.LOGGER.error("Dispatcher web: projection prepare failed for {}",
                        graphId, t);
            }
        });
    }

    /**
     * The plan overlay for one corridor: the projection's trains laid onto a fresh copy of the
     * corridor's axis (the shared corridor diagram is never mutated). Cached until the
     * projection or corridor changes. Ticks in {@code segs} are sim-relative; the client
     * shifts them by {@code baseTick}.
     */
    public String planJson(CorridorService.Corridor corridor, Projection projection,
                           Map<String, String> stationGroups, Set<String> hiddenStations,
                           PlanCalibration calibration) {
        String corridorKey = corridor.graphVersion() + "|" + corridor.from() + "|" + corridor.to();
        PlanJson cached = planCache.get(corridor.graphId());
        if (cached != null && cached.projectionBuiltAtMs() == projection.builtAtMs()
                && cached.corridorKey().equals(corridorKey))
            return cached.json();

        SimDiagram diagram = SimDiagram.fromPath(projection.graph(), corridor.path(),
                stationGroups, hiddenStations);
        diagram.pinUnmappedStationEdges();
        diagram.populateLines(projection.result(), projection.specs(), -1, MAX_LINES,
                MAX_POINTS_PER_SEGMENT, Set.of());

        StringBuilder json = new StringBuilder(8192);
        json.append("{\"graphId\":\"").append(corridor.graphId())
                .append("\",\"graphVersion\":").append(corridor.graphVersion())
                .append(",\"from\":").append(CorridorService.quote(corridor.from()))
                .append(",\"to\":").append(CorridorService.quote(corridor.to()))
                .append(",\"baseTick\":").append(projection.startGameTick())
                .append(",\"startDayTime\":").append(projection.startDayTime())
                .append(",\"dayTimeRate\":").append(projection.dayTimeRate())
                .append(",\"ticksSimulated\":").append(projection.result().ticksSimulated)
                .append(",\"truncated\":").append(projection.result().truncated)
                .append(",\"ageSeconds\":")
                .append((System.currentTimeMillis() - projection.builtAtMs()) / 1000)
                .append(",\"stale\":").append(isStale(projection))
                .append(",\"cal\":");
        PlanCalibration.Cal graphCal =
                calibration == null ? null : calibration.get(corridor.graphId());
        appendCal(json, graphCal);
        json.append(",\"lines\":[");
        boolean first = true;
        for (SimDiagram.Line line : diagram.lines) {
            SimTrainSpec spec = projection.specs().get(line.train());
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(CorridorService.quote(spec.id))
                    .append(",\"name\":").append(CorridorService.quote(spec.name))
                    // this train's own drift estimate; clients fall back to the graph's
                    .append(",\"cal\":");
            appendCal(json, calibration == null ? null : calibration.forTrain(spec.id));
            json
                    // captured physics, for debugging plan-vs-actual mismatches
                    .append(",\"acc\":").append(Math.round(spec.acceleration * 1e6) / 1e6)
                    .append(",\"top\":").append(Math.round(spec.topSpeed * 1000) / 1000.0)
                    .append(",\"thr\":").append(Math.round(spec.initialThrottle * 100) / 100.0)
                    .append(",\"plim\":").append(Math.round(spec.primaryLimit * 100) / 100.0)
                    .append(",\"sperm\":").append(spec.initialStoredPermanent == null ? "null"
                            : Math.round(spec.initialStoredPermanent * 100) / 100.0)
                    .append(",\"segs\":[");
            for (int s = 0; s < line.segments().size(); s++) {
                if (s > 0) json.append(',');
                json.append('[');
                List<SimDiagram.Point> segment = line.segments().get(s);
                for (int p = 0; p < segment.size(); p++) {
                    if (p > 0) json.append(',');
                    json.append('[').append(segment.get(p).tick()).append(',')
                            .append(CorridorService.round1(segment.get(p).pos())).append(']');
                }
                json.append(']');
            }
            json.append("]}");
        }
        json.append("],\"dropped\":").append(diagram.linesDropped).append('}');
        String built = json.toString();
        planCache.put(corridor.graphId(),
                new PlanJson(projection.builtAtMs(), corridorKey, built));
        return built;
    }

    private static void appendCal(StringBuilder json, PlanCalibration.Cal cal) {
        if (cal == null) {
            json.append("null");
            return;
        }
        json.append("{\"stretch\":").append(cal.stretch())
                .append(",\"sigmaPerMin\":").append(cal.sigmaPerMinuteTicks())
                .append(",\"n\":").append(cal.samples()).append('}');
    }
}
