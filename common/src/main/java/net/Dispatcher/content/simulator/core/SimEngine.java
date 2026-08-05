package net.Dispatcher.content.simulator.core;

import java.util.*;
import java.util.regex.Pattern;

/**
 * The deterministic train simulator. Fixed 1-tick steps, trains processed in
 * input order, no wall clock in any decision — identical inputs produce
 * identical results.
 *
 * <p>Movement, signalling and condition semantics deliberately mirror Create
 * 6.0.8 ({@code Navigation.tick}, {@code ScheduleRuntime}, {@code
 * SignalEdgeGroup}) so projected times match in-game behavior: bang-bang
 * speed control with {@code v²/2a} braking curves, per-tick section
 * reservations plus persistent chain reservations (chains claimed atomically
 * and held until crossed), signal-waiting trains ticking before moving ones,
 * occupancy checked transitively across diamond crossings, turns limited by
 * the train's turn speed, and Tramways signs replayed as per-train throttle
 * events (applied inside their braking window, matching Tramways'
 * {@code TrainMixin}).
 */
public class SimEngine {

    /** Create's arrival threshold (1/32 blocks). */
    private static final double ARRIVAL_EPS = 1 / 32d;
    /** Create's pre-departure signal lookahead at stations. */
    private static final double PRE_DEPARTURE_LOOKAHEAD = 4.5;
    /** Create's schedule retry cooldown ({@code ScheduleRuntime.INTERVAL}). */
    private static final int RETRY_COOLDOWN = 40;
    /**
     * Re-navigation cadence for a train held at a CHAIN signal — Create's
     * {@code Train.updateNavigationTarget} refreshes at
     * {@code ticksWaitingForSignal % 100 == 50}, chain signals only, and
     * never while the train still holds reserved signal blocks. Trains at
     * plain entry signals keep their route forever.
     */
    private static final long REPATH_WAIT_TICKS = 100;
    private static final long REPATH_WAIT_PHASE = 50;

    private static class StationHistory {
        long lastAny = Long.MIN_VALUE;
        final TreeMap<String, Long> byTrainName = new TreeMap<>();
        final TreeMap<String, Long> byLine = new TreeMap<>();
        final TreeMap<String, Long> byCategory = new TreeMap<>();
    }

    private final SimGraph graph;
    private final SimClock clock;
    private final long horizon;
    private final int sampleStride;
    private final long maxWallMillis;

    private final List<TrainState> trains = new ArrayList<>();
    private final SimResult result = new SimResult();
    private final ConflictDetector conflicts;

    /** Per-tick occupancy: how many trains sit in each section, and which. */
    private final int[] sectionTrainCount;
    private final int[] sectionSingleTrain;
    /**
     * Occupancy of trains that will never move again (obstacles, parked) —
     * rebuilt only when a train parks, copied per tick instead of re-marked.
     */
    private final int[] staticTrainCount;
    private final int[] staticSingleTrain;
    private boolean staticOccupancyDirty = true;
    /**
     * Per-tick section reservations, Create's {@code SignalEdgeGroup.reserved}:
     * cleared at the start of every tick, set by a non-chain scan that sights
     * a free section within braking distance. A reservation therefore lapses
     * the moment its owner stops re-asserting it (brakes for a nearer red or
     * a turn), exactly like the runtime.
     */
    private final int[] sectionReservedBy;
    /**
     * Persistent chain reservations, Create's {@code reservedSignalBlocks}:
     * granted atomically for a whole free chain, held until the head crosses
     * into each section or navigation restarts — never expiring with
     * distance. Which train holds a section; -1 = none.
     */
    private final int[] sectionChainClaimedBy;
    /**
     * Tick order, Create's {@code GlobalRailwayManager} two-list scheme:
     * signal-waiting trains tick before moving trains, longest-waiting
     * first; membership migrates after each tick.
     */
    private final List<TrainState> waitingOrder = new ArrayList<>();
    private final List<TrainState> movingOrder = new ArrayList<>();
    /** Unique legal predecessor per edge; -1 when none or ambiguous. */
    private final int[] uniquePredecessor;
    /**
     * Approachable-platform lookup for pathfinding penalties: per edge, which
     * stations a span on that edge can cover (own and mirrored from the
     * opposite edge) and at what offset in this edge's frame — so coverage is
     * found by iterating train spans instead of stations × trains.
     */
    private final int[][] edgeStationIndex;
    private final double[][] edgeStationOffset;
    /** Per station: the edge that takes its 50/300 penalty (Create: STATION). */
    private final int[] stationPenaltyEdge;
    private final double[] penaltyScratch;
    private final boolean[] stationCoveredScratch;
    /** Station targets per glob pattern — the graph never changes mid-run. */
    private final Map<String, List<SimGraph.StationTarget>> stationTargetCache = new HashMap<>();

    /**
     * Failed searches, keyed by exact start state and target set. Penalties
     * only ever add cost — they never sever reachability — so a search that
     * found no route keeps finding none until the train moves or its targets
     * change. Retrying stuck trains (Create retries every 40 ticks, forever)
     * then costs a lookup instead of a full-graph exploration.
     */
    private record PathFailKey(int headEdge, long headOffsetBits, int reverseEdge,
                               long reverseOffsetBits, String targetKey) {}

    private final java.util.Set<PathFailKey> pathFailMemo = new java.util.HashSet<>();

    /** CRN-style departure history: station name → last departures. */
    private final TreeMap<String, StationHistory> departureHistory = new TreeMap<>();

    /**
     * Pre-run seeding from the real world's departure history so separation
     * gates start from reality instead of an empty ledger (ticks are
     * engine-relative — usually negative; {@code Long.MIN_VALUE} = never).
     * Must be called before {@link #run}.
     */
    public void seedDeparture(String station, long lastAny, Map<String, Long> byLine,
                              Map<String, Long> byCategory, Map<String, Long> byName) {
        StationHistory history =
                departureHistory.computeIfAbsent(station, k -> new StationHistory());
        history.lastAny = Math.max(history.lastAny, lastAny);
        byLine.forEach((k, v) -> history.byLine.merge(k, v, Math::max));
        byCategory.forEach((k, v) -> history.byCategory.merge(k, v, Math::max));
        byName.forEach((k, v) -> history.byTrainName.merge(k, v, Math::max));
    }
    private final Map<String, Pattern> patternCache = new HashMap<>();

    private long currentTick;

    /**
     * Optional progress/abort hook, polled every {@value #PROGRESS_STRIDE}
     * ticks with the current tick; returning {@code false} aborts the run
     * (marked {@code truncated}). Null (the default) changes nothing —
     * results are bit-identical with or without a hook that returns true.
     */
    public interface Progress {
        boolean tick(long tick);
    }

    private static final int PROGRESS_STRIDE = 2048;
    private Progress progress;

    public void setProgress(Progress progress) {
        this.progress = progress;
    }

    public SimEngine(SimGraph graph, List<SimTrainSpec> specs, SimClock clock,
                     long horizonTicks, int sampleStride, long maxWallMillis) {
        this(graph, specs, clock, horizonTicks, sampleStride, maxWallMillis, 600, 200);
    }

    /**
     * @param waitConflictTicks    red-signal wait that becomes a SECTION
     *                             conflict; ≤0 disables wait conflicts
     * @param headwayConflictTicks minimum gap between consecutive trains
     *                             through a section; CRN separation
     *                             conditions tighten it per train pair, so
     *                             ≤0 only disables the flat threshold
     */
    public SimEngine(SimGraph graph, List<SimTrainSpec> specs, SimClock clock,
                     long horizonTicks, int sampleStride, long maxWallMillis,
                     long waitConflictTicks, long headwayConflictTicks) {
        this.graph = graph;
        this.clock = clock;
        this.horizon = horizonTicks;
        this.sampleStride = Math.max(1, sampleStride);
        this.maxWallMillis = maxWallMillis;
        this.sectionTrainCount = new int[graph.sectionCount()];
        this.sectionSingleTrain = new int[graph.sectionCount()];
        this.staticTrainCount = new int[graph.sectionCount()];
        this.staticSingleTrain = new int[graph.sectionCount()];
        this.sectionReservedBy = new int[graph.sectionCount()];
        java.util.Arrays.fill(sectionReservedBy, -1);
        this.sectionChainClaimedBy = new int[graph.sectionCount()];
        java.util.Arrays.fill(sectionChainClaimedBy, -1);
        // -2 marks "seen twice" while building; collapsed to -1 below.
        this.uniquePredecessor = new int[graph.edges.size()];
        java.util.Arrays.fill(uniquePredecessor, -1);
        for (SimEdge candidate : graph.edges)
            for (int next : candidate.nextEdges) {
                if (next == candidate.id)
                    continue;
                uniquePredecessor[next] = uniquePredecessor[next] == -1 ? candidate.id : -2;
            }
        for (int i = 0; i < uniquePredecessor.length; i++)
            if (uniquePredecessor[i] == -2)
                uniquePredecessor[i] = -1;

        int edgeCount = graph.edges.size();
        List<List<int[]>> touching = new ArrayList<>(edgeCount);
        List<List<Double>> touchingOffsets = new ArrayList<>(edgeCount);
        for (int i = 0; i < edgeCount; i++) {
            touching.add(new ArrayList<>());
            touchingOffsets.add(new ArrayList<>());
        }
        List<Integer> penaltyEdges = new ArrayList<>();
        for (SimEdge edge : graph.edges)
            for (SimEdge.Station station : edge.stations) {
                if (!station.approachable())
                    continue;
                int stationIndex = penaltyEdges.size();
                penaltyEdges.add(edge.id);
                touching.get(edge.id).add(new int[] { stationIndex });
                touchingOffsets.get(edge.id).add(station.offset());
                if (edge.oppositeId >= 0) {
                    touching.get(edge.oppositeId).add(new int[] { stationIndex });
                    touchingOffsets.get(edge.oppositeId).add(edge.length - station.offset());
                }
            }
        this.edgeStationIndex = new int[edgeCount][];
        this.edgeStationOffset = new double[edgeCount][];
        for (int i = 0; i < edgeCount; i++) {
            edgeStationIndex[i] = touching.get(i).stream().mapToInt(entry -> entry[0]).toArray();
            edgeStationOffset[i] = touchingOffsets.get(i).stream().mapToDouble(Double::doubleValue).toArray();
        }
        this.stationPenaltyEdge = penaltyEdges.stream().mapToInt(Integer::intValue).toArray();
        this.penaltyScratch = new double[edgeCount];
        this.stationCoveredScratch = new boolean[stationPenaltyEdge.length];

        for (int i = 0; i < specs.size(); i++)
            trains.add(new TrainState(specs.get(i), i));
        this.conflicts = new ConflictDetector(graph, trains, sectionReservedBy,
                sectionChainClaimedBy, waitConflictTicks, headwayConflictTicks);
    }

    public SimResult run() {
        long initStart = System.nanoTime();
        for (TrainState train : trains)
            initTrain(train);
        movingOrder.addAll(trains);
        result.stats.initNanos = System.nanoTime() - initStart;

        long wallStart = System.currentTimeMillis();
        long tick = 0;
        for (; tick < horizon; tick++) {
            currentTick = tick;
            if (maxWallMillis > 0 && (tick & 1023) == 0
                    && System.currentTimeMillis() - wallStart > maxWallMillis) {
                result.truncated = true;
                break;
            }
            if (progress != null && tick % PROGRESS_STRIDE == 0 && !progress.tick(tick)) {
                result.truncated = true;
                break;
            }

            long occupancyStart = System.nanoTime();
            rebuildOccupancy();
            // Create clears every group's per-tick reservation before trains
            // tick; chain reservations persist via sectionChainClaimedBy.
            java.util.Arrays.fill(sectionReservedBy, -1);
            long loopStart = System.nanoTime();
            result.stats.occupancyNanos += loopStart - occupancyStart;

            for (TrainState train : waitingOrder)
                tickTrain(train, tick);
            for (TrainState train : movingOrder)
                tickTrain(train, tick);
            migrateTickOrder();
            boolean anyActive = false;
            for (TrainState train : trains)
                if (train.mode != TrainState.Mode.PARKED && train.mode != TrainState.Mode.OBSTACLE)
                    anyActive = true;
            result.stats.trainLoopNanos += System.nanoTime() - loopStart;

            long conflictStart = System.nanoTime();
            conflicts.tick(tick);
            result.stats.conflictNanos += System.nanoTime() - conflictStart;

            if (tick % sampleStride == 0)
                for (TrainState train : trains)
                    if (train.mode != TrainState.Mode.OBSTACLE
                            && (train.mode != TrainState.Mode.PARKED || tick == 0))
                        sample(train, tick);

            if (!anyActive) {
                tick++;
                break;
            }
        }

        result.ticksSimulated = tick;
        result.stats.pathfindMemoSize = pathFailMemo.size();
        long conflictStart = System.nanoTime();
        conflicts.finish(result, tick);
        result.stats.conflictNanos += System.nanoTime() - conflictStart;
        for (TrainState train : trains) {
            sample(train, Math.min(tick, horizon));
            train.result.endState = train.mode.name();
            if (train.route != null && train.routeIndex < train.route.length) {
                int remaining = Math.min(train.route.length - train.routeIndex, 200);
                train.result.finalPlan = new int[remaining];
                System.arraycopy(train.route, train.routeIndex,
                        train.result.finalPlan, 0, remaining);
            }
            result.trains.add(train.result);
        }
        return result;
    }

    private void initTrain(TrainState train) {
        train.result.path.add(train.headEdge);
        initOccupancy(train);
        SimTrainSpec spec = train.spec;
        if (spec.program == null || spec.program.entries.isEmpty()) {
            train.mode = spec.program == null ? TrainState.Mode.OBSTACLE : TrainState.Mode.PARKED;
            sample(train, 0);
            return;
        }
        train.currentEntry = Math.min(spec.startEntry, spec.program.entries.size() - 1);
        if (spec.startWaiting
                && spec.program.entries.get(train.currentEntry).kind == SimProgram.InstructionKind.DESTINATION) {
            train.mode = TrainState.Mode.WAITING;
            train.arrivalTick = 0;
            resolveCurrentStation(train);
            initColumns(train);
            if (spec.startColumnProgress != null
                    && spec.startColumnProgress.length == train.columnProgress.length) {
                System.arraycopy(spec.startColumnProgress, 0, train.columnProgress, 0,
                        train.columnProgress.length);
                if (spec.startColumnElapsed != null
                        && spec.startColumnElapsed.length == train.columnElapsed.length)
                    System.arraycopy(spec.startColumnElapsed, 0, train.columnElapsed, 0,
                            train.columnElapsed.length);
            }
            train.result.visits.add(new SimResult.StationVisit(train.currentEntry, train.currentStationId,
                    train.currentStationName, 0, -1));
            train.holdingAtStation = true;
        } else {
            train.mode = TrainState.Mode.PRE_TRANSIT;
        }
    }

    /**
     * Covers {@code length} blocks of track behind the head: back along the
     * head edge, then through unique legal predecessors. Ambiguous history
     * (a switch directly behind) is clamped — slight under-coverage beats
     * inventing occupancy on the wrong branch.
     */
    private void initOccupancy(TrainState train) {
        double remaining = train.spec.length;
        int edgeId = train.headEdge;
        double end = train.headOffset;
        train.occupied.clear();
        train.occupiedVersion++;
        while (true) {
            double start = Math.max(0, end - remaining);
            train.occupied.addFirst(new double[] { edgeId, start, end });
            remaining -= end - start;
            if (remaining <= 1e-6)
                return;
            int predecessor = uniquePredecessor[edgeId];
            if (predecessor == -1)
                return;
            edgeId = predecessor;
            end = graph.edge(predecessor).length;
        }
    }

    private static boolean isStatic(TrainState train) {
        return train.mode == TrainState.Mode.OBSTACLE || train.mode == TrainState.Mode.PARKED;
    }

    private void rebuildOccupancy() {
        if (staticOccupancyDirty) {
            java.util.Arrays.fill(staticTrainCount, 0);
            java.util.Arrays.fill(staticSingleTrain, -1);
            for (TrainState train : trains)
                if (isStatic(train))
                    for (double[] span : train.occupied)
                        markSection(staticTrainCount, staticSingleTrain,
                                graph.edge((int) span[0]).sectionId, train.index);
            staticOccupancyDirty = false;
        }
        System.arraycopy(staticTrainCount, 0, sectionTrainCount, 0, sectionTrainCount.length);
        System.arraycopy(staticSingleTrain, 0, sectionSingleTrain, 0, sectionSingleTrain.length);
        // Opposite edges share their twin's section by construction, so
        // marking the spanned edge's section covers both directions.
        for (TrainState train : trains) {
            if (isStatic(train))
                continue;
            for (double[] span : train.occupied)
                markSection(sectionTrainCount, sectionSingleTrain,
                        graph.edge((int) span[0]).sectionId, train.index);
        }
    }

    private static void markSection(int[] count, int[] single, int section, int trainIndex) {
        if (count[section] == 0) {
            count[section] = 1;
            single[section] = trainIndex;
        } else if (single[section] != trainIndex) {
            count[section] = 2;
        }
    }

    /**
     * The train responsible for this edge's section reading occupied — a
     * physical occupant first, else the claim holder. Debug bookkeeping
     * for wait records; -1 when unknown.
     */
    private int sectionHolder(int edgeId, int me) {
        if (edgeId < 0)
            return -1;
        for (int linked : graph.sectionClosure(graph.edge(edgeId).sectionId)) {
            if (sectionTrainCount[linked] >= 1 && sectionSingleTrain[linked] != me
                    && sectionSingleTrain[linked] != -1)
                return sectionSingleTrain[linked];
            if (sectionReservedBy[linked] != -1 && sectionReservedBy[linked] != me)
                return sectionReservedBy[linked];
            if (sectionChainClaimedBy[linked] != -1 && sectionChainClaimedBy[linked] != me)
                return sectionChainClaimedBy[linked];
        }
        return -1;
    }

    /** Mirrors {@code SignalEdgeGroup.isOccupiedUnless} incl. crossings. */
    private boolean occupiedByOther(int section, int trainIndex) {
        for (int linked : graph.sectionClosure(section)) {
            if (sectionTrainCount[linked] >= 2)
                return true;
            if (sectionTrainCount[linked] == 1 && sectionSingleTrain[linked] != trainIndex)
                return true;
            if (sectionReservedBy[linked] != -1 && sectionReservedBy[linked] != trainIndex)
                return true;
            if (sectionChainClaimedBy[linked] != -1 && sectionChainClaimedBy[linked] != trainIndex)
                return true;
        }
        return false;
    }

    private void tickTrain(TrainState train, long tick) {
        switch (train.mode) {
            case OBSTACLE, PARKED -> { }
            case PRE_TRANSIT -> tickPreTransit(train, tick, true);
            case WAITING -> tickWaiting(train, tick);
            case MOVING -> tickMoving(train, tick);
        }
    }

    /**
     * Mirrors {@code GlobalRailwayManager.tickTrains}: after every tick,
     * trains that sighted a red join the waiting list (appended, so the
     * longest-waiting train stays first) and trains whose track cleared
     * rejoin the moving list.
     */
    private void migrateTickOrder() {
        for (java.util.Iterator<TrainState> it = waitingOrder.iterator(); it.hasNext();) {
            TrainState train = it.next();
            if (train.mode == TrainState.Mode.MOVING && train.blockedEdge != -1)
                continue;
            movingOrder.add(train);
            it.remove();
        }
        for (java.util.Iterator<TrainState> it = movingOrder.iterator(); it.hasNext();) {
            TrainState train = it.next();
            if (train.mode != TrainState.Mode.MOVING || train.blockedEdge == -1)
                continue;
            waitingOrder.add(train);
            it.remove();
        }
    }

    // ------------------------------------------------------------------
    // PRE_TRANSIT: schedule entry dispatch (mirrors ScheduleRuntime.tick)
    // ------------------------------------------------------------------

    /**
     * One {@code ScheduleRuntime.tick}. Create runs the runtime before the
     * navigation, so a destination dispatched this tick also MOVES this tick
     * ({@code moveOnDispatch}); a train that stays destination-less coasts
     * via passive slowdown instead of freezing. {@code moveOnDispatch} is
     * false when called from {@link #passWaypoint} — the train already
     * moved this tick.
     */
    private void tickPreTransit(TrainState train, long tick, boolean moveOnDispatch) {
        if (train.cooldown-- > 0) {
            if (moveOnDispatch)
                passiveSlowdown(train);
            return;
        }
        SimProgram program = train.spec.program;
        if (train.currentEntry >= program.entries.size()) {
            train.currentEntry = 0;
            if (!program.cyclic) {
                train.mode = TrainState.Mode.PARKED;
                staticOccupancyDirty = true;
                event(SimResult.EventType.PARKED, tick, train, 0);
            }
            return;
        }

        SimProgram.Entry entry = program.entries.get(train.currentEntry);
        switch (entry.kind) {
            case THROTTLE -> {
                train.throttle = entry.throttle;
                train.currentEntry++;
            }
            // Tramways' SetPrimaryLimitInstruction: sets the persistent sign
            // clamp AND (via its ChangeThrottleInstruction super) the throttle.
            case PRIMARY_LIMIT -> {
                train.primaryLimit = entry.throttle;
                train.throttle = entry.throttle;
                train.currentEntry++;
            }
            case NO_OP -> train.currentEntry++;
            case DESTINATION -> startNavigation(train, entry, tick, true);
        }
        if (train.mode == TrainState.Mode.MOVING) {
            if (moveOnDispatch)
                tickMoving(train, tick);
        } else if (moveOnDispatch && train.mode == TrainState.Mode.PRE_TRANSIT) {
            passiveSlowdown(train);
        }
    }

    /**
     * Create's {@code tickPassiveSlowdown}: a moving train without a
     * destination (failed resume, failed retry, non-destination instruction
     * ticks) keeps rolling {@code v²/2a} blocks while decaying by the
     * train's acceleration — it does not freeze in place. The coast follows
     * unique continuations; an ambiguous switch stops it like end-of-track
     * (Create's steering is undefined there, stopping under-covers rather
     * than inventing a branch).
     */
    private void passiveSlowdown(TrainState train) {
        if (train.speed <= 0)
            return;
        train.speed = Math.max(0, train.speed - train.spec.acceleration);
        double remaining = train.speed;
        while (remaining > 1e-9) {
            SimEdge head = graph.edge(train.headEdge);
            double available = head.length - train.headOffset;
            double moved = Math.min(remaining, available);
            if (moved > 0) {
                train.headOffset += moved;
                remaining -= moved;
                double[] headSpan = train.occupied.peekLast();
                if (headSpan != null && (int) headSpan[0] == train.headEdge) {
                    headSpan[2] = train.headOffset;
                } else {
                    train.occupied.addLast(new double[] { train.headEdge,
                            train.headOffset - moved, train.headOffset });
                    train.occupiedVersion++;
                }
            }
            if (remaining <= 1e-9)
                break;
            if (head.nextEdges.length != 1) {
                train.speed = 0;
                break;
            }
            train.headEdge = head.nextEdges[0];
            train.headOffset = 0;
            train.result.path.add(train.headEdge);
            train.occupied.addLast(new double[] { train.headEdge, 0, 0 });
            train.occupiedVersion++;
            markSection(sectionTrainCount, sectionSingleTrain,
                    graph.edge(train.headEdge).sectionId, train.index);
        }
        trimTail(train);
    }

    private void startNavigation(TrainState train, SimProgram.Entry entry, long tick,
                                 boolean allowReverse) {
        long pathfindStart = System.nanoTime();
        int reverseEdge = -1;
        double reverseOffset = 0;
        if (allowReverse && train.spec.canReverse && !train.occupied.isEmpty()) {
            double[] tailSpan = train.occupied.peekFirst();
            SimEdge tailEdge = graph.edge((int) tailSpan[0]);
            if (tailEdge.oppositeId >= 0) {
                reverseEdge = tailEdge.oppositeId;
                reverseOffset = tailEdge.length - tailSpan[1];
            }
        }

        SimPathfinder.Path path;
        if (train.resumeDestination != null
                && graph.findStation(train.resumeDestination) != null) {
            path = searchMemoized(train, List.of(graph.findStation(train.resumeDestination)),
                    "R" + train.resumeDestination, reverseEdge, reverseOffset, null);
        } else if (entry.patterns.size() == 1) {
            path = searchMemoized(train, findStationsCached(entry.pattern),
                    "P" + entry.pattern.pattern(), reverseEdge, reverseOffset, null);
        } else {
            path = prioritizedSearch(train, entry, reverseEdge, reverseOffset);
        }
        result.stats.pathfindNanos += System.nanoTime() - pathfindStart;
        result.stats.pathfindCalls++;

        if (path == null) {
            result.stats.pathfindFails++;
            if (train.lastPathFailEntry != train.currentEntry) {
                train.lastPathFailEntry = train.currentEntry;
                event(SimResult.EventType.PATH_FAILED, tick, train, train.currentEntry);
            }
            train.cooldown = RETRY_COOLDOWN;
            return;
        }
        train.lastPathFailEntry = -1;
        train.resumeDestination = null;
        // Adopting a new path restarts navigation, which in Create releases
        // all reservedSignalBlocks (a failed search keeps them).
        releaseChainClaims(train);

        if (path.reversed())
            reverse(train, tick);

        train.route = path.edges();
        train.routeIndex = 0;
        train.distanceToTarget = path.distance();
        train.targetStation = path.target().stationId();
        train.targetStationName = path.target().name();
        train.signalWaiting = false;
        train.mode = TrainState.Mode.MOVING;
    }

    /**
     * Mirrors Create's {@code Navigation} pathfinding costs: other trains
     * make their endpoint edges expensive ({@code Train.getNavigationPenalty}
     * halved, both directions), foreign platforms cost a little (a lot when a
     * train stands there), and red governed entries add signal weight — so
     * simulated routes divert around blockages exactly like real ones.
     */
    private SimPathfinder.Penalties buildPenalties(TrainState me) {
        double[] base = penaltyScratch;
        java.util.Arrays.fill(base, 0);
        boolean[] covered = stationCoveredScratch;
        java.util.Arrays.fill(covered, false);
        for (TrainState other : trains) {
            if (other == me || other.occupied.isEmpty())
                continue;
            double half = navigationPenalty(other) / 2.0;
            addEndpointPenalty(base, other.occupied.peekFirst(), half);
            addEndpointPenalty(base, other.occupied.peekLast(), half);
            for (double[] span : other.occupied) {
                int edgeId = (int) span[0];
                int[] stationsHere = edgeStationIndex[edgeId];
                double[] offsetsHere = edgeStationOffset[edgeId];
                for (int k = 0; k < stationsHere.length; k++)
                    if (offsetsHere[k] >= span[1] - 3 && offsetsHere[k] <= span[2] + 3)
                        covered[stationsHere[k]] = true;
            }
        }
        // Station penalties (Create: STATION=50, STATION_WITH_TRAIN=300).
        for (int s = 0; s < stationPenaltyEdge.length; s++)
            base[stationPenaltyEdge[s]] += covered[s] ? 350 : 50;
        // Create scales the red-signal weight with the searcher's own wait:
        // clamp(ticksWaitingForSignal * 2, 25, 200) — a long-held train
        // increasingly prefers detours. (Create also halves the weight per
        // successive occupied group within one search; that expansion-order
        // dependent decay is not reproducible in an exact search and is
        // deliberately not modeled.)
        double redWeight = me.signalWaiting
                ? clamp((currentTick - me.signalWaitStart) * 2, 25, 200)
                : 25;
        return new SimPathfinder.Penalties() {
            @Override
            public double edgeBase(int edgeId) {
                return base[edgeId];
            }

            @Override
            public boolean redSignalEntry(int edgeId) {
                return occupiedByOther(graph.edge(edgeId).sectionId, me.index);
            }

            @Override
            public double redSignalWeight() {
                return redWeight;
            }
        };
    }

    /** One memoized pathfinder invocation toward a fixed target set. */
    private SimPathfinder.Path searchMemoized(TrainState train, List<SimGraph.StationTarget> targets,
                                              String targetKey, int reverseEdge, double reverseOffset,
                                              SimPathfinder.Penalties penalties) {
        if (targets.isEmpty())
            return null;
        PathFailKey memoKey = new PathFailKey(train.headEdge,
                Double.doubleToLongBits(train.headOffset), reverseEdge,
                Double.doubleToLongBits(reverseOffset), targetKey);
        if (pathFailMemo.contains(memoKey)) {
            result.stats.pathfindMemoHits++;
            return null;
        }
        SimPathfinder.Path path = SimPathfinder.find(graph, train.headEdge, train.headOffset,
                targets, train.spec.canReverse, reverseEdge, reverseOffset,
                penalties != null ? penalties : buildPenalties(train));
        if (path == null)
            pathFailMemo.add(memoKey);
        return path;
    }

    /**
     * CRN's {@code PrioritizedDestinationInstruction.start}: filters in
     * priority order, cheapest matching station per filter, the first
     * reachable filter wins — unless avoid-trains is set, where a busy
     * chosen station makes later filters preferable (fewest problems,
     * earliest wins ties). The avoid-red-signal toggle inspects the train's
     * own surroundings, identical for every filter, so it can never change
     * which filter wins and is not modeled.
     */
    private SimPathfinder.Path prioritizedSearch(TrainState train, SimProgram.Entry entry,
                                                 int reverseEdge, double reverseOffset) {
        SimPathfinder.Penalties penalties = buildPenalties(train);
        SimPathfinder.Path best = null;
        int bestProblems = Integer.MAX_VALUE;
        for (Pattern pattern : entry.patterns) {
            SimPathfinder.Path path = searchMemoized(train, findStationsCached(pattern),
                    "P" + pattern.pattern(), reverseEdge, reverseOffset, penalties);
            if (path == null)
                continue;
            int problems = entry.avoidTrains && stationBusy(path.target(), train) ? 1 : 0;
            if (problems < bestProblems) {
                bestProblems = problems;
                best = path;
                if (problems == 0)
                    break;
            }
        }
        return best;
    }

    /** CRN's present/imminent/nearest-train test at a station, from sim state. */
    private boolean stationBusy(SimGraph.StationTarget target, TrainState me) {
        SimEdge platformEdge = graph.edge(target.edgeId());
        int opposite = platformEdge.oppositeId;
        double mirroredOffset = platformEdge.length - target.offset();
        for (TrainState other : trains) {
            if (other == me)
                continue;
            if (target.stationId().equals(other.currentStationId))
                return true;
            if (other.mode == TrainState.Mode.MOVING
                    && target.stationId().equals(other.targetStation))
                return true;
            for (double[] span : other.occupied) {
                int spanEdge = (int) span[0];
                if (spanEdge == target.edgeId()
                        && target.offset() >= span[1] - 3 && target.offset() <= span[2] + 3)
                    return true;
                if (spanEdge == opposite
                        && mirroredOffset >= span[1] - 3 && mirroredOffset <= span[2] + 3)
                    return true;
            }
        }
        return false;
    }

    private List<SimGraph.StationTarget> findStationsCached(Pattern pattern) {
        return stationTargetCache.computeIfAbsent(pattern.pattern(),
                key -> graph.findStations(pattern));
    }

    /** Create's {@code Train.getNavigationPenalty}, from sim state. */
    private double navigationPenalty(TrainState other) {
        if (other.spec.manual)
            return 200;                                         // MANUAL_TRAIN
        return switch (other.mode) {
            case OBSTACLE, PARKED -> 700;                       // IDLE_TRAIN
            // Dwelling trains have no destination and fall through to
            // ANY_TRAIN in Create — there is no station-dwell class.
            case WAITING, PRE_TRANSIT -> 25;
            case MOVING -> {
                if (other.signalWaiting)
                    yield 50 + Math.min((currentTick - other.signalWaitStart) / 20.0, 1000);
                if (other.distanceToTarget < 50 || other.signalDistance < 20)
                    yield 50;                                   // ARRIVING_TRAIN
                yield 25;                                       // ANY_TRAIN
            }
        };
    }

    private void addEndpointPenalty(double[] base, double[] span, double amount) {
        if (span == null)
            return;
        int edgeId = (int) span[0];
        base[edgeId] += amount;
        int opposite = graph.edge(edgeId).oppositeId;
        if (opposite >= 0)
            base[opposite] += amount;
    }

    /** Turn the train around: head becomes tail, spans flip direction. */
    private void reverse(TrainState train, long tick) {
        ArrayList<double[]> flipped = new ArrayList<>();
        for (double[] span : train.occupied) {
            SimEdge edge = graph.edge((int) span[0]);
            if (edge.oppositeId < 0)
                continue;
            flipped.add(new double[] { edge.oppositeId, edge.length - span[2], edge.length - span[1] });
        }
        train.occupied.clear();
        train.occupiedVersion++;
        for (int i = flipped.size() - 1; i >= 0; i--)
            train.occupied.addLast(flipped.get(i));
        if (!train.occupied.isEmpty()) {
            double[] headSpan = train.occupied.peekLast();
            train.headEdge = (int) headSpan[0];
            train.headOffset = headSpan[2];
            train.result.path.add(train.headEdge);
        }
        event(SimResult.EventType.REVERSED, tick, train, 0);
    }

    // ------------------------------------------------------------------
    // WAITING: condition columns + departure gates
    // ------------------------------------------------------------------

    private void initColumns(TrainState train) {
        SimProgram.Entry entry = train.spec.program.entries.get(train.currentEntry);
        train.columnProgress = new int[entry.columns.size()];
        train.columnElapsed = new int[entry.columns.size()];
        train.conditionsDone = false;
        train.departureGates.clear();
    }

    private void tickWaiting(TrainState train, long tick) {
        SimProgram.Entry entry = train.spec.program.entries.get(train.currentEntry);

        if (!train.conditionsDone) {
            // Mirrors ScheduleRuntime.tickConditions: completion is detected
            // at the top of the loop, columns race, conditions in a column
            // run one after another. No columns at all = wait forever.
            for (int i = 0; i < entry.columns.size(); i++) {
                List<SimCondition> column = entry.columns.get(i);
                if (train.columnProgress[i] >= column.size()) {
                    train.conditionsDone = true;
                    break;
                }
                SimCondition condition = column.get(train.columnProgress[i]);
                if (condition.tick(clock, tick, train.columnElapsed[i], train)) {
                    train.columnProgress[i]++;
                    train.columnElapsed[i] = 0;
                } else {
                    train.columnElapsed[i]++;
                }
            }
            if (!train.conditionsDone)
                return;
        }

        if (!gatesPass(train, entry, tick))
            return;

        depart(train, tick);
    }

    private boolean gatesPass(TrainState train, SimProgram.Entry entry, long tick) {
        for (SimCondition.Separation gate : train.departureGates) {
            long last;
            if (gate.stationFilter() != null && !gate.stationFilter().isBlank()) {
                last = latestDeparture(gate.filter(), train.spec.name, entry,
                        patternCache.computeIfAbsent(gate.stationFilter(), SimGlob::compile));
            } else {
                // CRN with a blank station filter maxes over ALL the entry's
                // destination filters (PrioritizedDestinationInstruction
                // .getFilters()). Trains alternate sibling platforms, so
                // consulting only the first filter hides half the relevant
                // departures and collapses the headway.
                last = Long.MIN_VALUE;
                if (entry.patterns != null)
                    for (Pattern pattern : entry.patterns)
                        last = Math.max(last,
                                latestDeparture(gate.filter(), train.spec.name, entry, pattern));
            }
            if (last != Long.MIN_VALUE && last + gate.ticks() >= tick)
                return false;
        }
        return true;
    }

    private long latestDeparture(SimCondition.TrainFilter filter, String trainName,
                                 SimProgram.Entry entry, Pattern pattern) {
        // A SAME_LINE/SAME_CATEGORY gate on a train with no section identity
        // can never match a departure (CRN: empty Optional line).
        if (filter == SimCondition.TrainFilter.SAME_LINE && entry.lineToken == null)
            return Long.MIN_VALUE;
        if (filter == SimCondition.TrainFilter.SAME_CATEGORY && entry.categoryToken == null)
            return Long.MIN_VALUE;
        long latest = Long.MIN_VALUE;
        for (Map.Entry<String, StationHistory> stationEntry : departureHistory.entrySet()) {
            if (!pattern.matcher(stationEntry.getKey()).matches())
                continue;
            StationHistory history = stationEntry.getValue();
            long value = switch (filter) {
                case SAME_NAME -> history.byTrainName.getOrDefault(trainName, Long.MIN_VALUE);
                case SAME_LINE -> history.byLine.getOrDefault(entry.lineToken, Long.MIN_VALUE);
                case SAME_CATEGORY -> history.byCategory.getOrDefault(entry.categoryToken, Long.MIN_VALUE);
                case ANY -> history.lastAny;
            };
            latest = Math.max(latest, value);
        }
        return latest;
    }

    private void depart(TrainState train, long tick) {
        // CRN records EVERY scheduled departure (TrainListener), not just
        // gated ones — other trains' separation gates measure against them.
        if (!train.currentStationName.isEmpty()) {
            StationHistory history =
                    departureHistory.computeIfAbsent(train.currentStationName, k -> new StationHistory());
            history.lastAny = tick;
            history.byTrainName.put(train.spec.name, tick);
            SimProgram.Entry entry = train.spec.program.entries.get(train.currentEntry);
            if (entry.lineToken != null)
                history.byLine.put(entry.lineToken, tick);
            if (entry.categoryToken != null)
                history.byCategory.put(entry.categoryToken, tick);
        }

        if (!train.result.visits.isEmpty()) {
            SimResult.StationVisit last = train.result.visits.get(train.result.visits.size() - 1);
            if (last.departureTick() == -1)
                train.result.visits.set(train.result.visits.size() - 1,
                        new SimResult.StationVisit(last.entryIndex(), last.stationId(),
                                last.stationName(), last.arrivalTick(), tick));
        }
        event(SimResult.EventType.DEPARTURE, tick, train, train.currentEntry);

        train.departureGates.clear();
        train.conditionsDone = false;
        train.columnProgress = null;
        train.columnElapsed = null;
        train.currentEntry++;
        train.cooldown = 0;
        train.mode = TrainState.Mode.PRE_TRANSIT;
        train.holdingAtStation = true;
    }

    // ------------------------------------------------------------------
    // MOVING: Create's Navigation.tick, in order
    // ------------------------------------------------------------------

    private void tickMoving(TrainState train, long tick) {
        double acceleration = train.spec.acceleration;
        double brakingDistance = (train.speed * train.speed) / (2 * acceleration);
        double brakingNoFlicker = brakingDistance + 3 - (brakingDistance % 3);
        double preDeparture = train.holdingAtStation ? PRE_DEPARTURE_LOOKAHEAD : 0;
        double scanDistance = clamp(brakingNoFlicker, preDeparture, train.distanceToTarget);

        double signalStop = scanSignals(train, scanDistance, brakingNoFlicker);
        train.blockedEdge = signalStop >= 0 ? scanBlockedEdge : -1;
        train.signalDistance = signalStop >= 0 ? signalStop : Double.MAX_VALUE;
        boolean stoppedAtChainSignal = signalStop >= 0 && scanStopChainSignal;

        // Signal wait bookkeeping (feeds the M4 conflict detectors).
        if (signalStop >= 0 && signalStop < 1 && train.speed < 1e-4) {
            if (!train.signalWaiting) {
                train.signalWaiting = true;
                train.signalWaitStart = tick;
                event(SimResult.EventType.SIGNAL_WAIT_START, tick, train, 0);
                train.result.waitBlocks.add(new long[] { tick, train.blockedEdge,
                        sectionHolder(train.blockedEdge, train.index) });
            }
        } else if (train.signalWaiting && signalStop < 0) {
            train.signalWaiting = false;
            event(SimResult.EventType.SIGNAL_WAIT_END, tick, train, tick - train.signalWaitStart);
        }

        // Create re-navigates a held train ONLY at chain signals, every 100
        // waiting ticks, and never while it still holds reserved sections
        // (Train.updateNavigationTarget) — a train pinned at a plain entry
        // signal keeps its route forever, so real entry-signal deadlocks
        // stay deadlocked here too instead of dissolving into a diversion.
        if (train.signalWaiting && stoppedAtChainSignal
                && (tick - train.signalWaitStart) % REPATH_WAIT_TICKS == REPATH_WAIT_PHASE
                && train.chainClaims.isEmpty())
            repath(train, tick);

        // SnR waypoints: the train rolls through without braking for the
        // target — only signals ahead still slow it down.
        boolean waypoint = isWaypointEntry(train);
        double targetDistance = waypoint
                ? (signalStop >= 0 ? signalStop + 0.25 : Double.MAX_VALUE)
                : (signalStop >= 0
                        ? Math.min(signalStop, train.distanceToTarget)
                        : train.distanceToTarget) + 0.25;

        // Don't leave the platform until the exit signal clears.
        if (targetDistance > ARRIVAL_EPS && train.holdingAtStation) {
            if (signalStop >= 0 && signalStop < PRE_DEPARTURE_LOOKAHEAD)
                return;
            train.holdingAtStation = false;
            train.currentStationId = null;
            train.currentStationName = "";
        }

        double topSpeed = train.spec.topSpeed;
        if (targetDistance - train.speed < ARRIVAL_EPS) {
            train.speed = Math.max(targetDistance, ARRIVAL_EPS);
        } else if (targetDistance < 10 && train.speed > topSpeed * (targetDistance / 10)) {
            train.speed += (topSpeed * (targetDistance / 10) - train.speed) * .5f;
        } else {
            double effectiveTop = topSpeed * train.throttle;
            double turnTop = Math.min(effectiveTop, train.spec.turnSpeed);

            double targetSpeed = targetDistance > brakingDistance ? effectiveTop : 0;

            double nextTurn = distanceToNextTurn(train, brakingNoFlicker);
            if (nextTurn >= 0) {
                double slowingDistance = brakingDistance - (turnTop * turnTop) / (2 * acceleration);
                targetSpeed = Math.min(targetSpeed, nextTurn > slowingDistance ? effectiveTop : turnTop);
            }
            // Tramways registers signs from Navigation's scan with real
            // distances, so real trains brake IN ANTICIPATION of a lower
            // speed sign (SpeedSignDemand applies within braking
            // displacement).
            LowerSign nextSlowSign = distanceToLowerSignEvent(train, brakingNoFlicker, effectiveTop);
            if (nextSlowSign != null) {
                double slowingDistance = brakingDistance
                        - (nextSlowSign.targetSpeed() * nextSlowSign.targetSpeed()) / (2 * acceleration);
                targetSpeed = Math.min(targetSpeed,
                        nextSlowSign.distance() > slowingDistance ? effectiveTop : nextSlowSign.targetSpeed());
            }
            // Sighted sign demands execute every tick in Tramways, mutating
            // the persistent train.throttle inside their displacement window
            // — before the sign, so the change survives a later reroute and
            // temporary raises take effect early. Only the nearest event is
            // window-applied; anything behind it keeps crossing order.
            FirstSign firstSign = firstSignEventAhead(train, brakingNoFlicker);
            if (firstSign != null)
                applySignDemandInWindow(train, firstSign, acceleration);

            if (train.speed < targetSpeed)
                train.speed = Math.min(train.speed + acceleration, targetSpeed);
            else if (train.speed > targetSpeed)
                train.speed = Math.max(train.speed - acceleration, targetSpeed);
        }

        double signalLimit = signalStop >= 0 ? Math.max(0, signalStop) : Double.MAX_VALUE;
        double step = Math.min(train.speed, Math.min(train.distanceToTarget, signalLimit));
        if (step > 0)
            advance(train, step);
        // Pinned at a red signal: the train halts and restarts from zero.
        if (signalStop >= 0 && signalStop - step <= ARRIVAL_EPS)
            train.speed = 0;

        if (train.distanceToTarget <= ARRIVAL_EPS && signalStop < 0) {
            if (waypoint)
                passWaypoint(train, tick);
            else
                arrive(train, tick);
        }
    }

    private boolean isWaypointEntry(TrainState train) {
        SimProgram program = train.spec.program;
        return program != null && train.currentEntry < program.entries.size()
                && program.entries.get(train.currentEntry).waypoint;
    }

    /**
     * Walks the route ahead looking for governed signals, mirroring Create's
     * signal scout: entry signals stop the scan when their section is taken;
     * chain signals collect whole chains and wait at the chain's first
     * signal; free sections within braking distance get reserved (per-tick
     * claims in train order).
     *
     * @return distance to the signal to stop at, or -1
     */
    /** The governed edge whose occupied section caused the last scan's stop. */
    private int scanBlockedEdge;
    /** Whether the last scan's stop position is a CHAIN signal (repath gate). */
    private boolean scanStopChainSignal;

    private double scanSignals(TrainState train, double scanDistance, double brakingDistance) {
        scanBlockedEdge = -1;
        scanStopChainSignal = false;
        if (train.route == null)
            return -1;
        double distance = graph.edge(train.headEdge).length - train.headOffset;
        double chainStart = -1;
        List<Integer> chainSections = new ArrayList<>();
        double stop = -1;

        for (int i = train.routeIndex; i + 1 < train.route.length; i++) {
            if (distance >= train.distanceToTarget - 1e-6)
                break;
            // Chains once started are followed to their terminating entry
            // signal regardless of scan distance, like Create's scout.
            if (chainStart == -1 && distance > scanDistance)
                break;

            SimEdge next = graph.edge(train.route[i + 1]);
            if (next.entrySignal != SimEdge.Signal.NONE) {
                int section = next.sectionId;
                // Forced red (redstone-held signal) reads red regardless of
                // occupancy, exactly like Create's scan.
                boolean occupied = next.entryForcedRed || occupiedByOther(section, train.index);
                boolean chain = next.entrySignal == SimEdge.Signal.CHAIN;

                if (chainStart == -1) {
                    if (chain) {
                        chainStart = distance;
                        chainSections.clear();
                        chainSections.add(section);
                    }
                    if (occupied) {
                        stop = distance;
                        scanStopChainSignal = chain;
                        if (scanBlockedEdge == -1)
                            scanBlockedEdge = next.id;
                        if (!chain)
                            return stop;
                    }
                    if (!occupied && !chain && distance < brakingDistance)
                        claim(section, train);
                } else {
                    chainSections.add(section);
                    if (occupied) {
                        stop = chainStart;
                        scanStopChainSignal = true;
                        if (scanBlockedEdge == -1)
                            scanBlockedEdge = next.id;
                    }
                    if (!chain) {
                        if (stop == -1) {
                            for (int chained : chainSections)
                                claimChain(chained, train);
                            chainStart = -1;
                            chainSections.clear();
                        } else {
                            return stop;
                        }
                    }
                }
            }
            distance += next.length;
        }

        if (chainStart != -1 && stop == -1)
            for (int chained : chainSections)
                claimChain(chained, train);
        return stop;
    }

    /**
     * Per-tick reservation, Create's {@code group.reserved}: valid for this
     * tick only, re-asserted by each scan that still reaches the section.
     */
    private void claim(int section, TrainState train) {
        sectionReservedBy[section] = train.index;
    }

    /**
     * Persistent chain reservation, Create's {@code reservedSignalBlocks}:
     * held until the head crosses into the section or navigation restarts.
     */
    private void claimChain(int section, TrainState train) {
        if (sectionChainClaimedBy[section] == train.index)
            return;
        sectionChainClaimedBy[section] = train.index;
        train.chainClaims.add(section);
    }

    /** Releases every chain reservation (Create: navigation restart/end). */
    private void releaseChainClaims(TrainState train) {
        for (int section : train.chainClaims)
            if (sectionChainClaimedBy[section] == train.index)
                sectionChainClaimedBy[section] = -1;
        train.chainClaims.clear();
    }

    /**
     * Re-runs navigation for the current destination while the train is
     * held at a red chain signal. Forward only — Create's
     * {@code Navigation.findPathTo} skips the reversing direction while a
     * destination is active ("avoid reversing out of path"). The wait
     * bookkeeping survives the call: if the fresh route clears the way,
     * next tick's scan closes the wait window naturally; if nothing better
     * exists the search returns the same route (or fails, keeping the old
     * one) and the wait keeps aging toward a conflict report.
     */
    private void repath(TrainState train, long tick) {
        SimProgram program = train.spec.program;
        if (program == null || train.currentEntry >= program.entries.size())
            return;
        SimProgram.Entry entry = program.entries.get(train.currentEntry);
        if (entry.kind != SimProgram.InstructionKind.DESTINATION)
            return;
        boolean waiting = train.signalWaiting;
        long waitStart = train.signalWaitStart;
        startNavigation(train, entry, tick, false);
        train.signalWaiting = waiting;
        train.signalWaitStart = waitStart;
    }

    /** Distance to the next turn region ahead (0 if inside one), or -1. */
    private double distanceToNextTurn(TrainState train, double lookahead) {
        SimEdge head = graph.edge(train.headEdge);
        if (head.inTurn(train.headOffset))
            return 0;
        double best = -1;
        for (double[] range : head.turnRanges)
            if (range[0] > train.headOffset) {
                double d = range[0] - train.headOffset;
                if (d <= lookahead && (best < 0 || d < best))
                    best = d;
            }
        if (train.route != null) {
            double base = head.length - train.headOffset;
            for (int i = train.routeIndex + 1; i < train.route.length && base <= lookahead; i++) {
                SimEdge edge = graph.edge(train.route[i]);
                for (double[] range : edge.turnRanges) {
                    double d = base + range[0];
                    if (d <= lookahead && (best < 0 || d < best))
                        best = d;
                }
                base += edge.length;
            }
        }
        return best;
    }

    /** An upcoming sign event that will slow the train, with its distance. */
    private record LowerSign(double distance, double targetSpeed, SimEdge.SignEvent event) {}

    /**
     * Nearest upcoming sign event within {@code lookahead} that would LOWER
     * the effective top — or null. Only speed-setting events count (releases
     * and permanent signs shadowed by an active temporary zone don't slow
     * real trains on approach).
     */
    private LowerSign distanceToLowerSignEvent(TrainState train, double lookahead, double effectiveTop) {
        SimEdge head = graph.edge(train.headEdge);
        double base = -train.headOffset;
        int routeIndex = train.routeIndex;
        SimEdge edge = head;
        while (true) {
            for (SimEdge.SignEvent event : edge.signEvents) {
                double distance = base + event.offset();
                if (distance <= 0)
                    continue;
                if (distance > lookahead)
                    return null;
                if (event.kind() == SimEdge.SignEvent.Kind.RELEASE)
                    continue;
                if (event.kind() == SimEdge.SignEvent.Kind.PERMANENT && train.storedPermanent != null)
                    continue;
                double target = Math.min(event.throttle(), train.primaryLimit) * train.spec.topSpeed;
                if (target < effectiveTop)
                    return new LowerSign(distance, target, event);
            }
            base += edge.length;
            if (base > lookahead || train.route == null || routeIndex + 1 >= train.route.length)
                return null;
            routeIndex++;
            edge = graph.edge(train.route[routeIndex]);
        }
    }

    /** The single nearest sign event ahead on the route, any kind. */
    private record FirstSign(double distance, SimEdge.SignEvent event) {}

    private FirstSign firstSignEventAhead(TrainState train, double lookahead) {
        double base = -train.headOffset;
        int routeIndex = train.routeIndex;
        SimEdge edge = graph.edge(train.headEdge);
        while (true) {
            for (SimEdge.SignEvent event : edge.signEvents) {
                double distance = base + event.offset();
                if (distance <= 0)
                    continue;
                return distance > lookahead ? null : new FirstSign(distance, event);
            }
            base += edge.length;
            if (base > lookahead || train.route == null || routeIndex + 1 >= train.route.length)
                return null;
            routeIndex++;
            edge = graph.edge(train.route[routeIndex]);
        }
    }

    /**
     * Tramways' per-tick demand execution for a sighted sign: TEMPORARY
     * applies within {@code |v²−u²|/2a} of the sign — early for reductions
     * AND raises ({@code TemporarySpeedSignDemand.execute}); PERMANENT
     * applies within its braking displacement when reducing and otherwise
     * waits for the crossing ({@code SpeedSignDemand.execute}: raises only
     * within 1 block); RELEASE restores only on crossing. Application is
     * idempotent, so the crossing re-fire is harmless.
     */
    private void applySignDemandInWindow(TrainState train, FirstSign sign, double acceleration) {
        SimEdge.SignEvent event = sign.event();
        if (event.kind() == SimEdge.SignEvent.Kind.RELEASE)
            return;
        double v = Math.min(event.throttle(), train.primaryLimit) * train.spec.topSpeed;
        double u = train.speed;
        if (event.kind() == SimEdge.SignEvent.Kind.PERMANENT && v >= u)
            return;
        double window = Math.abs(v * v - u * u) / (2 * acceleration);
        if (sign.distance() <= window)
            applySignEvent(train, event);
    }

    /**
     * Applies the sign events the head crossed while moving from {@code from}
     * (exclusive) to {@code to} (inclusive) on {@code edge}. Tramways applies
     * demands when the front passes the sign (higher limits), and lower
     * limits already braked for via {@link #distanceToLowerSignEvent}.
     */
    private void fireSignEvents(TrainState train, SimEdge edge, double from, double to) {
        for (SimEdge.SignEvent event : edge.signEvents) {
            if (event.offset() <= from)
                continue;
            if (event.offset() > to)
                break;
            applySignEvent(train, event);
        }
    }

    /** Mirrors Tramways' {@code TrainMixin.tramways$tickSigns} per crossing. */
    private void applySignEvent(TrainState train, SimEdge.SignEvent event) {
        switch (event.kind()) {
            case TEMPORARY -> {
                if (train.storedPermanent == null)
                    train.storedPermanent = train.throttle;
                train.throttle = Math.min(event.throttle(), train.primaryLimit);
            }
            case RELEASE -> {
                if (train.storedPermanent != null) {
                    train.throttle = train.storedPermanent;
                    train.storedPermanent = null;
                }
            }
            case PERMANENT -> {
                double fraction = Math.min(event.throttle(), train.primaryLimit);
                if (train.storedPermanent == null)
                    train.throttle = fraction;
                else
                    train.storedPermanent = fraction;
            }
        }
    }

    /** Moves the head {@code step} blocks along the route, dragging the tail. */
    private void advance(TrainState train, double step) {
        double remaining = step;
        while (remaining > 1e-9) {
            SimEdge head = graph.edge(train.headEdge);
            double available = head.length - train.headOffset;
            double moved = Math.min(remaining, available);
            if (moved > 0) {
                train.headOffset += moved;
                remaining -= moved;
                if (!head.signEvents.isEmpty())
                    fireSignEvents(train, head, train.headOffset - moved, train.headOffset);
                double[] headSpan = train.occupied.peekLast();
                if (headSpan != null && (int) headSpan[0] == train.headEdge) {
                    headSpan[2] = train.headOffset;
                } else {
                    train.occupied.addLast(new double[] { train.headEdge,
                            train.headOffset - moved, train.headOffset });
                    train.occupiedVersion++;
                }
            }
            if (remaining <= 1e-9)
                break;
            if (train.route == null || train.routeIndex + 1 >= train.route.length)
                break;
            train.routeIndex++;
            train.headEdge = train.route[train.routeIndex];
            train.headOffset = 0;
            train.result.path.add(train.headEdge);
            train.occupied.addLast(new double[] { train.headEdge, 0, 0 });
            train.occupiedVersion++;
            // Crossing a boundary converts a chain reservation into physical
            // occupancy (Create's occupy()). Mark the section immediately so
            // later-ticking trains this tick don't see a gap between the
            // released reservation and the next occupancy rebuild.
            SimEdge entered = graph.edge(train.headEdge);
            markSection(sectionTrainCount, sectionSingleTrain, entered.sectionId, train.index);
            if (entered.entrySignal != SimEdge.Signal.NONE
                    && sectionChainClaimedBy[entered.sectionId] == train.index) {
                sectionChainClaimedBy[entered.sectionId] = -1;
                train.chainClaims.remove(Integer.valueOf(entered.sectionId));
            }
        }
        train.distanceToTarget -= step - remaining;
        trimTail(train);
    }

    /**
     * Drops occupancy behind the tail, but never the last span: a train always
     * occupies at least the point under its head. That matters for the
     * ZERO-LENGTH trains Create really produces — occupancy runs between the
     * leading and trailing travelling points, so a single-carriage,
     * single-bogey train measures 0 blocks — which would otherwise trim their
     * occupancy away entirely, leaving them invisible to signalling and, worse,
     * unable to turn around: {@link #startNavigation} reads the rearmost span
     * to offer the reversed departure, so an empty deque means forward-only
     * searches and a permanent PATH_FAILED at any dead-end platform.
     * Trains with a real length keep their previous occupancy exactly — the
     * remainder can never reach the last span for them.
     */
    private void trimTail(TrainState train) {
        double excess = train.occupiedLength() - train.spec.length;
        while (excess > 1e-9 && train.occupied.size() > 1) {
            double[] tail = train.occupied.peekFirst();
            double spanLength = tail[2] - tail[1];
            if (spanLength <= excess + 1e-9) {
                train.occupied.pollFirst();
                train.occupiedVersion++;
                excess -= spanLength;
            } else {
                tail[1] += excess;
                excess = 0;
            }
        }
        if (excess > 1e-9 && !train.occupied.isEmpty()) {
            double[] tail = train.occupied.peekFirst();
            tail[1] = Math.min(tail[2], tail[1] + excess);
        }
    }

    /**
     * Passes a Steam 'n' Rails waypoint: a zero-dwell visit is recorded and
     * the next entry dispatches the same tick, keeping the current speed —
     * the momentary standstill this tick is the 1-tick quantization of
     * SnR's seamless roll-through.
     */
    private void passWaypoint(TrainState train, long tick) {
        train.route = null;
        train.distanceToTarget = 0;
        train.blockedEdge = -1;
        releaseChainClaims(train);
        if (train.signalWaiting) {
            train.signalWaiting = false;
            event(SimResult.EventType.SIGNAL_WAIT_END, tick, train, tick - train.signalWaitStart);
        }
        train.result.visits.add(new SimResult.StationVisit(train.currentEntry, train.targetStation,
                train.targetStationName, tick, tick));
        event(SimResult.EventType.ARRIVAL, tick, train, train.currentEntry);
        event(SimResult.EventType.DEPARTURE, tick, train, train.currentEntry);
        sample(train, tick);
        train.currentEntry++;
        train.cooldown = 0;
        train.holdingAtStation = false;
        train.mode = TrainState.Mode.PRE_TRANSIT;
        tickPreTransit(train, tick, false);
    }

    private void arrive(TrainState train, long tick) {
        train.speed = 0;
        train.mode = TrainState.Mode.WAITING;
        train.route = null;
        train.distanceToTarget = 0;
        releaseChainClaims(train);
        train.currentStationId = train.targetStation;
        train.currentStationName = train.targetStationName;
        train.arrivalTick = tick;
        train.holdingAtStation = true;
        train.blockedEdge = -1;
        if (train.signalWaiting) {
            train.signalWaiting = false;
            event(SimResult.EventType.SIGNAL_WAIT_END, tick, train, tick - train.signalWaitStart);
        }
        initColumns(train);
        train.result.visits.add(new SimResult.StationVisit(train.currentEntry, train.currentStationId,
                train.currentStationName, tick, -1));
        event(SimResult.EventType.ARRIVAL, tick, train, train.currentEntry);
        sample(train, tick);
    }

    /** Fills in station identity for trains that start mid-dwell. */
    private void resolveCurrentStation(TrainState train) {
        SimEdge head = graph.edge(train.headEdge);
        for (SimEdge.Station station : head.stations)
            if (Math.abs(station.offset() - train.headOffset) < 1.5) {
                train.currentStationId = station.id();
                train.currentStationName = station.name();
                return;
            }
    }

    private void sample(TrainState train, long tick) {
        List<SimResult.Sample> samples = train.result.samples;
        if (!samples.isEmpty()) {
            SimResult.Sample last = samples.get(samples.size() - 1);
            if (last.tick() == tick)
                return;
            if (last.edgeId() == train.headEdge && Math.abs(last.offset() - train.headOffset) < 1e-3
                    && Math.abs(last.speed() - train.speed) < 1e-4)
                return;
        }
        samples.add(new SimResult.Sample(tick, train.headEdge, (float) train.headOffset, (float) train.speed));
    }

    private void event(SimResult.EventType type, long tick, TrainState train, long data) {
        result.events.add(new SimResult.SimEvent(type, tick, train.index, train.headEdge,
                train.headOffset, data));
    }

    private static double clamp(double value, double min, double max) {
        if (max < min)
            return max;
        return Math.max(min, Math.min(max, value));
    }
}
