package net.Dispatcher.content.simulator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The W5 planner polls the engine through {@link SimEngine.Progress}: a hook
 * that always continues must not change results at all, and a hook returning
 * false must abort the run promptly with {@code truncated} set — that is how
 * a browser cancels a running web sim.
 */
class ProgressHookTest {

    private static SimResult run(long horizon, boolean cyclic, SimEngine.Progress progress) {
        LineFixture line = new LineFixture().nodes(0, 1000);
        line.station("A", 10);
        line.station("B", 900);
        SimGraph graph = line.build();
        SimProgram program = cyclic
                ? LineFixture.program(true,
                        LineFixture.destination("B", new SimCondition.Delay(100)),
                        LineFixture.destination("A", new SimCondition.Delay(100)))
                : LineFixture.program(false,
                        LineFixture.destination("B", new SimCondition.Delay(100)));
        SimTrainSpec train = LineFixture.train("t1", line, 10, program);
        SimEngine engine = LineFixture.engine(graph, List.of(train), horizon);
        engine.setProgress(progress);
        return engine.run();
    }

    @Test
    void alwaysContinueHookLeavesResultsIdentical() {
        AtomicLong calls = new AtomicLong();
        SimResult plain = run(5000, true, null);
        SimResult hooked = run(5000, true, tick -> {
            calls.incrementAndGet();
            return true;
        });
        assertTrue(calls.get() > 0, "hook was never polled");
        assertEquals(plain.ticksSimulated, hooked.ticksSimulated);
        assertFalse(hooked.truncated);
        assertEquals(plain.trains.get(0).visits, hooked.trains.get(0).visits);
        assertEquals(plain.trains.get(0).samples, hooked.trains.get(0).samples);
    }

    @Test
    void refusingHookAbortsAndMarksTruncated() {
        // A cyclic shuttle never finishes on its own; the run must stop at the
        // first refusal (poll cadence 2048 → the poll at tick 4096 aborts).
        SimResult aborted = run(200_000, true, tick -> tick < 4096);
        assertTrue(aborted.truncated, "aborted run must be marked truncated");
        assertTrue(aborted.ticksSimulated <= 4096, "ran to " + aborted.ticksSimulated);
        assertTrue(aborted.ticksSimulated >= 2048, "aborted too early: " + aborted.ticksSimulated);
    }
}
