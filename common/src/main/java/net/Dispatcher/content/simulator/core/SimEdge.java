package net.Dispatcher.content.simulator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A directed edge of the simulation graph, mirroring one graph-v2
 * {@code RailEdge} (same id). All speeds are blocks/tick, lengths blocks.
 */
public class SimEdge {

    /** Entry signal kinds, matching graph v2's {@code SignalKind} ordinals. */
    public enum Signal {
        NONE, ENTRY, CHAIN
    }

    public record Station(UUID id, String name, double offset, boolean approachable) {}

    public record Crossing(UUID id, double offset) {}

    /**
     * A Tramways sign the head crosses at {@code offset}, mutating the
     * train's persistent throttle exactly like Tramways' {@code TrainMixin}:
     * PERMANENT sets it, TEMPORARY sets it while stashing the previous value,
     * RELEASE restores the stashed value. {@code throttle} is a fraction of
     * the train's max speed (0 for RELEASE), clamped per-train by the spec's
     * primary limit when applied.
     */
    public record SignEvent(double offset, Kind kind, double throttle) {
        public enum Kind {
            TEMPORARY, RELEASE, PERMANENT
        }
    }

    public final int id;
    public final int from;
    public final int to;
    /** Reverse-direction twin, or -1 for one-way constructs. */
    public final int oppositeId;
    public final double length;
    /** Signal governing entry into this edge at its start node, if any. */
    public final Signal entrySignal;
    /**
     * Static per-walk view of Tramways sign zones (blocks/tick), 0 = none.
     * Display/diagnostic only — the engine models zones dynamically through
     * {@link #signEvents} carried as per-train throttle state, which is what
     * lets a zone survive junctions. Curvature caps are deliberately NOT
     * applied: in-game trains ignore them; turns instead limit to the
     * train's turn speed (Create behavior).
     */
    public final double signCap;
    /**
     * Turn ranges as {@code [start, end]} offsets; trains use turn speed
     * inside. Mirrors Create's Navigation curve listener: every bezier span
     * except straight mild slopes — flat straight bezier connections and
     * gentle curves of any radius count too.
     */
    public final double[][] turnRanges;
    /** Unit direction of travel at the start / end of this edge. */
    public final SimVec entryTangent;
    public final SimVec exitTangent;
    public final boolean interDimensional;
    public final List<Station> stations = new ArrayList<>();
    public final List<Crossing> crossings = new ArrayList<>();
    /** Sign crossings in travel direction, sorted by offset. */
    public final List<SignEvent> signEvents = new ArrayList<>();

    /**
     * The governing entry signal reported redstone power at snapshot time:
     * red regardless of occupancy, and Create's REDSTONE_RED_SIGNAL (400)
     * pathfinding penalty applies instead of the occupancy weight.
     */
    public boolean entryForcedRed = false;

    /** Signal section this edge belongs to; assigned by {@link SimGraph}. */
    public int sectionId = -1;
    /** Legal continuation edge ids at the end node; assigned by {@link SimGraph}. */
    public int[] nextEdges = new int[0];

    public SimEdge(int id, int from, int to, int oppositeId, double length, Signal entrySignal,
                   double signCap, double[][] turnRanges, SimVec entryTangent, SimVec exitTangent,
                   boolean interDimensional) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.oppositeId = oppositeId;
        this.length = length;
        this.entrySignal = entrySignal;
        this.signCap = signCap;
        this.turnRanges = turnRanges;
        this.entryTangent = entryTangent;
        this.exitTangent = exitTangent;
        this.interDimensional = interDimensional;
    }

    public boolean inTurn(double offset) {
        for (double[] range : turnRanges)
            if (offset >= range[0] && offset <= range[1])
                return true;
        return false;
    }
}
