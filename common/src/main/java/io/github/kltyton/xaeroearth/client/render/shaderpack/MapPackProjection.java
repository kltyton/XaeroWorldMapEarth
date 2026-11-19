package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.TerrainDetail;
import org.joml.Matrix4f;

/** Maps the orthographic diorama into optical units without changing its screen or world coordinates. */
public record MapPackProjection(Matrix4f view, Matrix4f projection, float viewScale) {
    private static final float OPTICAL_FOCUS_DISTANCE = 64;

    public static MapPackProjection of(MapCamera camera, Matrix4f view, Matrix4f projection) {
        if (camera.mode().equals("street")) return new MapPackProjection(view, projection, 1);
        // Earth's eye offset only places the full map height inside its clip volume. A pack
        // interprets it as the distance that light travels, e.g. when computing atmospheric fog.
        float scale = Math.min(1, OPTICAL_FOCUS_DISTANCE / camera.eyeOffset().length());
        // (P / scale) * (scale * V) == P * V. Gbuffer world reconstruction cancels the
        // factor as well; screen positions, depth, material positions and shadow space stay put.
        return new MapPackProjection(new Matrix4f().scaling(scale).mul(view),
                new Matrix4f(projection).scale(1 / scale), scale);
    }

    public Matrix4f scaleModelView(Matrix4f modelView) {
        return new Matrix4f().scaling(viewScale).mul(modelView);
    }

    public static int renderDistance(MapCamera camera, ChunkTileSource source) {
        if (camera.mode().equals("street")) return 0;
        // Use the drawable envelope rather than the asynchronously changing set of resident meshes.
        double radius = TerrainDetail.radius(camera.pixelsPerBlock(), Double.POSITIVE_INFINITY);
        double horizontal = radius + 12 + Math.hypot(32, 32);
        double vertical = 0;
        if (source != null) {
            int bottom = source.minY(), top = source.maxY();
            double outside = Math.max(0, Math.max(bottom - camera.y(), camera.y() - top));
            vertical = (double) top - bottom + outside;
        }
        // Packs taper their edge fog immediately inside `far`. Leave this taper
        // beyond the detailed meshes: Xaero's mapped LOD already owns their border.
        return (int) Math.ceil(Math.hypot(horizontal, vertical) * 1.5);
    }
}
