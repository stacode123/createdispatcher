package net.Dispatcher.content.simulator;

import com.simibubi.create.content.trains.graph.TrackGraph;
import net.Dispatcher.content.simulator.core.SimClock;
import net.Dispatcher.content.simulator.core.SimEngine;
import net.Dispatcher.content.simulator.core.SimGraph;
import net.Dispatcher.content.simulator.core.SimResult;
import net.Dispatcher.content.simulator.core.SimTrainSpec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.List;

/**
 * Player-free simulation entry point (the web interface's engine door, and W5's planner
 * backend): {@link #prepare} snapshots the live network on the server thread, {@link #run}
 * executes the deterministic engine on any thread. No packets, no items, no screens —
 * schedule overrides (planner assignments) arrive in W5 as an extension of {@code prepare}.
 */
public final class HeadlessSimService {

    /** In-game hours are 1000 day-time ticks; horizons run in real ticks. */
    public static final int TICKS_PER_HOUR = 1000;

    /**
     * Everything a run needs, captured on the server thread. {@code removedTrains} names
     * the live trains the planner took off the network for this run.
     */
    public record Prepared(SimGraph graph, List<SimTrainSpec> specs, SimClock clock,
                           long horizonTicks, long startGameTick,
                           List<NetworkSnapshotter.Excluded> excluded,
                           List<net.Dispatcher.compat.CrnCompat.DepartureSeed> departureSeeds,
                           List<String> removedTrains) {}

    /** A planner assignment that could not be applied; the sim ran without it. */
    public record OverrideIssue(String trainName, String translationKey, String detail) {}

    /**
     * What the planner does to the live snapshot before the run.
     *
     * @param schedules       per train, the schedule tag to install instead of its live one
     * @param remove          trains to take off the network outright
     * @param keep            trains {@code removeScheduled} must spare
     * @param removeScheduled clear the timetable: every train running a schedule leaves the
     *                        network unless it is assigned or kept. Unscheduled trains
     *                        (parked, paused, manual, derailed) stay — they physically
     *                        occupy track, so a planned timetable must still route around
     *                        them.
     */
    public record PlannerInput(java.util.Map<java.util.UUID, net.minecraft.nbt.CompoundTag> schedules,
                               java.util.Set<java.util.UUID> remove,
                               java.util.Set<java.util.UUID> keep,
                               boolean removeScheduled) {

        public static final PlannerInput NONE =
                new PlannerInput(java.util.Map.of(), java.util.Set.of(), java.util.Set.of(), false);

        boolean empty() {
            return schedules.isEmpty() && remove.isEmpty() && !removeScheduled;
        }
    }

    private HeadlessSimService() {}

    /**
     * SERVER THREAD: snapshot the graph's live trains as-is (no phantom, no overrides).
     * {@code startDayTime} null = "now"; {@code observedSpeedFloors} restores powered
     * stats for trains whose fuel counter happens to read 0 at snapshot time.
     */
    public static Prepared prepare(ServerLevel level, TrackGraph trackGraph, SimTopology topology,
                                   SimGraph simGraph, int horizonHours, Long startDayTime,
                                   java.util.Map<java.util.UUID, Double> observedSpeedFloors) {
        return prepare(level, trackGraph, topology, simGraph, horizonHours, startDayTime,
                observedSpeedFloors, PlannerInput.NONE, null);
    }

    /**
     * SERVER THREAD: the planner variant. Removals run first, then the timetable clear
     * (if asked), then the assignments — so an assigned train always survives, whether it
     * was running a schedule or standing idle. Assignments that cannot be applied
     * (unknown train, uncompilable schedule) are reported into {@code overrideIssues}
     * and the train keeps its snapshotted behavior.
     */
    public static Prepared prepare(ServerLevel level, TrackGraph trackGraph, SimTopology topology,
                                   SimGraph simGraph, int horizonHours, Long startDayTime,
                                   java.util.Map<java.util.UUID, Double> observedSpeedFloors,
                                   PlannerInput planner, List<OverrideIssue> overrideIssues) {
        NetworkSnapshotter.Snapshot snapshot =
                NetworkSnapshotter.snapshot(level, trackGraph, topology, observedSpeedFloors);
        List<SimTrainSpec> specs = new ArrayList<>(snapshot.specs());
        List<NetworkSnapshotter.Excluded> excluded = new ArrayList<>(snapshot.excluded());
        List<String> removed = new ArrayList<>();
        if (!planner.empty()) {
            java.util.Set<String> spared = idStrings(planner.keep());
            for (java.util.UUID id : planner.schedules().keySet()) spared.add(id.toString());
            java.util.Set<String> dropIds = idStrings(planner.remove());
            java.util.Set<String> droppedNames = new java.util.HashSet<>();
            specs.removeIf(spec -> {
                boolean drop = dropIds.contains(spec.id)
                        // clearing the timetable takes out scheduled traffic only:
                        // an obstacle is scenery the plan still has to live with
                        || (planner.removeScheduled() && spec.program != null
                            && !spared.contains(spec.id));
                if (drop) {
                    droppedNames.add(spec.name);
                    removed.add(spec.name);
                }
                return drop;
            });
            excluded.removeIf(line -> droppedNames.contains(line.trainName()));
        }
        if (!planner.schedules().isEmpty())
            applyOverrides(specs, excluded, planner.schedules(), overrideIssues);
        boolean daylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        SimClock clock = new SimClock(startDayTime != null ? startDayTime : level.getDayTime(),
                daylight ? 1 : 0);
        return new Prepared(simGraph, List.copyOf(specs), clock,
                (long) horizonHours * TICKS_PER_HOUR, level.getGameTime(), List.copyOf(excluded),
                net.Dispatcher.compat.CrnCompat.departureSeeds(), List.copyOf(removed));
    }

    private static java.util.Set<String> idStrings(java.util.Set<java.util.UUID> ids) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (java.util.UUID id : ids) out.add(id.toString());
        return out;
    }

    /**
     * Replaces each overridden train's program with the compiled override, keeping its
     * captured physicals and Tramways zone state, with fresh schedule progress from entry 0.
     * This is exactly the "assign preset to an idle train" upgrade: an obstacle spec
     * (paused / no schedule) becomes a scheduled one.
     */
    private static void applyOverrides(List<SimTrainSpec> specs,
                                       List<NetworkSnapshotter.Excluded> excluded,
                                       java.util.Map<java.util.UUID, net.minecraft.nbt.CompoundTag> overrides,
                                       List<OverrideIssue> issues) {
        java.util.Map<String, Integer> indexById = new java.util.HashMap<>();
        for (int i = 0; i < specs.size(); i++)
            indexById.put(specs.get(i).id, i);
        for (java.util.Map.Entry<java.util.UUID, net.minecraft.nbt.CompoundTag> override
                : new java.util.TreeMap<>(overrides).entrySet()) {
            Integer index = indexById.get(override.getKey().toString());
            if (index == null) {
                report(issues, override.getKey().toString(),
                        "dispatcher.sim.exclude.unmappable", "train not on this network");
                continue;
            }
            SimTrainSpec old = specs.get(index);
            com.simibubi.create.content.trains.schedule.Schedule schedule;
            try {
                schedule = com.simibubi.create.content.trains.schedule.Schedule
                        .fromTag(override.getValue());
            } catch (Exception e) {
                report(issues, old.name, "dispatcher.sim.refuse.empty_schedule", "");
                continue;
            }
            if (schedule.entries.isEmpty()) {
                report(issues, old.name, "dispatcher.sim.refuse.empty_schedule", "");
                continue;
            }
            ScheduleCompiler.CompileResult compiled = ScheduleCompiler.compile(schedule);
            if (!compiled.clean()) {
                for (ScheduleCompiler.Problem problem : compiled.problems())
                    report(issues, old.name, problem.translationKey(), problem.detail());
                continue;
            }
            SimTrainSpec fresh = new SimTrainSpec(old.id, old.name, old.length, old.acceleration,
                    old.topSpeed, old.turnSpeed, old.initialThrottle, compiled.program(),
                    old.headEdge, old.headOffset);
            fresh.canReverse = old.canReverse;
            fresh.initialSpeed = old.initialSpeed;
            fresh.primaryLimit = old.primaryLimit;
            fresh.initialStoredPermanent = old.initialStoredPermanent;
            fresh.liveAnchored = !NetworkSnapshotter.hasTimeAnchor(compiled.program());
            fresh.notices.addAll(compiled.notices());
            specs.set(index, fresh);
            // No longer an obstacle — drop its exclusion row.
            String name = old.name;
            excluded.removeIf(line -> line.trainName().equals(name));
        }
    }

    private static void report(List<OverrideIssue> issues, String trainName,
                               String translationKey, String detail) {
        if (issues != null)
            issues.add(new OverrideIssue(trainName, translationKey, detail));
    }

    /** Any thread. {@code maxWallMillis} ≤ 0 = uncapped (fully deterministic). */
    public static SimResult run(Prepared prepared, int sampleStride, long maxWallMillis,
                                long waitConflictTicks, long headwayConflictTicks) {
        return run(prepared, sampleStride, maxWallMillis, waitConflictTicks,
                headwayConflictTicks, null);
    }

    /** Any thread; {@code progress} is polled periodically and may abort the run. */
    public static SimResult run(Prepared prepared, int sampleStride, long maxWallMillis,
                                long waitConflictTicks, long headwayConflictTicks,
                                SimEngine.Progress progress) {
        SimEngine engine = new SimEngine(prepared.graph(), new ArrayList<>(prepared.specs()),
                prepared.clock(), prepared.horizonTicks(), sampleStride, maxWallMillis,
                waitConflictTicks, headwayConflictTicks);
        engine.setProgress(progress);
        seedEngine(engine, prepared.departureSeeds(), prepared.startGameTick());
        return engine.run();
    }

    /**
     * Feeds CRN's real departure history into a fresh engine, converting
     * absolute game ticks to engine-relative (0 = "never" in CRN's ledger).
     */
    public static void seedEngine(SimEngine engine,
                                  List<net.Dispatcher.compat.CrnCompat.DepartureSeed> seeds,
                                  long baseTick) {
        for (net.Dispatcher.compat.CrnCompat.DepartureSeed seed : seeds)
            engine.seedDeparture(seed.station(),
                    relative(seed.lastAny(), baseTick),
                    relativeMap(seed.byLine(), baseTick),
                    relativeMap(seed.byCategory(), baseTick),
                    relativeMap(seed.byName(), baseTick));
    }

    private static long relative(long absoluteTick, long baseTick) {
        return absoluteTick <= 0 ? Long.MIN_VALUE : absoluteTick - baseTick;
    }

    private static java.util.Map<String, Long> relativeMap(java.util.Map<String, Long> map,
                                                           long baseTick) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        map.forEach((k, v) -> {
            long tick = relative(v, baseTick);
            if (tick != Long.MIN_VALUE)
                out.put(k, tick);
        });
        return out;
    }
}
