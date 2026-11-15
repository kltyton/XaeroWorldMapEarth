package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import net.minecraft.world.phys.Vec3;

/** Finds the frontmost mapped surface along the same ray used by the map projection. */
public final class MapTerrainHit {
    public static Vec3 pick(MapCamera camera, XaeroMapBridge bridge, NativeTerrainScene scene, double x, double y) {
        var ray = camera.ray(x, y);
        var origin = new Vec3(camera.x() + ray[0].x, camera.y() + ray[0].y, camera.z() + ray[0].z);
        var direction = new Vec3(ray[1].x, ray[1].y, ray[1].z);
        double begin = 0, end = camera.mode().equals("street") ? 2000 * camera.unit()
                : camera.eyeOffset().length() * 2;
        if (Math.abs(direction.y) > 1e-8) {
            double a = (bridge.minPickHeight() - origin.y) / direction.y;
            double b = (bridge.maxPickHeight() - origin.y) / direction.y;
            begin = Math.max(begin, Math.min(a, b));
            end = Math.min(end, Math.max(a, b));
        }
        var surface = bridge.surface();
        while (begin <= end) {
            Vec3 entry = origin.add(direction.scale(begin + 1e-6));
            int bx = (int) Math.floor(entry.x), bz = (int) Math.floor(entry.z);
            double leave = Math.min(end, Math.min(exit(origin.x, direction.x, bx), exit(origin.z, direction.z, bz)));
            if (scene.hasDetailAt(bx, bz)) {
                int top = bridge.heightAt(bx, bz, Integer.MIN_VALUE);
                if (top != Integer.MIN_VALUE && direction.y < -1e-8) {
                    double hit = (top + 1.0 - origin.y) / direction.y;
                    if (hit <= leave + 1e-6) {
                        Vec3 p = origin.add(direction.scale(Math.max(hit, begin + 1e-6)));
                        return new Vec3(p.x, top + 1, p.z);
                    }
                }
            } else if (surface != null) {
                Vec3 hit = coarse(surface, origin, direction, entry, begin, leave);
                if (hit != null) return hit;
            }
            if (!Double.isFinite(leave) || leave >= end) break;
            begin = leave + 1e-6;
        }
        return null;
    }

    private static double exit(double origin, double direction, int cell) {
        if (Math.abs(direction) < 1e-8) return Double.POSITIVE_INFINITY;
        return ((direction > 0 ? cell + 1.0 : cell) - origin) / direction;
    }

    private static Vec3 coarse(NativeTerrainScene.Surface surface, Vec3 origin, Vec3 direction, Vec3 entry,
                               double begin, double end) {
        int step = surface.step();
        int x = (int) Math.floor((entry.x - surface.x()) / step);
        int z = (int) Math.floor((entry.z - surface.z()) / step);
        if (x < 0 || z < 0 || x >= surface.width() || z >= surface.depth()
                || surface.rgba()[(z * surface.width() + x) * 4 + 3] == 0) return null;
        double baseX = surface.x() + (long) x * step, baseZ = surface.z() + (long) z * step;
        double a = corner(surface, x, z), b = corner(surface, x, z + 1);
        double c = corner(surface, x + 1, z + 1), d = corner(surface, x + 1, z);
        double u = (origin.x - baseX) / step, v = (origin.z - baseZ) / step;
        for (int triangle = 0; triangle < 2; triangle++) {
            double dx = triangle == 0 ? c - b : d - a;
            double dz = triangle == 0 ? b - a : c - d;
            double rate = direction.y - (dx * direction.x + dz * direction.z) / step;
            if (rate >= -1e-8) continue;
            double hit = (a + u * dx + v * dz - origin.y) / rate;
            if (hit < begin - 1e-6 || hit > end + 1e-6) continue;
            double diagonal = u - v + (direction.x - direction.z) * hit / step;
            if (triangle == 0 ? diagonal <= 1e-6 : diagonal >= -1e-6)
                return origin.add(direction.scale(hit));
        }
        return null;
    }

    private static double corner(NativeTerrainScene.Surface surface, int x, int z) {
        double total = 0;
        int count = 0;
        for (int dz = -1; dz <= 0; dz++) for (int dx = -1; dx <= 0; dx++) {
            int sample = Math.max(0, Math.min(surface.depth() - 1, z + dz)) * surface.width()
                    + Math.max(0, Math.min(surface.width() - 1, x + dx));
            if (surface.rgba()[sample * 4 + 3] == 0) continue;
            total += surface.heights()[sample] + 1;
            count++;
        }
        return total / count;
    }

    private MapTerrainHit() {}
}
