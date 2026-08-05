package net.Dispatcher.content.simulator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Separation gates against pre-run seeded departure history (the real
 * world's CRN ledger): a train must wait out the headway measured from a
 * departure that happened BEFORE the simulation started.
 */
class SeparationSeedTest {

    private SimResult run(LineFixture line, SimGraph graph, boolean seeded) {
        SimProgram.Entry first = LineFixture.destination("A",
                new SimCondition.Separation(1000, SimCondition.TrainFilter.SAME_LINE, ""));
        first.lineToken = "L1";
        SimProgram program = LineFixture.program(false, first,
                LineFixture.destination("B", new SimCondition.Delay(20)));
        SimTrainSpec train = LineFixture.train("t1", line, 10, program);
        SimEngine engine = LineFixture.engine(graph, List.of(train), 5000);
        if (seeded)
            engine.seedDeparture("A", -200, Map.of("L1", -200L), Map.of(), Map.of());
        return engine.run();
    }

    /**
     * A blank-station-filter gate on a prioritized destination must consult
     * ALL sibling-platform filters (CRN maxes over getFilters()) — a
     * departure recorded at the SECOND platform still arms the headway.
     */
    @Test
    void gateSeesDeparturesAtSiblingPlatforms() {
        LineFixture line = new LineFixture().nodes(0, 1000);
        line.station("A 1", 10);
        line.station("A 2", 30);
        line.station("B", 900);
        SimGraph graph = line.build();

        SimProgram.Entry first = SimProgram.Entry.destinationPrioritized(
                List.of("A 1", "A 2"), false);
        first.lineToken = "L1";
        first.columns.add(new java.util.ArrayList<>(List.of(
                new SimCondition.Separation(1000, SimCondition.TrainFilter.SAME_LINE, ""))));
        SimProgram program = LineFixture.program(false, first,
                LineFixture.destination("B", new SimCondition.Delay(20)));
        SimTrainSpec train = LineFixture.train("t1", line, 10, program);

        SimEngine engine = LineFixture.engine(graph, List.of(train), 5000);
        // Ledger departure at the SECOND platform of the pair.
        engine.seedDeparture("A 2", -200, Map.of("L1", -200L), Map.of(), Map.of());
        SimResult result = engine.run();

        SimResult.StationVisit held = result.trains.get(0).visits.get(0);
        assertTrue(held.departureTick() >= 790 && held.departureTick() <= 900,
                "sibling-platform departure must arm the gate: " + held.departureTick());
    }

    @Test
    void seededHistoryHoldsTheHeadway() {
        LineFixture line = new LineFixture().nodes(0, 1000);
        line.station("A", 10);
        line.station("B", 900);

        SimResult unseeded = run(line, line.build(), false);
        SimResult seeded = run(line, line.build(), true);

        SimResult.StationVisit fast = unseeded.trains.get(0).visits.get(0);
        SimResult.StationVisit held = seeded.trains.get(0).visits.get(0);
        assertFalse(fast.departureTick() > 700,
                "empty history should not hold the gate: " + fast.departureTick());
        // Last same-line departure at tick -200, headway 1000 → hold to ~800.
        assertTrue(held.departureTick() >= 790 && held.departureTick() <= 900,
                "seeded gate should hold to ~800: " + held.departureTick());
    }
}
