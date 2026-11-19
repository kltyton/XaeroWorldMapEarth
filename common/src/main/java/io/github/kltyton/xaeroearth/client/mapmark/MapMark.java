package io.github.kltyton.xaeroearth.client.mapmark;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public record MapMark(UUID id, Kind kind, List<Vec3> points, String text, int tint) {
    public enum Kind { LINE, AREA, NOTE, WAYPOINT, MEASURE, WAYSTONE, CLAIM, ADDON_SURFACE }
    public static final int POINT_LIMIT = 128;
    public static final int TEXT_LIMIT = 96;

    public MapMark(UUID id, Kind kind, List<Vec3> points, String text) { this(id, kind, points, text, 0); }

    public MapMark {
        points = List.copyOf(points);
        boolean countValid = switch (kind) {
            case LINE -> points.size() >= 2;
            case AREA -> points.size() >= 3;
            case NOTE, WAYPOINT, WAYSTONE -> points.size() == 1;
            case MEASURE -> points.size() == 2;
            case CLAIM -> points.size() == 4;
            case ADDON_SURFACE -> points.size() == 3;
        };
        if (id == null || text == null || text.length() > TEXT_LIMIT || !countValid || points.size() > POINT_LIMIT)
            throw new IllegalArgumentException("Invalid map mark");
        for (Vec3 point : points) {
            if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                    || Math.abs(point.x) > 30_000_002 || Math.abs(point.z) > 30_000_002 || Math.abs(point.y) > 65_536)
                throw new IllegalArgumentException("Invalid map mark coordinate");
        }
    }
}
