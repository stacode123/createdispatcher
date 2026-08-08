package net.Dispatcher.content.simulator;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import net.Dispatcher.compat.TramwaysCompat;
import net.Dispatcher.content.simulator.core.SimCondition;
import net.Dispatcher.content.simulator.core.SimProgram;
import net.Dispatcher.content.simulator.core.SimTrainSpec;
import net.Dispatcher.content.simulator.core.TrainState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Captures every live train on one track graph as simulator input. Must run
 * on the server thread. Trains whose behavior a static projection cannot
 * predict (manual, paused, unsupported conditions, unmappable position)
 * become static obstacles and are reported with a reason.
 */
public class NetworkSnapshotter {

    public record Excluded(String trainName, String translationKey, String detail) {}

    public record Snapshot(List<SimTrainSpec> specs, List<Excluded> excluded) {}

    public static Snapshot snapshot(Level level, TrackGraph graph, SimTopology topology) {
        return snapshot(level, graph, topology, java.util.Map.of());
    }

    /**
     * {@code observedSpeedFloors}: recently observed |speed| per train id
     * (blocks/tick). Create's fueled stats are transient — mods power
     * scheduled trains by burning fuel while they move, so a train
     * snapshotted parked can read the unfueled (much lower) top speed and
     * the whole projection crawls. A train observed faster than its
     * snapshot cap is demonstrably powered and gets powered stats back.
     */
    public static Snapshot snapshot(Level level, TrackGraph graph, SimTopology topology,
                                    java.util.Map<java.util.UUID, Double> observedSpeedFloors) {
        List<Train> trains = new ArrayList<>();
        for (Train train : Create.RAILWAYS.sided(level).trains.values())
            if (train.graph != null && train.graph.id.equals(graph.id) && !train.carriages.isEmpty())
                trains.add(train);
        trains.sort(Comparator.comparing(train -> train.id.toString()));

        List<SimTrainSpec> specs = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (Train train : trains) {
            SimTrainSpec spec = snapshotTrain(train, topology, excluded, observedSpeedFloors);
            if (spec != null)
                specs.add(spec);
        }
        return new Snapshot(specs, excluded);
    }

    private static SimTrainSpec snapshotTrain(Train train, SimTopology topology, List<Excluded> excluded,
                                              java.util.Map<java.util.UUID, Double> observedSpeedFloors) {
        String name = train.name.getString();
        TravellingPoint head = train.carriages.get(0).getLeadingPoint();
        if (head.node1 == null || head.node2 == null || head.edge == null) {
            excluded.add(new Excluded(name, "dispatcher.sim.exclude.unmappable", ""));
            return null;
        }
        SimTopology.Location location =
                topology.locate(head.node1.getNetId(), head.node2.getNetId(), head.position);
        if (location == null) {
            excluded.add(new Excluded(name, "dispatcher.sim.exclude.unmappable", ""));
            return null;
        }

        // Occupancy runs between the leading and trailing travelling points —
        // exactly the sum of bogey and carriage spacings, no overhang pad.
        double length = 0;
        for (Carriage carriage : train.carriages)
            length += carriage.bogeySpacing;
        for (int spacing : train.carriageSpacing)
            length += spacing;

        ScheduleRuntime runtime = train.runtime;
        Schedule schedule = runtime.getSchedule();

        SimProgram program = null;
        List<String> notices = new ArrayList<>();
        String obstacleReason = null;
        String obstacleDetail = "";

        if (train.derailed) {
            obstacleReason = "dispatcher.sim.exclude.derailed";
        } else if (schedule == null) {
            obstacleReason = "dispatcher.sim.exclude.no_schedule";
        } else if (runtime.paused) {
            obstacleReason = runtime.completed
                    ? "dispatcher.sim.exclude.completed"
                    : "dispatcher.sim.exclude.paused";
        } else {
            ScheduleCompiler.CompileResult compiled = ScheduleCompiler.compile(schedule);
            if (!compiled.clean()) {
                ScheduleCompiler.Problem problem = compiled.problems().get(0);
                obstacleReason = problem.translationKey();
                obstacleDetail = problem.detail();
            } else {
                program = compiled.program();
                notices.addAll(compiled.notices());
                if (!hasTimeAnchor(program))
                    notices.add("dispatcher.sim.notice.snapshot_anchor");
            }
        }

        // The live train.throttle IS the train's real speed state: Create's
        // schedule sets it and Tramways sign zones rewrite it in place. The
        // engine replays both going forward (THROTTLE instructions as they
        // execute, sign crossings as SignEvents), so seeding the live value —
        // plus Tramways' stashed pre-zone throttle — is the faithful start.
        // Reconstructing the schedule throttle here would erase the zone the
        // train is currently inside.
        int entryCount = program != null ? program.entries.size() : 0;
        int startEntry = entryCount == 0 ? 0
                : Math.max(0, Math.min(runtime.currentEntry, entryCount - 1));
        double throttle = Math.max(0.05, Math.min(1, train.throttle));
        double topSpeed = train.maxSpeed();
        double turnSpeed = train.maxTurnSpeed();
        Double observed = observedSpeedFloors.get(train.id);
        if (observed != null && observed > topSpeed + 1e-4) {
            // Seen faster than the snapshot cap → the fuel counter caught us at
            // zero between burns. Powered turning stats come back with it.
            topSpeed = Math.max(topSpeed, observed);
            turnSpeed = Math.max(turnSpeed, com.simibubi.create.infrastructure.config.AllConfigs
                    .server().trains.poweredTrainTurningTopSpeed.getF() / 20);
        }
        SimTrainSpec spec = new SimTrainSpec(train.id.toString(), name, length,
                train.acceleration(), topSpeed, turnSpeed,
                throttle, program, location.edgeId(), location.offset());
        // Create only paths backward with a backward conductor
        // (Navigation.findPathTo's canDriveBackward).
        spec.canReverse = train.doubleEnded && train.hasBackwardConductor();
        spec.manual = train.manualTick;
        spec.startCooldown = Math.max(0,
                ((net.Dispatcher.mixin.mixinaccesors.ScheduleRuntimeAccessor) runtime).getCooldown());
        spec.initialSpeed = Math.min(Math.abs(train.speed), topSpeed);
        spec.primaryLimit = Math.max(0.05, Math.min(1, TramwaysCompat.getPrimaryLimit(train)));
        Double storedPermanent = TramwaysCompat.getStoredPermanent(train);
        if (storedPermanent != null)
            spec.initialStoredPermanent = Math.max(0.05, Math.min(1, storedPermanent));
        spec.notices.addAll(notices);
        spec.liveAnchored = program == null || !hasTimeAnchor(program);

        if (program == null) {
            excluded.add(new Excluded(name, obstacleReason, obstacleDetail));
            spec.notices.add("dispatcher.sim.notice.static_obstacle");
            return spec;
        }

        if (entryCount == 0) {
            excluded.add(new Excluded(name, "dispatcher.sim.exclude.no_schedule", ""));
            return spec;
        }
        spec.startEntry = startEntry;
        if (runtime.state == ScheduleRuntime.State.POST_TRANSIT) {
            spec.startWaiting = true;
            int columns = runtime.conditionProgress.size();
            spec.startColumnProgress = new int[columns];
            spec.startColumnElapsed = new int[columns];
            spec.startColumnDepartAt = new long[columns];
            for (int i = 0; i < columns; i++) {
                spec.startColumnProgress[i] = runtime.conditionProgress.get(i);
                CompoundTag context = i < runtime.conditionContext.size()
                        ? runtime.conditionContext.get(i) : new CompoundTag();
                spec.startColumnElapsed[i] = context.getInt("Time");
                // Realism's booked departure slot, read by NBT key like every
                // other optional-mod integration here — absolute day time,
                // the same frame SimClock uses, so no conversion needed.
                spec.startColumnDepartAt[i] = context.contains("RealismDepartAt", Tag.TAG_LONG)
                        ? context.getLong("RealismDepartAt")
                        : TrainState.UNBOOKED;
            }
        } else if (train.navigation != null && train.navigation.destination != null) {
            spec.resumeDestination = train.navigation.destination.id;
        }
        return spec;
    }

    /** Whether the program contains a wall-clock anchor (time-of-day condition). */
    static boolean hasTimeAnchor(SimProgram program) {
        for (SimProgram.Entry entry : program.entries)
            for (List<SimCondition> column : entry.columns)
                for (SimCondition condition : column)
                    if (condition instanceof SimCondition.TimeOfDay
                            || condition instanceof SimCondition.TimeOfDayRealistic)
                        return true;
        return false;
    }
}
