package net.Dispatcher.web.corridor;

import net.Dispatcher.content.simulator.core.SimDiagram;
import net.Dispatcher.content.simulator.core.SimGraph;
import net.Dispatcher.content.simulator.core.SimPathfinder;
import net.Dispatcher.web.graph.WebGraphStore;
import net.Dispatcher.web.live.HistoryRing;
import net.Dispatcher.web.live.LiveTrainSampler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Corridors for the web diagrams: a geographic A→B route (no traffic penalties) laid out as a
 * {@link SimDiagram} distance axis, cached per graph version. The "actual" side projects
 * {@link HistoryRing} samples through the corridor with the diagram's own segment/simplify
 * semantics — including the foreign-visit line break, keyed off the ring's recorded station
 * arrivals; the "plan" side reuses the same corridor for projection-sim overlays.
 */
public final class CorridorService {

    /** Corridor cache entries kept (LRU) — a handful of active diagrams per server. */
    private static final int CACHE_SIZE = 16;
    /** Rebuild even a version-stable corridor after this long (station tags may change). */
    private static final long MAX_AGE_MS = 600_000;
    private static final int MAX_LINES = 60;
    private static final int MAX_POINTS_PER_SEGMENT = 400;
    /** A parked train must sit within its own length + slack of a station to be scenery. */
    private static final double PARKED_SLACK = 8.0;
    /** Window-edge tolerance for the parked-all-window cut, in ticks. */
    private static final long PARKED_EDGE_TICKS = 600;

    public record Corridor(UUID graphId, int graphVersion, String from, String to,
                           SimDiagram diagram, List<Integer> path, double pathDistance,
                           long builtAtMs) {}

    private final LinkedHashMap<String, Corridor> cache =
            new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Corridor> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    /**
     * The corridor for A→B on this graph entry, cached per graph version.
     * Null when either group is unknown or no route exists. Synchronized —
     * a build is tens of milliseconds and callers are HTTP threads.
     */
    public synchronized Corridor corridor(WebGraphStore.Entry entry, String from, String to,
                                          Map<String, String> stationGroups,
                                          Set<String> hiddenStations) {
        String key = entry.id() + "|" + entry.version() + "|" + from + "|" + to;
        Corridor cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.builtAtMs() < MAX_AGE_MS)
            return cached;

        SimGraph graph = entry.simGraph();
        if (graph == null) return null;
        StationIndex index = StationIndex.of(graph, stationGroups);
        List<StationIndex.Platform> starts = index.platforms(from);
        List<StationIndex.Platform> ends = index.platforms(to);
        if (starts.isEmpty() || ends.isEmpty()) return null;

        List<SimGraph.StationTarget> targets = new ArrayList<>();
        for (StationIndex.Platform platform : ends)
            if (platform.approachable())
                targets.add(new SimGraph.StationTarget(platform.edgeId(), platform.offset(),
                        platform.id(), platform.name()));
        if (targets.isEmpty())
            for (StationIndex.Platform platform : ends)
                targets.add(new SimGraph.StationTarget(platform.edgeId(), platform.offset(),
                        platform.id(), platform.name()));

        // Every platform of the start group, both track directions, in ONE
        // multi-source search — big hub groups (bus stops, depots, dozens of
        // platforms) must not hide the one platform the sane route leaves from.
        List<SimPathfinder.Start> startPoints = new ArrayList<>(starts.size());
        for (StationIndex.Platform platform : starts)
            startPoints.add(new SimPathfinder.Start(platform.edgeId(), platform.offset()));
        SimPathfinder.Path best =
                SimPathfinder.findMulti(graph, startPoints, targets, SimPathfinder.Penalties.NONE);
        if (best == null) return null;

        List<Integer> path = new ArrayList<>(best.edges().length);
        for (int edge : best.edges()) path.add(edge);

        // Round trip: on one-way double track the B→A direction runs on its own
        // edges — without the return leg, half the service never projects. The
        // fold pins the return onto the outbound axis (seen-station pins), the
        // same way the sim diagram folds an out-and-back run.
        List<SimPathfinder.Start> returnStarts = new ArrayList<>(ends.size());
        for (StationIndex.Platform platform : ends)
            returnStarts.add(new SimPathfinder.Start(platform.edgeId(), platform.offset()));
        List<SimGraph.StationTarget> returnTargets = new ArrayList<>();
        for (StationIndex.Platform platform : starts)
            if (platform.approachable())
                returnTargets.add(new SimGraph.StationTarget(platform.edgeId(),
                        platform.offset(), platform.id(), platform.name()));
        if (returnTargets.isEmpty())
            for (StationIndex.Platform platform : starts)
                returnTargets.add(new SimGraph.StationTarget(platform.edgeId(),
                        platform.offset(), platform.id(), platform.name()));
        SimPathfinder.Path back = SimPathfinder.findMulti(graph, returnStarts, returnTargets,
                SimPathfinder.Penalties.NONE);
        if (back != null)
            for (int edge : back.edges()) path.add(edge);

        SimDiagram diagram = SimDiagram.fromPath(graph, path, stationGroups, hiddenStations);
        // Sibling platforms the route didn't use still catch their dwells.
        diagram.pinUnmappedStationEdges();
        Corridor corridor = new Corridor(entry.id(), entry.version(), from, to, diagram, path,
                best.distance(), System.currentTimeMillis());
        cache.put(key, corridor);
        return corridor;
    }

    /**
     * The corridor's actual-movement lines: every history ring pinned to this graph version,
     * projected and segmented like sim lines. {@code sinceTick} windows the response (0 = the
     * full history); parked-scenery trains are cut the same way the sim diagram cuts them.
     */
    public String actualJson(Corridor corridor, HistoryRing history,
                             Map<UUID, LiveTrainSampler.RosterEntry> roster,
                             long sinceTick, long nowTick, double topSpeed) {
        SimDiagram diagram = corridor.diagram();
        StringBuilder json = new StringBuilder(16384);
        json.append("{\"graphId\":\"").append(corridor.graphId())
                .append("\",\"graphVersion\":").append(corridor.graphVersion())
                .append(",\"from\":").append(quote(corridor.from()))
                .append(",\"to\":").append(quote(corridor.to()))
                .append(",\"length\":").append(round1(diagram.corridorLength))
                .append(",\"nowTick\":").append(nowTick)
                .append(",\"sinceTick\":").append(sinceTick)
                .append(",\"path\":[");
        for (int i = 0; i < corridor.path().size(); i++) {
            if (i > 0) json.append(',');
            json.append(corridor.path().get(i));
        }
        json.append("],\"stations\":[");
        boolean first = true;
        for (SimDiagram.StationMark mark : diagram.stations) {
            if (!first) json.append(',');
            first = false;
            json.append('[').append(quote(mark.name())).append(',')
                    .append(round1(mark.pos())).append(']');
        }
        json.append("],\"lines\":[");

        int dropped = 0;
        int lines = 0;
        first = true;
        for (HistoryRing.Snapshot snapshot : history.snapshotFor(corridor.graphId(),
                corridor.graphVersion())) {
            LiveTrainSampler.RosterEntry entry = roster.get(snapshot.trainId());
            double length = entry != null ? entry.length() : 16;

            List<SimDiagram.Point> points = new ArrayList<>();
            long firstTick = Long.MAX_VALUE;
            for (int i = 0; i < snapshot.ticks().length; i++) {
                if (snapshot.ticks()[i] < sinceTick) continue;
                firstTick = Math.min(firstTick, snapshot.ticks()[i]);
                double pos = diagram.project(snapshot.edges()[i], snapshot.offsets()[i]);
                if (!Double.isNaN(pos))
                    points.add(new SimDiagram.Point(snapshot.ticks()[i], pos));
            }
            // The sim diagram's foreign-visit break, from recorded arrivals: a call
            // at a station with no corridor position proves the train served another
            // line — the gap breaks instead of bridging. Corridor-station arrivals
            // (any platform, any track) have positions and keep the line whole.
            List<Long> foreignVisits = new ArrayList<>();
            for (int v = 0; v < snapshot.visitTicks().length; v++)
                if (snapshot.visitTicks()[v] >= sinceTick
                        && !diagram.stationPositions.containsKey(snapshot.visitStations()[v]))
                    foreignVisits.add(snapshot.visitTicks()[v]);
            List<List<SimDiagram.Point>> segments =
                    SimDiagram.segment(points, topSpeed, length, foreignVisits);
            if (segments.isEmpty()) continue;
            if (parkedAllWindow(segments, length, diagram, firstTick, nowTick)) continue;
            for (int s = 0; s < segments.size(); s++)
                segments.set(s, SimDiagram.capPoints(
                        SimDiagram.simplify(segments.get(s)), MAX_POINTS_PER_SEGMENT));
            segments.removeIf(segment -> segment.size() < 2);
            if (segments.isEmpty()) continue;
            if (++lines > MAX_LINES) {
                dropped++;
                continue;
            }

            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":\"").append(snapshot.trainId())
                    .append("\",\"name\":").append(quote(entry != null ? entry.name() : "?"))
                    .append(",\"segs\":[");
            for (int s = 0; s < segments.size(); s++) {
                if (s > 0) json.append(',');
                json.append('[');
                List<SimDiagram.Point> segment = segments.get(s);
                for (int p = 0; p < segment.size(); p++) {
                    if (p > 0) json.append(',');
                    json.append('[').append(segment.get(p).tick()).append(',')
                            .append(round1(segment.get(p).pos())).append(']');
                }
                json.append(']');
            }
            json.append("]}");
        }
        json.append("],\"dropped\":").append(dropped).append('}');
        return json.toString();
    }

    /**
     * The live twin of the sim diagram's idle-at-station cut: one unbroken
     * near-flat segment beside a station, spanning (almost) the whole
     * requested window, is parked scenery.
     */
    private static boolean parkedAllWindow(List<List<SimDiagram.Point>> segments, double length,
                                           SimDiagram diagram, long firstTick, long nowTick) {
        if (segments.size() != 1) return false;
        List<SimDiagram.Point> points = segments.get(0);
        if (points.size() < 2) return false;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (SimDiagram.Point point : points) {
            min = Math.min(min, point.pos());
            max = Math.max(max, point.pos());
        }
        double reach = length + PARKED_SLACK;
        if (max - min > reach) return false;
        if (points.get(0).tick() > firstTick + PARKED_EDGE_TICKS
                || points.get(points.size() - 1).tick() < nowTick - PARKED_EDGE_TICKS)
            return false;
        double center = (min + max) / 2;
        for (double stationPos : diagram.stationPositions.values())
            if (Math.abs(stationPos - center) <= reach)
                return true;
        return false;
    }

    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    public static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
