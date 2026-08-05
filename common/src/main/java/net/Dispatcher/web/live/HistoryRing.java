package net.Dispatcher.web.live;

import net.Dispatcher.config.DispatcherConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Long-window train position history for the actual-movement diagrams: one packed ring per
 * train at {@code Web History Sample Seconds} cadence covering {@code Web History Hours}.
 * Samples are (gameTick, edgeId, offset) in the coordinates of one pinned graph version — a
 * train whose graph rebuilds starts a fresh ring (edge ids are per-version transients).
 * Written only from the server thread; readers copy under the per-ring lock.
 */
public final class HistoryRing {

    /**
     * An immutable, tick-ordered copy of one train's ring. {@code visitTicks}/{@code
     * visitStations} are the train's station arrivals in the window (parallel arrays,
     * ascending ticks) — the corridor diagrams use them to break lines at foreign-station
     * detours the same way the sim diagram does.
     */
    public record Snapshot(UUID trainId, UUID graphId, int graphVersion,
                           long[] ticks, int[] edges, float[] offsets,
                           long[] visitTicks, UUID[] visitStations) {}

    private record Visit(long tick, UUID stationId) {}

    private static final class Ring {
        long[] ticks;
        int[] edges;
        float[] offsets;
        int next;
        int size;
        UUID graphId;
        int graphVersion;
        long lastSeenTick;
        /** Station arrivals in the window, ascending — arrivals are rarer than samples. */
        final java.util.ArrayDeque<Visit> visits = new java.util.ArrayDeque<>();

        Ring(int capacity) {
            ticks = new long[capacity];
            edges = new int[capacity];
            offsets = new float[capacity];
        }

        synchronized void reset(UUID graph, int version) {
            next = 0;
            size = 0;
            graphId = graph;
            graphVersion = version;
            visits.clear();
        }

        synchronized void addVisit(long tick, UUID stationId, long windowTicks) {
            while (!visits.isEmpty() && visits.peekFirst().tick() < tick - windowTicks)
                visits.pollFirst();
            visits.addLast(new Visit(tick, stationId));
        }

        synchronized void add(long tick, int edge, float offset) {
            ticks[next] = tick;
            edges[next] = edge;
            offsets[next] = offset;
            next = (next + 1) % ticks.length;
            if (size < ticks.length) size++;
        }

        synchronized Snapshot copy(UUID trainId) {
            long[] outTicks = new long[size];
            int[] outEdges = new int[size];
            float[] outOffsets = new float[size];
            int start = (next - size + ticks.length) % ticks.length;
            for (int i = 0; i < size; i++) {
                int at = (start + i) % ticks.length;
                outTicks[i] = ticks[at];
                outEdges[i] = edges[at];
                outOffsets[i] = offsets[at];
            }
            long[] outVisitTicks = new long[visits.size()];
            UUID[] outVisitStations = new UUID[visits.size()];
            int v = 0;
            for (Visit visit : visits) {
                outVisitTicks[v] = visit.tick();
                outVisitStations[v++] = visit.stationId();
            }
            return new Snapshot(trainId, graphId, graphVersion, outTicks, outEdges, outOffsets,
                    outVisitTicks, outVisitStations);
        }
    }

    private final Map<UUID, Ring> rings = new HashMap<>();
    /** Station each train stood at last frame — arrival = transition. Server thread. */
    private final Map<UUID, UUID> lastStop = new HashMap<>();
    /** Overflow-safe "never" — gameTick − MIN_VALUE would wrap negative and gate forever. */
    private long lastPushTick = -(1L << 62);
    private volatile long lastSampleTick;

    /** Tick of the newest stored sample — cheap change detector for ETags. */
    public long lastSampleTick() {
        return lastSampleTick;
    }

    /** SERVER THREAD: samples internally gated to the history cadence; station
     *  arrivals are captured every frame — a whole dwell can fit between two
     *  history samples, and losing the arrival would lose the detour break. */
    public void push(LiveTrainSampler.Frame frame) {
        int sampleSeconds = DispatcherConfig.COMMON.WebHistorySampleSeconds.get();
        int hours = DispatcherConfig.COMMON.WebHistoryHours.get();
        if (hours <= 0 || sampleSeconds <= 0) return;
        int capacity = Math.max(2, hours * 3600 / sampleSeconds);
        long windowTicks = hours * 3600L * 20;
        boolean storeSamples = frame.gameTick - lastPushTick >= sampleSeconds * 20L;
        if (storeSamples) lastPushTick = frame.gameTick;

        synchronized (rings) {
            if (!frame.stops.isEmpty() || !lastStop.isEmpty()) {
                Map<UUID, LiveTrainSampler.TrainPos> positionById = new HashMap<>();
                for (LiveTrainSampler.TrainPos pos : frame.positions)
                    positionById.put(pos.id(), pos);
                java.util.Set<UUID> stopped = new java.util.HashSet<>();
                for (LiveTrainSampler.StationStop stop : frame.stops) {
                    stopped.add(stop.trainId());
                    UUID before = lastStop.put(stop.trainId(), stop.stationId());
                    if (stop.stationId().equals(before)) continue;
                    LiveTrainSampler.TrainPos pos = positionById.get(stop.trainId());
                    if (pos == null) continue;
                    Integer version = frame.graphVersions.get(pos.graphId().toString());
                    if (version == null) continue;
                    Ring ring = ringFor(stop.trainId(), pos.graphId(), version, capacity);
                    ring.addVisit(frame.gameTick, stop.stationId(), windowTicks);
                    ring.lastSeenTick = frame.gameTick;
                }
                lastStop.keySet().retainAll(stopped);
            }

            if (!storeSamples) return;
            for (LiveTrainSampler.TrainPos pos : frame.positions) {
                Integer version = frame.graphVersions.get(pos.graphId().toString());
                if (version == null) continue;
                Ring ring = ringFor(pos.id(), pos.graphId(), version, capacity);
                ring.add(frame.gameTick, pos.edgeId(), (float) pos.offset());
                ring.lastSeenTick = frame.gameTick;
            }
            // Trains gone for a full history window (deleted, derailed off-graph) free their ring.
            long horizon = frame.gameTick - windowTicks;
            rings.values().removeIf(ring -> ring.lastSeenTick < horizon);
        }
        lastSampleTick = frame.gameTick;
    }

    /** The train's ring pinned to this graph version, (re)created/reset as needed. Under rings lock. */
    private Ring ringFor(UUID trainId, UUID graphId, int version, int capacity) {
        Ring ring = rings.get(trainId);
        if (ring == null || ring.ticks.length != capacity) {
            ring = new Ring(capacity);
            ring.reset(graphId, version);
            rings.put(trainId, ring);
        } else if (!graphId.equals(ring.graphId) || version != ring.graphVersion) {
            ring.reset(graphId, version);
        }
        return ring;
    }

    /** Ring copies for every train currently pinned to this graph version. Any thread. */
    public List<Snapshot> snapshotFor(UUID graphId, int graphVersion) {
        List<Ring> matching = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        synchronized (rings) {
            for (Map.Entry<UUID, Ring> entry : rings.entrySet())
                if (graphId.equals(entry.getValue().graphId)
                        && entry.getValue().graphVersion == graphVersion) {
                    matching.add(entry.getValue());
                    ids.add(entry.getKey());
                }
        }
        List<Snapshot> out = new ArrayList<>(matching.size());
        for (int i = 0; i < matching.size(); i++)
            out.add(matching.get(i).copy(ids.get(i)));
        return out;
    }
}
