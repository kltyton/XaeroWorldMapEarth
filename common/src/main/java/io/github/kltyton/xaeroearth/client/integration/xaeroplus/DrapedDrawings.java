package io.github.kltyton.xaeroearth.client.integration.xaeroplus;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.MapTerrainHit;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import io.github.kltyton.xaeroearth.client.mapmark.MapMark;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import io.github.kltyton.xaeroearth.client.render.map.MapHighlightField;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Samples Earth's published terrain (including its interpolated coarse relief) for XaeroPlus overlays. */
public final class DrapedDrawings {
    private static final UUID EXTERNAL = new UUID(0, 1);
    private static final int TRIANGLES_PER_VIEW = 40_000;
    private record Bounds(double minX, double minZ, double maxX, double maxZ) {
        boolean overlaps(double left, double top, double right, double bottom) {
            return left < maxX && right > minX && top < maxZ && bottom > minZ;
        }
    }
    private record Key(MapScope scope, Bounds bounds, long surface, long detail, int step) { }
    private List<DrawingSnapshot.Shape> shapes = List.of();
    private List<MapMark> mesh = List.of();
    private List<MapHighlightField.Area> areas = List.of();
    private MapHighlightField highlights;
    private Key key;
    private long lastRead;

    public List<MapMark> project(MapScope scope, MapCamera camera, XaeroMapBridge bridge,
                                 NativeTerrainScene scene) {
        if (scope == null || camera == null) { clear(); return List.of(); }
        var surface = scene.visibleSurface();
        long now = System.nanoTime();
        var extent = surface == null
                ? new Bounds(Math.floor(camera.x() / 32) * 32 - 256, Math.floor(camera.z() / 32) * 32 - 256,
                        Math.floor(camera.x() / 32) * 32 + 288, Math.floor(camera.z() / 32) * 32 + 288)
                : new Bounds(surface.x(), surface.z(), surface.x() + (long) surface.step() * surface.width(),
                        surface.z() + (long) surface.step() * surface.depth());
        int step = surface == null ? 1 : Math.max(1, Math.min(16, surface.step()));
        var wanted = new Key(scope, extent, surface == null ? -1 : surface.revision(), scene.coverageRevision(), step);
        var next = shapes;
        if (key == null || !key.scope.equals(scope) || now - lastRead >= 75_000_000L) {
            next = DrawingSnapshot.current(bridge.addonDimension());
            lastRead = now;
            var visibleAreas = new ArrayList<MapHighlightField.Area>();
            for (var shape : next) {
                if (shape instanceof DrawingSnapshot.Area area) visibleAreas.add(new MapHighlightField.Area(
                        Math.floorDiv(area.x(), 16), Math.floorDiv(area.z(), 16), area.argb()));
            }
            if (!areas.equals(visibleAreas)) {
                areas = List.copyOf(visibleAreas);
                highlights = MapHighlightField.build(areas);
            }
        }
        if (wanted.equals(key) && next.equals(shapes)) return mesh;
        shapes = next;
        key = wanted;
        if (next.isEmpty()) return mesh = List.of();
        var builder = new Builder(bridge, scene, extent, step);
        for (var shape : next) {
            if (shape instanceof DrawingSnapshot.Line line)
                builder.line(line.x1(), line.z1(), line.x2(), line.z2(), line.argb(), line.width());
            else if (shape instanceof DrawingSnapshot.Ellipse ellipse) builder.ellipse(ellipse);
        }
        return mesh = List.copyOf(builder.points);
    }

    public void clear() {
        key = null; shapes = List.of(); mesh = List.of(); lastRead = 0;
        areas = List.of(); highlights = null;
    }

    public MapHighlightField highlights() { return highlights; }

    private record Builder(XaeroMapBridge bridge, NativeTerrainScene scene, Bounds bounds, int step,
                           List<MapMark> points, Long2DoubleOpenHashMap ground) {
        Builder(XaeroMapBridge bridge, NativeTerrainScene scene, Bounds bounds, int step) {
            this(bridge, scene, bounds, step, new ArrayList<>(), new Long2DoubleOpenHashMap());
        }

        private Vec3 vertex(double x, double z, double sampleX, double sampleZ, double lift) {
            double y = MapTerrainHit.surfaceY(bridge, scene, sampleX, sampleZ);
            if (!Double.isFinite(y)) return null;
            int bx = (int) Math.floor(sampleX), bz = (int) Math.floor(sampleZ);
            if (scene.hasDetailAt(bx, bz)) {
                double renderedTop = y;
                long key = (long) bx << 32 | bz & 0xffffffffL;
                y = ground.computeIfAbsent(key, (java.util.function.LongToDoubleFunction)
                        column -> bridge.drawingGroundY(bx, bz, renderedTop));
            }
            return new Vec3(x, y + lift, z);
        }

        private void tri(Vec3 a, Vec3 b, Vec3 c, int color) {
            if (a == null || b == null || c == null || points.size() >= TRIANGLES_PER_VIEW) return;
            points.add(new MapMark(EXTERNAL, MapMark.Kind.ADDON_SURFACE, List.of(a, b, c), "", color));
        }

        private void line(double x, double z, double bx, double bz, int argb, float width) {
            if ((argb >>> 24) == 0 || points.size() >= TRIANGLES_PER_VIEW) return;
            double dx = bx - x, dz = bz - z, len = Math.hypot(dx, dz);
            if (len < 1e-4) return;
            double[] clipped = {0, 1};
            if (!clip(-dx, x - bounds.minX(), clipped) || !clip(dx, bounds.maxX() - x, clipped)
                    || !clip(-dz, z - bounds.minZ(), clipped) || !clip(dz, bounds.maxZ() - z, clipped)) return;
            var first = clipped[0]; var last = clipped[1];
            double halfWidth = Math.max(0.15, width * 0.5),
                    ox = -dz / len * halfWidth, oz = dx / len * halfWidth;
            Vec3 previousLeft = null, previousRight = null;
            double t = first;
            for (int i = 0; i < 4096 && t < last - 1e-10 && points.size() < TRIANGLES_PER_VIEW; i++) {
                double sample = Math.min(last, t + 1e-5 / len);
                int sx = (int) Math.floor(x + dx * sample), sz = (int) Math.floor(z + dz * sample);
                int spacing = scene.hasDetailAt(sx, sz) ? 1 : step;
                double end = Math.min(last, Math.min(nextEdge(x, dx, t, spacing), nextEdge(z, dz, t, spacing)));
                double x0 = x + dx * t, z0 = z + dz * t, x1 = x + dx * end, z1 = z + dz * end;
                // Sample just inside the interval so two segments on opposite sides of
                // a block rim can meet with a vertical ribbon instead of sinking inside it.
                double sx0 = x0 + (x1 - x0) * 0.001, sz0 = z0 + (z1 - z0) * 0.001;
                double sx1 = x1 - (x1 - x0) * 0.001, sz1 = z1 - (z1 - z0) * 0.001;
                Vec3 a = vertex(x0 + ox, z0 + oz, sx0, sz0, 0.12);
                Vec3 b = vertex(x0 - ox, z0 - oz, sx0, sz0, 0.12);
                Vec3 c = vertex(x1 - ox, z1 - oz, sx1, sz1, 0.12);
                Vec3 d = vertex(x1 + ox, z1 + oz, sx1, sz1, 0.12);
                if (a != null && c != null) {
                    tri(a, b, c, argb); tri(a, c, d, argb);
                    if (previousLeft != null && Math.abs(previousLeft.y - a.y) > 0.001) {
                        tri(previousLeft, previousRight, b, argb); tri(previousLeft, b, a, argb);
                    }
                    previousLeft = d; previousRight = c;
                } else { previousLeft = null; previousRight = null; }
                t = end;
            }
        }

        private static double nextEdge(double origin, double delta, double time, int spacing) {
            if (Math.abs(delta) < 1e-9) return Double.POSITIVE_INFINITY;
            double snapped = (origin + delta * time) / spacing;
            double cell = delta > 0 ? Math.floor(snapped + 1e-9) + 1 : Math.ceil(snapped - 1e-9) - 1;
            return (cell * spacing - origin) / delta;
        }

        private void ellipse(DrawingSnapshot.Ellipse ellipse) {
            if (!bounds.overlaps((double) ellipse.x() - ellipse.rx(), (double) ellipse.z() - ellipse.rz(),
                    (double) ellipse.x() + ellipse.rx(), (double) ellipse.z() + ellipse.rz())) return;
            int n = Math.max(16, Math.min(512, (int) (Math.PI * Math.max(ellipse.rx(), ellipse.rz()) / step)));
            double px = ellipse.x() + (double) ellipse.rx(), pz = ellipse.z();
            for (int i = 1; i <= n && points.size() < TRIANGLES_PER_VIEW; i++) {
                double angle = i * (2 * Math.PI / n);
                double x = ellipse.x() + Math.cos(angle) * ellipse.rx(), z = ellipse.z() + Math.sin(angle) * ellipse.rz();
                line(px, pz, x, z, ellipse.argb(), ellipse.width());
                px = x; pz = z;
            }
        }

        private static boolean clip(double p, double q, double[] range) {
            if (p == 0) return q >= 0;
            double t = q / p;
            if (p < 0) range[0] = Math.max(range[0], t); else range[1] = Math.min(range[1], t);
            return range[0] <= range[1];
        }
    }
}
