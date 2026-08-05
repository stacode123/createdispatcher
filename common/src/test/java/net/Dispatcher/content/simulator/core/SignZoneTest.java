package net.Dispatcher.content.simulator.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tramways sign zones as per-train throttle state: crossing a sign event
 * mutates the train's persistent throttle (like Tramways rewriting
 * {@code Train.throttle}), so a zone survives junctions and lasts until
 * another sign supersedes or releases it.
 */
class SignZoneTest {

    private static double maxSpeedBeyond(LineFixture line, SimResult.TrainResult train, double x) {
        double max = 0;
        for (SimResult.Sample sample : train.samples)
            if (line.xOf(sample) >= x)
                max = Math.max(max, sample.speed());
        return max;
    }

    private static double maxSpeedBetween(LineFixture line, SimResult.TrainResult train,
                                          double fromX, double toX) {
        double max = 0;
        for (SimResult.Sample sample : train.samples) {
            double x = line.xOf(sample);
            if (x >= fromX && x <= toX)
                max = Math.max(max, sample.speed());
        }
        return max;
    }

    /** A permanent speed sign's zone holds across a junction node. */
    @Test
    void permanentSignZonePersistsThroughAJunction() {
        LineFixture line = new LineFixture().nodes(0, 1000, 2000);
        line.station("A", 10);
        line.station("B", 1900);
        SimGraph graph = line.build();
        // Parallel long way between nodes 1 and 2 turns node 1 into a real
        // junction — the zone must carry across it on per-train state.
        LineFixture.addLoop(graph, 1, 2, 1500);
        graph.edge(0).signEvents.add(
                new SimEdge.SignEvent(500, SimEdge.SignEvent.Kind.PERMANENT, 0.5));

        SimProgram program = LineFixture.program(false,
                LineFixture.destination("B", new SimCondition.Delay(50)));
        SimTrainSpec train = LineFixture.train("t1", line, 50, program);
        SimResult result = LineFixture.engine(graph, List.of(train), 8000).run();

        SimResult.TrainResult trainResult = result.trains.get(0);
        assertFalse(trainResult.visits.isEmpty(), "train never reached B");
        // 37.5 blocks of deceleration after crossing at full speed, then the
        // zone caps the rest of the trip — including past the junction at 1000.
        assertTrue(maxSpeedBeyond(line, trainResult, 600) <= 0.55,
                "zone did not persist: " + maxSpeedBeyond(line, trainResult, 600));
        assertTrue(maxSpeedBetween(line, trainResult, 1050, 1850) <= 0.55,
                "zone lost at the junction");
    }

    /** A temporary zone slows the train until the end sign restores throttle. */
    @Test
    void temporaryZoneEndsAtReleaseSign() {
        LineFixture line = new LineFixture().nodes(0, 1000, 2000);
        line.station("A", 10);
        line.station("B", 1900);
        SimGraph graph = line.build();
        graph.edge(0).signEvents.add(
                new SimEdge.SignEvent(300, SimEdge.SignEvent.Kind.TEMPORARY, 0.3));
        graph.edge(2).signEvents.add(
                new SimEdge.SignEvent(200, SimEdge.SignEvent.Kind.RELEASE, 0));

        SimProgram program = LineFixture.program(false,
                LineFixture.destination("B", new SimCondition.Delay(50)));
        SimTrainSpec train = LineFixture.train("t1", line, 50, program);
        SimResult result = LineFixture.engine(graph, List.of(train), 10000).run();

        SimResult.TrainResult trainResult = result.trains.get(0);
        assertFalse(trainResult.visits.isEmpty(), "train never reached B");
        assertTrue(maxSpeedBetween(line, trainResult, 400, 1150) <= 0.35,
                "temporary zone not enforced: " + maxSpeedBetween(line, trainResult, 400, 1150));
        // Release at x=1200 restores the stashed pre-zone throttle (1.0).
        assertTrue(maxSpeedBetween(line, trainResult, 1300, 1800) >= 0.9,
                "release sign did not restore speed: "
                        + maxSpeedBetween(line, trainResult, 1300, 1800));
    }

    /** A snapshot taken inside a live temporary zone: the seeded stashed
     *  throttle comes back at the release sign. */
    @Test
    void seededStoredPermanentIsRestoredByRelease() {
        LineFixture line = new LineFixture().nodes(0, 1000, 2000);
        line.station("A", 10);
        line.station("B", 1900);
        SimGraph graph = line.build();
        graph.edge(0).signEvents.add(
                new SimEdge.SignEvent(500, SimEdge.SignEvent.Kind.RELEASE, 0));

        SimProgram program = LineFixture.program(false,
                LineFixture.destination("B", new SimCondition.Delay(50)));
        SimTrainSpec train = new SimTrainSpec("t1", "t1", 16, 0.01, 1.0, 0.5, 0.3, program,
                line.forwardEdgeAt(50), line.offsetOn(line.forwardEdgeAt(50), 50));
        train.canReverse = false;
        train.initialStoredPermanent = 1.0;
        SimResult result = LineFixture.engine(graph, List.of(train), 10000).run();

        SimResult.TrainResult trainResult = result.trains.get(0);
        assertFalse(trainResult.visits.isEmpty(), "train never reached B");
        assertTrue(maxSpeedBetween(line, trainResult, 100, 480) <= 0.35,
                "seeded zone throttle ignored");
        assertTrue(maxSpeedBetween(line, trainResult, 700, 1800) >= 0.9,
                "stored permanent not restored: "
                        + maxSpeedBetween(line, trainResult, 700, 1800));
    }

    /**
     * Tramways' set-primary-limit schedule instruction arms the persistent
     * clamp: a later 100% speed sign restores to the line speed, not to full.
     */
    @Test
    void primaryLimitInstructionClampsLaterFullSpeedSign() {
        LineFixture line = new LineFixture().nodes(0, 2000);
        line.station("A", 10);
        line.station("B", 1900);
        SimGraph graph = line.build();
        graph.edge(0).signEvents.add(
                new SimEdge.SignEvent(600, SimEdge.SignEvent.Kind.PERMANENT, 1.0));

        SimProgram program = LineFixture.program(false,
                SimProgram.Entry.primaryLimit(0.3),
                LineFixture.destination("B", new SimCondition.Delay(50)));
        SimTrainSpec train = LineFixture.train("t1", line, 50, program);
        SimResult result = LineFixture.engine(graph, List.of(train), 10000).run();

        SimResult.TrainResult trainResult = result.trains.get(0);
        assertFalse(trainResult.visits.isEmpty(), "train never reached B");
        assertTrue(maxSpeedBetween(line, trainResult, 100, 1850) <= 0.35,
                "100% sign escaped the primary limit: "
                        + maxSpeedBetween(line, trainResult, 100, 1850));
    }

    /**
     * Real trains brake IN ANTICIPATION of a lower speed sign (Tramways
     * registers scan-distance signs): the train must cross the sign near the
     * zone speed instead of sailing in at full speed and decaying inside.
     */
    @Test
    void trainBrakesBeforeALowerSpeedSign() {
        LineFixture line = new LineFixture().nodes(0, 2000);
        line.station("A", 10);
        line.station("B", 1900);
        SimGraph graph = line.build();
        graph.edge(0).signEvents.add(
                new SimEdge.SignEvent(1000, SimEdge.SignEvent.Kind.PERMANENT, 0.3));

        SimProgram program = LineFixture.program(false,
                LineFixture.destination("B", new SimCondition.Delay(50)));
        SimTrainSpec train = LineFixture.train("t1", line, 50, program);
        SimResult result = LineFixture.engine(graph, List.of(train), 10000).run();

        SimResult.TrainResult trainResult = result.trains.get(0);
        assertFalse(trainResult.visits.isEmpty(), "train never reached B");
        // The train must cross the sign AT zone speed: without anticipation
        // it would pass at ~1.0 and still be decaying through ~0.9-0.45 in
        // the 60 blocks past it.
        assertTrue(maxSpeedBetween(line, trainResult, 1000, 1060) <= 0.4,
                "no anticipatory braking: "
                        + maxSpeedBetween(line, trainResult, 1000, 1060));
        // And it must have been fast before braking began (starts ~954).
        assertTrue(maxSpeedBetween(line, trainResult, 400, 900) >= 0.9,
                "braked far too early");
    }

    /** Sign throttles are clamped by Tramways' per-train primary limit. */
    @Test
    void primaryLimitClampsSignThrottle() {
        LineFixture line = new LineFixture().nodes(0, 2000);
        line.station("A", 10);
        line.station("B", 1900);
        SimGraph graph = line.build();
        graph.edge(0).signEvents.add(
                new SimEdge.SignEvent(300, SimEdge.SignEvent.Kind.PERMANENT, 0.9));

        SimProgram program = LineFixture.program(false,
                LineFixture.destination("B", new SimCondition.Delay(50)));
        SimTrainSpec train = LineFixture.train("t1", line, 50, program);
        train.primaryLimit = 0.4;
        SimResult result = LineFixture.engine(graph, List.of(train), 10000).run();

        SimResult.TrainResult trainResult = result.trains.get(0);
        assertFalse(trainResult.visits.isEmpty(), "train never reached B");
        assertTrue(maxSpeedBetween(line, trainResult, 450, 1850) <= 0.45,
                "primary limit not applied: "
                        + maxSpeedBetween(line, trainResult, 450, 1850));
    }
}
