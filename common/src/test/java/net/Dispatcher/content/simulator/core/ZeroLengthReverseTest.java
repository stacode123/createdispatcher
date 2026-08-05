package net.Dispatcher.content.simulator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A shuttle between two terminus platforms — the shape of an ordinary
 * end-to-end passenger line, where each platform is a dead end and the only
 * way out is to turn around.
 *
 * <p>The zero-length case is not exotic: Create measures occupancy between the
 * leading and trailing travelling points, so a single-carriage, single-bogey
 * train is exactly 0 blocks long.
 */
class ZeroLengthReverseTest {

    /**
     * Two nodes, one bidirectional track. Platform A sits at the node-0 buffer
     * stop and is approachable only while travelling −X; platform B sits at the
     * node-1 buffer stop, approachable only while travelling +X.
     */
    private static SimGraph shuttleLine(double length) {
        SimGraph graph = new SimGraph();
        graph.nodes.add(new SimNode(0, false, 0, new SimVec(0, 0, 0)));
        graph.nodes.add(new SimNode(1, false, 0, new SimVec(length, 0, 0)));
        SimVec plusX = new SimVec(1, 0, 0);
        SimVec minusX = new SimVec(-1, 0, 0);
        SimEdge forward = new SimEdge(0, 0, 1, 1, length, SimEdge.Signal.NONE, 0,
                new double[0][], plusX, plusX, false);
        SimEdge backward = new SimEdge(1, 1, 0, 0, length, SimEdge.Signal.NONE, 0,
                new double[0][], minusX, minusX, false);
        UUID a = UUID.nameUUIDFromBytes("A".getBytes());
        UUID b = UUID.nameUUIDFromBytes("B".getBytes());
        // B is reached at the end of the forward edge, A at the end of the backward one.
        forward.stations.add(new SimEdge.Station(a, "A", 0, false));
        forward.stations.add(new SimEdge.Station(b, "B", length, true));
        backward.stations.add(new SimEdge.Station(b, "B", 0, false));
        backward.stations.add(new SimEdge.Station(a, "A", length, true));
        graph.edges.add(forward);
        graph.edges.add(backward);
        graph.computeDerived();
        return graph;
    }

    private static SimResult shuttle(double trainLength) {
        SimGraph graph = shuttleLine(1000);
        SimProgram program = LineFixture.program(true,
                LineFixture.destination("B", new SimCondition.Delay(20)),
                LineFixture.destination("A", new SimCondition.Delay(20)));
        // Standing at A, facing out along the forward edge — where a train that
        // just arrived from B and turned around would be.
        SimTrainSpec spec = new SimTrainSpec("T", "T", trainLength, 0.01, 1.0, 0.5, 1.0,
                program, 0, 0);
        spec.canReverse = true;
        return LineFixture.engine(graph, List.of(spec), 20_000).run();
    }

    private static int visits(SimResult result) {
        return result.trains.get(0).visits.size();
    }

    @Test
    void normalTrainShuttlesBetweenTerminals() {
        assertTrue(visits(shuttle(50)) >= 4,
                "a 50-block train should reverse at each terminus and keep shuttling, got "
                        + visits(shuttle(50)) + " visits");
    }

    @Test
    void zeroLengthTrainShuttlesToo() {
        SimResult result = shuttle(0);
        assertTrue(visits(result) >= 4,
                "a zero-length train reverses like any other, got " + visits(result) + " visits");
    }
}
