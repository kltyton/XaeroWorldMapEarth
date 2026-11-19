package io.github.kltyton.xaeroearth.client.render.map;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene.Surface;
import org.joml.Matrix4f;

/** World-space distances and atlas coordinates for a map boundary draw. */
public record MapBoundaryView(Matrix4f inverse, float x, float z, float step, int fieldRow,
                              int width, int height, float feather, float aspect,
                              float cameraY, float slopeX, float slopeZ) {
    public static MapBoundaryView of(MapCamera camera, Surface surface, Matrix4f projection) {
        double scale = Math.max(0.1, camera.pixelsPerBlock());
        float feather = (float) Math.max(28, Math.min(64, 44 / Math.sqrt(scale)));
        var eye = camera.eyeOffset();
        float horizontal = (float) Math.hypot(eye.x, eye.z);
        // Cap the ray-lift slope at 2:1 (default isometric is ~1.414:1) so grazing low-pitch
        // oblique views do not project boundary lookups hundreds of blocks across explored terrain.
        float divisor = Math.max(eye.y, horizontal * 0.5f);
        float inverseY = divisor <= 1e-4f ? 0f : 1f / divisor;
        return new MapBoundaryView(projection.mul(camera.viewMatrix()).invert(),
                (float) (surface.x() - camera.x()), (float) (surface.z() - camera.z()),
                surface.step(), surface.boundaryRow(), surface.width(), surface.depth(),
                feather, (float) camera.width() / camera.height(), (float) camera.y(),
                eye.x * inverseY, eye.z * inverseY);
    }
}
