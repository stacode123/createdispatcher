package net.Dispatcher.content.graph.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trips the web graph serializer: field names, deci-block quantization, bbox, station dedupe. */
class RailGraphJsonTest {

    @Test
    void serializesTheWireContract() throws Exception {
        RailGraph graph = new RailGraph(UUID.fromString("00000000-0000-0000-0000-000000000042"), 7);
        graph.dimensions.add("minecraft:overworld");
        graph.nodes.add(new RailNode(0, RailNode.NodeType.DEAD_END, 0, new Vec3(10.5, 64, -20)));
        graph.nodes.add(new RailNode(1, RailNode.NodeType.SIGNAL, 0, new Vec3(110.5, 64, -20)));

        RailEdge forward = new RailEdge(0, 0, 1, 100, 120, SignalKind.ENTRY, false);
        forward.oppositeId = 1;
        forward.shape.add(new Vec3(10.5, 64, -20));
        forward.shape.add(new Vec3(60.52, 64, -10.25));
        forward.shape.add(new Vec3(110.5, 64, -20));
        forward.stations.add(new RailStation(UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                "Central 1", 50, true));
        RailEdge backward = new RailEdge(1, 1, 0, 100, 0, SignalKind.NONE, false);
        backward.oppositeId = 0;
        backward.shape.add(new Vec3(110.5, 64, -20));
        backward.shape.add(new Vec3(60.52, 64, -10.25));
        backward.shape.add(new Vec3(10.5, 64, -20));
        backward.stations.add(new RailStation(UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                "Central 1", 50, true));
        graph.edges.add(forward);
        graph.edges.add(backward);

        RailGraphJson.Built built = RailGraphJson.build(graph);

        JsonObject root;
        try (var reader = new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(built.gzJson())), StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        assertEquals("00000000-0000-0000-0000-000000000042", root.get("id").getAsString());
        assertEquals(1, root.getAsJsonArray("dimensions").size());

        JsonArray nodes = root.getAsJsonArray("nodes");
        assertEquals(2, nodes.size());
        JsonArray node0 = nodes.get(0).getAsJsonArray();
        assertEquals(105, node0.get(0).getAsInt());   // 10.5 blocks -> 105 deci-blocks
        assertEquals(-200, node0.get(2).getAsInt());
        assertEquals(1, node0.get(4).getAsInt());     // DEAD_END ordinal

        JsonArray edges = root.getAsJsonArray("edges");
        assertEquals(2, edges.size());
        JsonObject e0 = edges.get(0).getAsJsonObject();
        assertEquals(0, e0.get("from").getAsInt());
        assertEquals(1, e0.get("to").getAsInt());
        assertEquals(1, e0.get("opp").getAsInt());
        assertEquals(1, e0.get("sig").getAsInt());    // ENTRY ordinal
        assertEquals(120, e0.get("cap").getAsInt());
        assertFalse(e0.has("xd"));
        JsonArray shape = e0.getAsJsonArray("shape");
        assertEquals(3, shape.size());
        assertEquals(605, shape.get(1).getAsJsonArray().get(0).getAsInt());   // 60.52 -> 605 (rounded)
        assertEquals(-102, shape.get(1).getAsJsonArray().get(1).getAsInt());  // Math.round(-102.5) -> -102 (half-up)
        JsonObject station = e0.getAsJsonArray("stations").get(0).getAsJsonObject();
        assertEquals("Central 1", station.get("name").getAsString());
        assertTrue(station.get("ap").getAsBoolean());
        assertFalse(edges.get(1).getAsJsonObject().has("cap"));

        assertEquals(1, built.stationCount());        // deduped across the twin pair
        double[] box = built.bbox()[0];
        assertEquals(10.5, box[0], 1e-6);
        assertEquals(-20, box[1], 1e-6);
        assertEquals(110.5, box[2], 1e-6);
        assertEquals(-10.25, box[3], 1e-6);
    }
}
