package net.Dispatcher.content.simulator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The multi-source pathfinder behind web corridor picking: one search seeded
 * from every platform of the start group. Regression for the field failure
 * where only the first-by-name platforms were tried — bus-stop platforms of a
 * huge hub group produced route_not_found, and a wrong-direction platform
 * produced an 8 km detour axis.
 */
class SimPathfinderMultiTest {

    /** One-way loop W→K (edge 0) / K→W (edge 1), a target on edge 0. */
    private static SimGraph loop() {
        SimGraph graph = new SimGraph();
        graph.nodes.add(new SimNode(0, false, 0, new SimVec(0, 0, 0)));
        graph.nodes.add(new SimNode(1, false, 0, new SimVec(1000, 0, 0)));
        SimVec plusX = new SimVec(1, 0, 0);
        graph.edges.add(new SimEdge(0, 0, 1, -1, 1000, SimEdge.Signal.NONE, 0,
                new double[0][], plusX, plusX, false));
        graph.edges.add(new SimEdge(1, 1, 0, -1, 1000, SimEdge.Signal.NONE, 0,
                new double[0][], plusX, plusX, false));
        graph.computeDerived();
        return graph;
    }

    private static SimGraph.StationTarget target(SimGraph graph, int edge, double offset) {
        return new SimGraph.StationTarget(edge, offset,
                UUID.nameUUIDFromBytes("target".getBytes()), "Target");
    }

    @Test
    void picksTheBestOfAllStarts() {
        SimGraph graph = loop();
        SimGraph.StationTarget target = target(graph, 0, 950);
        // Wrong-direction platform (edge 1) must lose to the direct one (edge 0).
        SimPathfinder.Path path = SimPathfinder.findMulti(graph,
                List.of(new SimPathfinder.Start(1, 60), new SimPathfinder.Start(0, 50)),
                List.of(target), SimPathfinder.Penalties.NONE);
        assertNotNull(path);
        assertEquals(900, path.distance(), 1e-6);
        assertEquals(0, path.edges()[0], "the winning start's edge leads the route");

        // And it matches the single-start search from that platform.
        SimPathfinder.Path single = SimPathfinder.find(graph, 0, 50, List.of(target),
                false, -1, 0, SimPathfinder.Penalties.NONE);
        assertNotNull(single);
        assertEquals(single.distance(), path.distance(), 1e-6);
        assertArrayEquals(single.edges(), path.edges());
    }

    @Test
    void wrongDirectionStartAloneStillRoutesTheLongWay() {
        SimGraph graph = loop();
        SimPathfinder.Path path = SimPathfinder.findMulti(graph,
                List.of(new SimPathfinder.Start(1, 60)),
                List.of(target(graph, 0, 950)), SimPathfinder.Penalties.NONE);
        assertNotNull(path);
        // rest of edge 1 (940) + 950 into edge 0
        assertEquals(1890, path.distance(), 1e-6);
        assertEquals(1, path.edges()[0]);
        assertEquals(0, path.edges()[path.edges().length - 1]);
    }

    /** A start on a disconnected stub is ignored, not fatal (the bus-stop case). */
    @Test
    void unroutableStartsDoNotPoisonTheSearch() {
        SimGraph graph = new SimGraph();
        graph.nodes.add(new SimNode(0, false, 0, new SimVec(0, 0, 0)));
        graph.nodes.add(new SimNode(1, false, 0, new SimVec(1000, 0, 0)));
        graph.nodes.add(new SimNode(2, false, 0, new SimVec(0, 0, 500)));
        graph.nodes.add(new SimNode(3, false, 0, new SimVec(200, 0, 500)));
        SimVec plusX = new SimVec(1, 0, 0);
        graph.edges.add(new SimEdge(0, 0, 1, -1, 1000, SimEdge.Signal.NONE, 0,
                new double[0][], plusX, plusX, false));
        // isolated stub: nothing connects
        graph.edges.add(new SimEdge(1, 2, 3, -1, 200, SimEdge.Signal.NONE, 0,
                new double[0][], plusX, plusX, false));
        graph.computeDerived();

        SimPathfinder.Path path = SimPathfinder.findMulti(graph,
                List.of(new SimPathfinder.Start(1, 50), new SimPathfinder.Start(0, 100)),
                List.of(target(graph, 0, 900)), SimPathfinder.Penalties.NONE);
        assertNotNull(path);
        assertEquals(800, path.distance(), 1e-6);
        assertEquals(0, path.edges()[0]);

        SimPathfinder.Path none = SimPathfinder.findMulti(graph,
                List.of(new SimPathfinder.Start(1, 50)),
                List.of(target(graph, 0, 900)), SimPathfinder.Penalties.NONE);
        assertNull(none, "only unroutable starts → no path");
    }
}
