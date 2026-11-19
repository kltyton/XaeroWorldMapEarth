package io.github.kltyton.xaeroearth.client.mapmark;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.MapTerrainHit;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;

/** Builds depth-tested annotation triangles in the same world coordinates as the map. */
public final class MapMarkMesh {
    private static final int LINE = -10, AREA = -11, FILL = -12, NOTE = -13, WAYPOINT = -14, MEASURE = -15, WAYSTONE = -16;
    private static final double LIFT = 0.28;
    private static final int MAX_VERTICES = 180_000;
    private record Vertex(Vec3 point, int style, int tint) { }
    private record Span(int start, int end, double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) { }
    private final List<Vertex> vertices = new ArrayList<>();
    private final List<Span> spans = new ArrayList<>(), visible = new ArrayList<>();
    private List<MapMark> cached;
    private int widthKey = Integer.MIN_VALUE;
    private long cachedSurfaceRevision = Long.MIN_VALUE;
    private long cachedCoverageRevision = Long.MIN_VALUE;
    private int count;

    public void update(List<MapMark> marks, MapCamera camera) {
        update(marks, camera, null, null);
    }

    public void update(List<MapMark> marks, MapCamera camera, XaeroMapBridge bridge, NativeTerrainScene scene) {
        int nextWidth = (int) Math.round(8 * Math.log(camera.pixelsPerBlock()) / Math.log(2));
        boolean drape = bridge != null && scene != null && hasDrapedMark(marks);
        var surface = drape ? scene.visibleSurface() : null;
        long surfaceRev = surface == null ? -1L : surface.revision();
        long coverageRev = drape ? scene.coverageRevision() : -1L;
        if (marks != cached || nextWidth != widthKey
                || drape && (surfaceRev != cachedSurfaceRevision || coverageRev != cachedCoverageRevision)) {
            int surfaceStep = surface == null ? 1 : Math.max(1, Math.min(16, surface.step()));
            rebuild(marks, nextWidth, drape ? bridge : null, drape ? scene : null, surfaceStep, surfaceRev, coverageRev);
        }
        count = 0; visible.clear();
        if (spans.isEmpty()) return;
        var frustum = new FrustumIntersection(camera.viewportProjectionMatrix().mul(camera.viewMatrix()));
        for (var span : spans) {
            if (!frustum.testAab((float) (span.minX - camera.x()), (float) (span.minY - camera.y()),
                    (float) (span.minZ - camera.z()), (float) (span.maxX - camera.x()),
                    (float) (span.maxY - camera.y()), (float) (span.maxZ - camera.z()))) continue;
            visible.add(span);
            count += span.end - span.start;
        }
    }

    private static boolean hasDrapedMark(List<MapMark> marks) {
        if (marks == null || marks.isEmpty()) return false;
        for (var mark : marks) {
            switch (mark.kind()) {
                case LINE, MEASURE, AREA -> { return true; }
                default -> { }
            }
        }
        return false;
    }

    private void rebuild(List<MapMark> marks, int nextWidth, XaeroMapBridge bridge, NativeTerrainScene scene,
                         int surfaceStep, long surfaceRev, long coverageRev) {
        cached = marks; widthKey = nextWidth;
        cachedSurfaceRevision = surfaceRev; cachedCoverageRevision = coverageRev;
        vertices.clear(); spans.clear();
        double halfWidth = Math.max(0.17, Math.min(12, 1.25 / Math.pow(2, widthKey / 8.0)));
        var ground = bridge != null && scene != null ? new Long2DoubleOpenHashMap() : null;
        for (MapMark mark : marks) {
            int start = vertices.size();
            List<Vec3> points = mark.points();
            switch (mark.kind()) {
                case LINE -> stroke(points, LINE, halfWidth, false, bridge, scene, surfaceStep, ground);
                case MEASURE -> {
                    stroke(points, MEASURE, halfWidth, false, bridge, scene, surfaceStep, ground);
                    diamond(drapedPoint(points.get(0), bridge, scene, ground), halfWidth, MEASURE);
                    diamond(drapedPoint(points.get(1), bridge, scene, ground), halfWidth, MEASURE);
                }
                case AREA -> {
                    fill(points, bridge, scene, surfaceStep, ground);
                    stroke(points, AREA, halfWidth, true, bridge, scene, surfaceStep, ground);
                }
                case NOTE -> diamond(points.get(0), halfWidth * 2.1, NOTE);
                case WAYPOINT -> diamond(points.get(0), halfWidth * 2.6, WAYPOINT);
                case WAYSTONE -> diamond(points.get(0), halfWidth * 2.2, WAYSTONE);
                case CLAIM -> claim(points, mark.tint(), halfWidth * 0.55);
                case ADDON_SURFACE -> triangle(points.get(0), points.get(1), points.get(2),
                        -(20 + (mark.tint() >>> 24)), mark.tint() & 0xffffff);
            }
            int end = vertices.size();
            if (end == start) continue;
            var first = vertices.get(start).point;
            double minX = first.x, minY = first.y, minZ = first.z;
            double maxX = first.x, maxY = first.y, maxZ = first.z;
            for (int i = start + 1; i < end; i++) {
                var p = vertices.get(i).point;
                minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
            }
            spans.add(new Span(start, end, minX, minY, minZ, maxX, maxY, maxZ));
        }
    }

    public int count() { return count; }

    public void write(ByteBuffer out, MapCamera camera) {
        for (var span : visible) for (int i = span.start; i < span.end; i++) {
            var vertex = vertices.get(i);
            Vec3 p = vertex.point;
            out.putFloat((float) (p.x - camera.x())).putFloat((float) (p.y - camera.y()))
                    .putFloat((float) (p.z - camera.z())).putFloat(vertex.style).putFloat(vertex.tint);
        }
        out.flip();
    }

    public void clear() {
        cached = null; cachedSurfaceRevision = Long.MIN_VALUE; cachedCoverageRevision = Long.MIN_VALUE;
        vertices.clear(); spans.clear(); visible.clear(); count = 0;
    }

    private static double sampleY(double x, double z, double fallbackY, XaeroMapBridge bridge,
                                  NativeTerrainScene scene, Long2DoubleOpenHashMap ground) {
        if (bridge == null || scene == null) return fallbackY;
        double y = MapTerrainHit.surfaceY(bridge, scene, x, z);
        if (!Double.isFinite(y)) return fallbackY;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (scene.hasDetailAt(bx, bz)) {
            double renderedTop = y;
            long key = (long) bx << 32 | bz & 0xffffffffL;
            y = ground.computeIfAbsent(key, (java.util.function.LongToDoubleFunction)
                    column -> bridge.drawingGroundY(bx, bz, renderedTop));
        }
        return y;
    }

    private static Vec3 drapedPoint(Vec3 point, XaeroMapBridge bridge, NativeTerrainScene scene,
                                    Long2DoubleOpenHashMap ground) {
        return new Vec3(point.x, sampleY(point.x, point.z, point.y, bridge, scene, ground), point.z);
    }

    private static double nextEdge(double origin, double delta, double time, int spacing) {
        if (Math.abs(delta) < 1e-9) return Double.POSITIVE_INFINITY;
        double snapped = (origin + delta * time) / spacing;
        double cell = delta > 0 ? Math.floor(snapped + 1e-9) + 1 : Math.ceil(snapped - 1e-9) - 1;
        return (cell * spacing - origin) / delta;
    }

    private void stroke(List<Vec3> points, int style, double width, boolean closed, XaeroMapBridge bridge,
                        NativeTerrainScene scene, int surfaceStep, Long2DoubleOpenHashMap ground) {
        int segments = closed ? points.size() : points.size() - 1;
        for (int i = 0; i < segments && vertices.size() < MAX_VERTICES; i++) {
            Vec3 p0 = points.get(i), p1 = points.get((i + 1) % points.size());
            double dx = p1.x - p0.x, dz = p1.z - p0.z, length = Math.hypot(dx, dz);
            if (length < 1e-5) continue;
            double ox = -dz * width / length, oz = dx * width / length;
            if (bridge == null || scene == null) {
                Vec3 a = p0.add(0, LIFT, 0), b = p1.add(0, LIFT, 0);
                Vec3 side = new Vec3(ox, 0, oz);
                var startLeft = a.add(side); var startRight = a.subtract(side);
                var endLeft = b.add(side); var endRight = b.subtract(side);
                triangle(startLeft, startRight, endRight, style);
                triangle(startLeft, endRight, endLeft, style);
                continue;
            }
            int minSpacing = Math.max(1, (int) Math.ceil(length / 512.0));
            Vec3 previousLeft = null, previousRight = null;
            double t = 0.0;
            for (int step = 0; step < 2048 && t < 1.0 - 1e-10 && vertices.size() + 12 <= MAX_VERTICES; step++) {
                double probe = Math.min(1.0, t + 1e-5 / length);
                int sx = (int) Math.floor(p0.x + dx * probe), sz = (int) Math.floor(p0.z + dz * probe);
                int spacing = Math.max(minSpacing, scene.hasDetailAt(sx, sz) ? 1 : surfaceStep);
                double end = Math.min(1.0, Math.min(nextEdge(p0.x, dx, t, spacing), nextEdge(p0.z, dz, t, spacing)));
                if (end <= t + 1e-10) end = Math.min(1.0, t + spacing / length);
                double x0 = p0.x + dx * t, z0 = p0.z + dz * t;
                double x1 = p0.x + dx * end, z1 = p0.z + dz * end;
                double sx0 = x0 + (x1 - x0) * 0.001, sz0 = z0 + (z1 - z0) * 0.001;
                double sx1 = x1 - (x1 - x0) * 0.001, sz1 = z1 - (z1 - z0) * 0.001;
                double y0 = sampleY(sx0, sz0, p0.y + (p1.y - p0.y) * t, bridge, scene, ground) + LIFT;
                double y1 = sampleY(sx1, sz1, p0.y + (p1.y - p0.y) * end, bridge, scene, ground) + LIFT;
                Vec3 a = new Vec3(x0 + ox, y0, z0 + oz);
                Vec3 b = new Vec3(x0 - ox, y0, z0 - oz);
                Vec3 c = new Vec3(x1 - ox, y1, z1 - oz);
                Vec3 d = new Vec3(x1 + ox, y1, z1 + oz);
                triangle(a, b, c, style);
                triangle(a, c, d, style);
                if (previousLeft != null && Math.abs(previousLeft.y - a.y) > 0.001) {
                    triangle(previousLeft, previousRight, b, style);
                    triangle(previousLeft, b, a, style);
                }
                previousLeft = d;
                previousRight = c;
                t = end;
            }
        }
        for (var point : points) {
            if (vertices.size() + 6 > MAX_VERTICES) break;
            diamond(drapedPoint(point, bridge, scene, ground), width, style);
        }
    }

    private void diamond(Vec3 point, double width, int style) {
        double y = LIFT + 0.025;
        Vec3 west = point.add(-width, y, 0), east = point.add(width, y, 0);
        Vec3 north = point.add(0, y, -width), south = point.add(0, y, width);
        triangle(west, north, east, style);
        triangle(west, east, south, style);
    }

    private static double cross(Vec3 a, Vec3 b, Vec3 c) {
        return (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x);
    }

    /** Ear clips concave polygons and subdivides triangles along terrain relief. */
    private void fill(List<Vec3> points, XaeroMapBridge bridge, NativeTerrainScene scene,
                      int surfaceStep, Long2DoubleOpenHashMap ground) {
        var pending = new ArrayList<>(points);
        double area = 0;
        Vec3 origin = pending.get(0);
        double minX = origin.x, maxX = origin.x, minZ = origin.z, maxZ = origin.z;
        for (int i = 1; i < pending.size(); i++) {
            var p = pending.get(i);
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
            if (i + 1 < pending.size()) area += cross(origin, p, pending.get(i + 1));
        }
        if (Math.abs(area) < 1e-4) return;
        if (area < 0) Collections.reverse(pending);
        double boxArea = Math.max(1.0, (maxX - minX) * (maxZ - minZ));
        double maxEdge = Math.max(2.0, Math.max(surfaceStep, Math.min(12.0, Math.sqrt(boxArea / 1024.0))));
        int start = vertices.size();
        while (pending.size() > 2) {
            boolean removed = false;
            int size = pending.size();
            for (int i = 0; i < size; i++) {
                int tip = (i + 1) % size, end = (i + 2) % size;
                var a = pending.get(i); var b = pending.get(tip); var c = pending.get(end);
                if (cross(a, b, c) < 1e-6) continue;
                boolean inside = false;
                for (int j = 0; !inside && j < size; j++) {
                    if (j == i || j == tip || j == end) continue;
                    var point = pending.get(j);
                    inside = cross(a, b, point) >= -1e-6 && cross(b, c, point) >= -1e-6
                            && cross(c, a, point) >= -1e-6;
                }
                if (inside) continue;
                if (bridge == null || scene == null) {
                    triangle(a.add(0, LIFT * 0.5, 0), b.add(0, LIFT * 0.5, 0), c.add(0, LIFT * 0.5, 0), FILL);
                } else {
                    subdivideFill(a, b, c, maxEdge * maxEdge, 0, bridge, scene, ground);
                }
                pending.remove(tip);
                removed = true;
                break;
            }
            if (!removed) {
                vertices.subList(start, vertices.size()).clear();
                return;
            }
        }
    }

    private void subdivideFill(Vec3 a, Vec3 b, Vec3 c, double maxEdgeSqr, int depth, XaeroMapBridge bridge,
                               NativeTerrainScene scene, Long2DoubleOpenHashMap ground) {
        if (vertices.size() + 3 > MAX_VERTICES) return;
        double ab = edgeSqr2d(a, b), bc = edgeSqr2d(b, c), ca = edgeSqr2d(c, a);
        double longest = Math.max(ab, Math.max(bc, ca));
        if (depth < 8 && longest > maxEdgeSqr && vertices.size() + 6 <= MAX_VERTICES) {
            if (ab >= bc && ab >= ca) {
                Vec3 mid = a.lerp(b, 0.5);
                subdivideFill(a, mid, c, maxEdgeSqr, depth + 1, bridge, scene, ground);
                subdivideFill(mid, b, c, maxEdgeSqr, depth + 1, bridge, scene, ground);
            } else if (bc >= ab && bc >= ca) {
                Vec3 mid = b.lerp(c, 0.5);
                subdivideFill(a, b, mid, maxEdgeSqr, depth + 1, bridge, scene, ground);
                subdivideFill(a, mid, c, maxEdgeSqr, depth + 1, bridge, scene, ground);
            } else {
                Vec3 mid = c.lerp(a, 0.5);
                subdivideFill(a, b, mid, maxEdgeSqr, depth + 1, bridge, scene, ground);
                subdivideFill(mid, b, c, maxEdgeSqr, depth + 1, bridge, scene, ground);
            }
            return;
        }
        double lift = LIFT * 0.55;
        Vec3 va = new Vec3(a.x, sampleY(a.x, a.z, a.y, bridge, scene, ground) + lift, a.z);
        Vec3 vb = new Vec3(b.x, sampleY(b.x, b.z, b.y, bridge, scene, ground) + lift, b.z);
        Vec3 vc = new Vec3(c.x, sampleY(c.x, c.z, c.y, bridge, scene, ground) + lift, c.z);
        triangle(va, vb, vc, FILL);
    }

    private static double edgeSqr2d(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private void triangle(Vec3 a, Vec3 b, Vec3 c, int style) {
        triangle(a, b, c, style, 0);
    }

    private void triangle(Vec3 a, Vec3 b, Vec3 c, int style, int tint) {
        vertices.add(new Vertex(a, style, tint));
        vertices.add(new Vertex(b, style, tint));
        vertices.add(new Vertex(c, style, tint));
    }

    private void claim(List<Vec3> p, int style, double width) {
        var a = p.get(0).add(0, LIFT, 0); var b = p.get(1).add(0, LIFT, 0);
        var c = p.get(2).add(0, LIFT, 0); var d = p.get(3).add(0, LIFT, 0);
        int tint = style & 0xffffff;
        triangle(a, b, c, -17, tint); triangle(a, c, d, -17, tint);
        for (int i = 0; i < 4; i++) if ((style & (1 << (24 + i))) != 0) {
            var from = p.get(i).add(0, LIFT + 0.025, 0);
            var to = p.get((i + 1) % 4).add(0, LIFT + 0.025, 0);
            var delta = to.subtract(from);
            double length = Math.hypot(delta.x, delta.z);
            var offset = new Vec3(-delta.z * width / length, 0, delta.x * width / length);
            triangle(from, from.add(offset), to.add(offset), -18, tint);
            triangle(from, to.add(offset), to, -18, tint);
        }
    }
}
