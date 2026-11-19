package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene.Surface;
import org.joml.Matrix4f;

/** Supplies the current coarse color and coverage at the edge of the pack-rendered geometry. */
public record MapPackLodTransition<T>(View view, T color, T height, T coverage) {
    public record View(Matrix4f inverse, float originX, float originZ, float step, float brightness,
                       float coverageX, float coverageZ, float width, float cameraY, float depthScale,
                       float depthBias, float packedHeight) {
        static final View DISABLED = new View(new Matrix4f(), 0, 0, 1, 1, 0, 0, 0, 0, 2, -1, 0);

        public static View of(MapCamera camera, Surface surface, int coverageX, int coverageZ, float brightness,
                              Matrix4f projection, boolean zeroToOne, boolean packedHeight) {
            return new View(new Matrix4f(projection).mul(camera.viewMatrix()).invert(),
                    (float) (surface.x() - camera.x()), (float) (surface.z() - camera.z()), surface.step(), brightness,
                    (float) (camera.x() - coverageX * 16L), (float) (camera.z() - coverageZ * 16L),
                    (float) Math.max(2, Math.min(4, 6 / camera.pixelsPerBlock())), (float) camera.y(),
                    zeroToOne ? 1 : 2, zeroToOne ? 0 : -1, packedHeight ? 1 : 0);
        }
    }
}
