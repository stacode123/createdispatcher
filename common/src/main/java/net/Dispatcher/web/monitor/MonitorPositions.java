package net.Dispatcher.web.monitor;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import net.Dispatcher.content.graph.v2.RailEdge;
import net.Dispatcher.content.graph.v2.RailGeometry;
import net.Dispatcher.content.simulator.SimTopology;
import net.Dispatcher.web.graph.WebGraphStore;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Best-effort world position of a train's head for notification anchors. */
final class MonitorPositions {
    record Pos(UUID graphId, double x, double z, String dim) {}

    private MonitorPositions() {}

    static Pos locate(WebGraphStore store, Train train) {
        if (train.carriages.isEmpty()) return null;
        TravellingPoint head = train.carriages.get(0).getLeadingPoint();
        if (head == null || head.node1 == null) return null;

        WebGraphStore.Entry entry = train.graph != null ? store.get(train.graph.id) : null;
        if (entry != null && !entry.tooLarge() && head.node2 != null) {
            SimTopology.Location location =
                    entry.topology().locate(head.node1.getNetId(), head.node2.getNetId(), head.position);
            if (location != null) {
                RailEdge edge = entry.graph().edges.get(location.edgeId());
                Vec3 position = RailGeometry.pointAlong(edge, location.offset());
                String dim = entry.graph().dimensions.get(entry.graph().nodes.get(edge.from).dimension);
                return new Pos(train.graph.id, position.x, position.z, dim);
            }
        }
        TrackNodeLocation nodeLocation = head.node1.getLocation();
        Vec3 position = nodeLocation.getLocation();
        return new Pos(train.graph != null ? train.graph.id : null, position.x, position.z,
                nodeLocation.dimension == null ? "" : nodeLocation.dimension.location().toString());
    }
}
