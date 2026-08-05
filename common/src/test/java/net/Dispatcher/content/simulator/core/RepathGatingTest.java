package net.Dispatcher.content.simulator.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Create only re-navigates a signal-held train at CHAIN signals (every 100
 * waiting ticks, {@code Train.updateNavigationTarget}); a train pinned at a
 * plain ENTRY signal keeps its route forever. Also exercises forced-red
 * (redstone-held) signals: they stop trains regardless of occupancy and cost
 * REDSTONE_RED_SIGNAL in pathfinding.
 *
 * <p>Layout (+X): Start(50) — n1(200, signal under test) —short— n2(400,
 * forced-red ENTRY) — n3(600, ENTRY) — End(650) — n4(800), plus a 1200-block
 * passing loop n1→n3. Train B drives up the short track and parks forever at
 * the forced-red signal, blocking the short route; its growing waiting
 * penalty eventually makes the loop cheaper for train A.
 */
class RepathGatingTest {

    private SimResult run(SimEdge.Signal signalAtN1) {
        LineFixture line = new LineFixture().nodes(0, 200, 400, 600, 800);
        line.forwardSignal(1, signalAtN1);
        line.forwardSignal(2, SimEdge.Signal.ENTRY);
        line.forwardSignal(3, SimEdge.Signal.ENTRY);
        line.station("Start", 50);
        line.station("Mid", 250);
        line.station("End", 650);
        SimGraph graph = line.build();
        // Redstone holds the signal at n2 red: B stops there with an empty
        // section ahead and never repaths (entry signal).
        graph.edge(line.forwardEdgeAt(450)).entryForcedRed = true;
        // Passing loop n1→n3. Its ends sit on signal-boundary nodes, so the
        // loop needs its own signal heads to be enterable (one-way rule).
        // Long enough that the short route wins at dispatch (even with B's
        // tail still covering Mid, +350) and only B's growing waiting
        // penalty (up to +1000) makes the loop worth taking later.
        SimVec plusX = new SimVec(1, 0, 0);
        SimVec minusX = new SimVec(-1, 0, 0);
        int loopId = graph.edges.size();
        graph.edges.add(new SimEdge(loopId, 1, 3, loopId + 1, 1600, SimEdge.Signal.ENTRY, 0,
                new double[0][], plusX, plusX, false));
        graph.edges.add(new SimEdge(loopId + 1, 3, 1, loopId, 1600, SimEdge.Signal.ENTRY, 0,
                new double[0][], minusX, minusX, false));
        graph.computeDerived();

        SimTrainSpec trainA = LineFixture.train("A", line, 50,
                LineFixture.program(false, LineFixture.destination("End", new SimCondition.Delay(50))));
        SimTrainSpec trainB = LineFixture.train("B", line, 250,
                LineFixture.program(false, LineFixture.destination("End", new SimCondition.Delay(50))));
        return LineFixture.engine(graph, List.of(trainA, trainB), 20000).run();
    }

    @Test
    void forcedRedHoldsTrainOnEmptySection() {
        SimResult result = run(SimEdge.Signal.ENTRY);
        assertTrue(result.trains.get(1).visits.isEmpty(),
                "B must never pass the forced-red signal");
        assertEquals("MOVING", result.trains.get(1).endState,
                "B ends held at the red, still in transit");
    }

    @Test
    void entrySignalWaitNeverRepaths() {
        SimResult result = run(SimEdge.Signal.ENTRY);
        assertTrue(result.trains.get(0).visits.isEmpty(),
                "A must keep its blocked route forever at a plain entry signal, "
                        + "matching Create's no-repath behavior");
        assertEquals("MOVING", result.trains.get(0).endState);
    }

    @Test
    void chainSignalWaitRepathsAroundTheBlock() {
        SimResult result = run(SimEdge.Signal.CHAIN);
        boolean arrived = result.trains.get(0).visits.stream()
                .anyMatch(visit -> "End".equals(visit.stationName()));
        assertTrue(arrived, "A must eventually divert over the loop when held at a chain signal");
    }

    @Test
    void snapshotCooldownDelaysTheFirstDispatch() {
        LineFixture line = new LineFixture().nodes(0, 400);
        line.station("Start", 50);
        line.station("End", 350);
        SimGraph graph = line.build();

        SimTrainSpec eager = LineFixture.train("eager", line, 50,
                LineFixture.program(false, LineFixture.destination("End", new SimCondition.Delay(50))));
        SimResult without = LineFixture.engine(graph, List.of(eager), 2000).run();

        SimTrainSpec delayed = LineFixture.train("delayed", line, 50,
                LineFixture.program(false, LineFixture.destination("End", new SimCondition.Delay(50))));
        delayed.startCooldown = 40;
        SimResult with = LineFixture.engine(graph, List.of(delayed), 2000).run();

        long eagerArrival = without.trains.get(0).visits.get(0).arrivalTick();
        long delayedArrival = with.trains.get(0).visits.get(0).arrivalTick();
        assertEquals(eagerArrival + 40, delayedArrival,
                "a snapshotted retry cooldown defers dispatch tick-for-tick");
    }
}
