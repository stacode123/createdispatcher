package net.Dispatcher.content.graph.v2;

import com.google.gson.stream.JsonWriter;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Serializes a {@link RailGraph} to the gzipped JSON the web map consumes.
 * Coordinates are quantized to 0.1-block integers (deci-blocks) — roughly halves the payload.
 * Field names are the web wire contract (web/src/lib/api/types.ts). The store version is
 * deliberately NOT part of the payload — clients take it from the index/events, and the store
 * byte-compares rebuilt payloads to keep versions stable across no-op refreshes.
 *
 * <p>Pure serialization over an immutable graph — safe to run off the server thread.
 */
public final class RailGraphJson {
    /** bbox: per dimension index [minX, minZ, maxX, maxZ] in world blocks (NaN-free; empty dims get nulls). */
    public record Built(byte[] gzJson, double[][] bbox, int stationCount) {}

    private RailGraphJson() {}

    public static Built build(RailGraph graph) throws IOException {
        double[][] bbox = new double[graph.dimensions.size()][];
        Set<UUID> stationIds = new HashSet<>();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 20);
        try (JsonWriter json = new JsonWriter(new OutputStreamWriter(new GZIPOutputStream(buffer), StandardCharsets.UTF_8))) {
            json.beginObject();
            json.name("id").value(graph.trackGraphId.toString());
            json.name("dimensions").beginArray();
            for (String dimension : graph.dimensions) json.value(dimension);
            json.endArray();

            json.name("nodes").beginArray();
            for (RailNode node : graph.nodes) {
                json.beginArray();
                json.value(Math.round(node.position.x * 10));
                json.value(Math.round(node.position.y * 10));
                json.value(Math.round(node.position.z * 10));
                json.value(node.dimension);
                json.value(node.type.ordinal());
                json.endArray();
            }
            json.endArray();

            json.name("edges").beginArray();
            for (RailEdge edge : graph.edges) {
                int dim = graph.nodes.get(edge.from).dimension;
                json.beginObject();
                json.name("from").value(edge.from);
                json.name("to").value(edge.to);
                json.name("opp").value(edge.oppositeId);
                json.name("len").value(Math.round(edge.length * 100) / 100.0);
                json.name("sig").value(edge.entrySignal.ordinal());
                json.name("dim").value(dim);
                if (edge.speedCapKmh > 0) json.name("cap").value(Math.round(edge.speedCapKmh));
                if (edge.interDimensional) json.name("xd").value(true);
                json.name("shape").beginArray();
                for (Vec3 point : edge.shape) {
                    json.beginArray();
                    json.value(Math.round(point.x * 10));
                    json.value(Math.round(point.z * 10));
                    json.endArray();
                }
                json.endArray();
                if (!edge.stations.isEmpty()) {
                    json.name("stations").beginArray();
                    for (RailStation station : edge.stations) {
                        stationIds.add(station.stationId());
                        json.beginObject();
                        json.name("id").value(station.stationId().toString());
                        json.name("name").value(station.name());
                        json.name("off").value(Math.round(station.offset() * 10) / 10.0);
                        json.name("ap").value(station.approachable());
                        json.endObject();
                    }
                    json.endArray();
                }
                json.endObject();

                // bbox from canonical edges only (twins share geometry)
                if ((edge.oppositeId < 0 || edge.id < edge.oppositeId) && !edge.interDimensional) {
                    double[] box = bbox[dim];
                    if (box == null) bbox[dim] = box = new double[] {
                            Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
                    for (Vec3 point : edge.shape) {
                        if (point.x < box[0]) box[0] = point.x;
                        if (point.z < box[1]) box[1] = point.z;
                        if (point.x > box[2]) box[2] = point.x;
                        if (point.z > box[3]) box[3] = point.z;
                    }
                }
            }
            json.endArray();
            json.endObject();
        }
        return new Built(buffer.toByteArray(), bbox, stationIds.size());
    }
}
