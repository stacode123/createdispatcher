package net.Dispatcher.content.simulator;

import net.Dispatcher.content.graph.v2.RailCrossing;
import net.Dispatcher.content.graph.v2.RailEdge;
import net.Dispatcher.content.graph.v2.RailGraph;
import net.Dispatcher.content.graph.v2.RailNode;
import net.Dispatcher.content.graph.v2.RailStation;
import net.Dispatcher.content.simulator.core.SimEdge;
import net.Dispatcher.content.simulator.core.SimGraph;
import net.Dispatcher.content.simulator.core.SimNode;
import net.Dispatcher.content.simulator.core.SimVec;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Adapts a translated graph + its server-only topology into the MC-free sim model. */
public class SimGraphBuilder {

    /** km/h → blocks/tick. */
    public static double kmhToBlocksPerTick(double kmh) {
        return kmh / 3.6 / 20;
    }

    public static SimGraph build(RailGraph rail, SimTopology topology) {
        SimGraph graph = new SimGraph();
        for (RailNode node : rail.nodes)
            graph.nodes.add(new SimNode(node.id, node.type == RailNode.NodeType.SIGNAL,
                    node.dimension, toSimVec(node.position)));

        for (RailEdge edge : rail.edges) {
            // Turn spans come from the topology's Navigation-exact list (every
            // bezier except straight mild slopes), NOT the display-only
            // curvature capProfile — Create slows for gentle curves, steep
            // climbs and flat straight beziers that carry no curvature cap.
            List<double[]> spans = topology.turnRanges(edge.id);
            double[][] turnRanges = new double[spans.size()][];
            for (int i = 0; i < spans.size(); i++)
                turnRanges[i] = new double[] { spans.get(i)[0], spans.get(i)[1] };
            SimEdge simEdge = new SimEdge(edge.id, edge.from, edge.to, edge.oppositeId, edge.length,
                    SimEdge.Signal.values()[edge.entrySignal.toByte()],
                    kmhToBlocksPerTick(topology.signCapKmh(edge.id)), turnRanges,
                    toSimVec(topology.entryTangent(edge.id).normalize()),
                    toSimVec(topology.exitTangent(edge.id).normalize()),
                    edge.interDimensional);
            simEdge.entryForcedRed = topology.entryForcedRed(edge.id);
            for (RailStation station : edge.stations)
                simEdge.stations.add(new SimEdge.Station(station.stationId(), station.name(),
                        station.offset(), station.approachable()));
            for (RailCrossing crossing : edge.crossings)
                simEdge.crossings.add(new SimEdge.Crossing(crossing.crossingId(), crossing.offset()));
            // Stable sort keeps the translator's TEMPORARY/RELEASE/PERMANENT
            // ordering for events at the same offset (Tramways tick order).
            simEdge.signEvents.addAll(topology.signEvents(edge.id));
            simEdge.signEvents.sort(java.util.Comparator.comparingDouble(SimEdge.SignEvent::offset));
            graph.edges.add(simEdge);
        }
        graph.computeDerived();
        return graph;
    }

    private static SimVec toSimVec(Vec3 vec) {
        return new SimVec(vec.x, vec.y, vec.z);
    }
}
