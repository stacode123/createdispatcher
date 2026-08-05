package net.Dispatcher.content.graph.v2;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Geometry helpers over the collapsed graph. */
public final class RailGeometry {
    private RailGeometry() {}

    /**
     * World position at an offset (blocks from the edge start), walking the shape polyline by
     * arc length — the server-side twin of the map's pointAlong.
     */
    public static Vec3 pointAlong(RailEdge edge, double offset) {
        List<Vec3> shape = edge.shape;
        if (shape.isEmpty()) return Vec3.ZERO;
        if (shape.size() == 1) return shape.get(0);
        double totalArc = 0;
        for (int i = 1; i < shape.size(); i++) totalArc += shape.get(i).distanceTo(shape.get(i - 1));
        if (totalArc <= 0) return shape.get(0);
        double target = Math.min(1, Math.max(0, offset / edge.length)) * totalArc;
        for (int i = 1; i < shape.size(); i++) {
            double segment = shape.get(i).distanceTo(shape.get(i - 1));
            if (target <= segment && segment > 0)
                return shape.get(i - 1).lerp(shape.get(i), target / segment);
            target -= segment;
        }
        return shape.get(shape.size() - 1);
    }
}
