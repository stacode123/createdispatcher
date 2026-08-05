package net.Dispatcher.web.plan;

import java.util.List;
import java.util.UUID;

/**
 * A saved planned timetable: which preset each train should run, which trains the run
 * spares or removes, and the sim settings that produced it. Immutable.
 *
 * <p>Train and preset ids are stable UUIDs, but the things behind them can vanish — a
 * train is disassembled, a preset deleted. Names are stored alongside so a plan still
 * reads correctly (and can be repaired) when that happens.
 */
public record Plan(UUID id, String name, String author, long createdMs, long updatedMs,
                   String graphId, boolean removeScheduled, int horizonHours,
                   int headwaySeconds, String startTime,
                   List<Assignment> assignments, List<TrainRef> keeps, List<TrainRef> removals) {

    /** One train running one preset. */
    public record Assignment(String trainId, String trainName, String presetId, String presetName) {}

    /** A train referenced by a plan without an assignment (kept or removed). */
    public record TrainRef(String trainId, String trainName) {}
}
