package io.github.kltyton.xaeroearth.client.render.entity;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import org.joml.FrustumIntersection;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Camera geometry shared by entity-buffer and submit-node renderers. */
final class EntityMapGeometry {
    private EntityMapGeometry() { }

    static boolean visible(MapCamera camera, float x, float y, float z, float width, float height) {
        float radius = width * 0.5F;
        return new FrustumIntersection(camera.projectionMatrix().mul(camera.viewMatrix())).testAab(
                x - radius, y, z - radius, x + radius, y + height + 0.35F, z + radius);
    }

    static boolean containsEye(MapCamera camera, double eyeX, double eyeY, double eyeZ,
            double x, double y, double z, float width, float height) {
        float radius = width * 0.5F;
        return camera.mode().equals("street") && eyeX >= x - radius && eyeX <= x + radius
                && eyeZ >= z - radius && eyeZ <= z + radius && eyeY >= y && eyeY <= y + height;
    }

    static float headScale(MapCamera camera, Quaternionf orientation, double x, double y, double z) {
        Vector3f tip = camera.project(x, y, z), right = orientation.transform(new Vector3f(1, 0, 0));
        Vector3f unit = camera.project(x + right.x, y + right.y, z + right.z);
        float pixels = (float) Math.hypot(unit.x - tip.x, unit.y - tip.y);
        if (!Float.isFinite(pixels) || pixels <= 0) return 0;
        return (float) (Math.max(16, Math.min(24, 0.5 * pixels)) / pixels / 0.5);
    }
}
