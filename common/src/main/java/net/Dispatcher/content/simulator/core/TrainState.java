package net.Dispatcher.content.simulator.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mutable engine-side state of one train. Position is tracked as the head's
 * (edge, offset) plus the occupancy spans behind it; navigation as the
 * remaining route of edge ids with {@code route[routeIndex] == headEdge}.
 */
public class TrainState {

    /**
     * "No departure slot" — tick 0 is a real slot, and a slot seeded from a
     * live snapshot may be negative (already due), so 0 cannot be the marker.
     */
    public static final long UNBOOKED = Long.MIN_VALUE;

    public enum Mode {
        /** Needs to start/resolve its current schedule entry. */
        PRE_TRANSIT,
        /** Following a route toward a station. */
        MOVING,
        /** At a station, ticking wait conditions / departure gates. */
        WAITING,
        /** Done (non-cyclic end) — occupies track, never moves again. */
        PARKED,
        /** Static obstacle from the start (manual/unscheduled/excluded). */
        OBSTACLE
    }

    public final SimTrainSpec spec;
    public final int index;

    public Mode mode;
    public double speed;
    public double throttle;
    /**
     * Throttle stashed by an active temporary sign zone (restored by a
     * RELEASE sign event), or null — mirrors Tramways' stored permanent.
     */
    public Double storedPermanent;
    /**
     * Persistent clamp on sign throttles ("line speed") — mirrors Tramways'
     * primary limit; updated by PRIMARY_LIMIT schedule instructions.
     */
    public double primaryLimit;

    public int headEdge;
    public double headOffset;
    /** Spans {@code {edgeId, startOffset, endOffset}}, ordered tail → head. */
    public final ArrayDeque<double[]> occupied = new ArrayDeque<>();
    /**
     * Bumped whenever {@code occupied} gains or loses a span (edge set may
     * have changed). Read only by the conflict detectors' fast path.
     */
    public int occupiedVersion;

    public int[] route;
    public int routeIndex;
    public double distanceToTarget;
    public UUID targetStation;
    public String targetStationName = "";
    /** Station to resume toward (train was in transit at snapshot), or null. */
    public UUID resumeDestination;
    /** Entry index whose pathfinding already logged a failure event. */
    public int lastPathFailEntry = -1;

    /** Still holding at the departure station (Create's pre-departure gate). */
    public boolean holdingAtStation;
    public int cooldown;

    public int currentEntry;
    public int[] columnProgress;
    public int[] columnElapsed;
    /** Set once every gate-relevant column finished; departure gates pending. */
    public boolean conditionsDone;
    public final List<SimCondition.Separation> departureGates = new ArrayList<>();
    /**
     * Departure slot booked by this column's {@link SimCondition
     * .TimeOfDayRealistic}, in day time, or {@link #UNBOOKED} — Realism's
     * {@code RealismDepartAt} context tag, cleared with the rest of the
     * column's context on arrival and on completion.
     */
    public long[] columnDepartAt;
    /**
     * Day time this train last departed on (Realism's
     * {@code lastDepartureDayTime}), or {@link #UNBOOKED}: carries across
     * stops, and is what keeps a timetable moving forward instead of
     * re-basing on every arrival.
     */
    public long lastDepartureSlot = UNBOOKED;

    public UUID currentStationId;
    public String currentStationName = "";
    /** Tick (not day time) the train started waiting at its current stop, or -1 if never. */
    public long arrivalTick = -1;

    public boolean signalWaiting;
    public long signalWaitStart;
    /**
     * The governed edge whose red entry signal the last scan stopped at, or
     * -1. Observation for the conflict detectors — never a decision input.
     */
    public int blockedEdge = -1;
    /**
     * Distance to the signal the last scan stopped at (MAX_VALUE when
     * clear) — feeds Create's ARRIVING_TRAIN penalty class.
     */
    public double signalDistance = Double.MAX_VALUE;
    /**
     * Sections chain-reserved for this train (Create's
     * {@code reservedSignalBlocks}) — persistent until crossed or navigation
     * restarts. Non-empty suppresses re-navigation at reds.
     */
    public final List<Integer> chainClaims = new ArrayList<>();

    public final SimResult.TrainResult result;

    public TrainState(SimTrainSpec spec, int index) {
        this.spec = spec;
        this.index = index;
        this.throttle = spec.initialThrottle;
        this.storedPermanent = spec.initialStoredPermanent;
        this.primaryLimit = spec.primaryLimit;
        this.speed = spec.initialSpeed;
        this.headEdge = spec.headEdge;
        this.headOffset = spec.headOffset;
        this.currentEntry = spec.startEntry;
        this.cooldown = spec.startCooldown;
        this.resumeDestination = spec.resumeDestination;
        this.result = new SimResult.TrainResult(spec.id, spec.name, spec.program == null);
        this.result.notices.addAll(spec.notices);
    }

    public double occupiedLength() {
        double total = 0;
        for (double[] span : occupied)
            total += span[2] - span[1];
        return total;
    }
}
