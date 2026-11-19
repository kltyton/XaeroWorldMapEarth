package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import java.util.function.Function;
import org.joml.Matrix4f;

/** Couples the pack's temporal attachments to the map frame that produced them. */
final class MapPackTemporalState {
    private static final long IDLE_GAP_NS = 500_000_000L;
    private MapCamera previousCamera;
    private MapPackProjection previousProjection;
    private long lastFrame;

    void begin(MapPackTarget scope, MapCamera camera, MapPackProjection projection, Function<Matrix4f, ?> adapter) {
        boolean clear = previousProjection == null || System.nanoTime() - lastFrame > IDLE_GAP_NS
                || !camera.mode().equals(previousCamera.mode()) || viewChanged(camera) || beyondPreviousView(camera);
        scope.previousMatrices(clear ? projection : previousProjection, adapter, clear);
    }

    private boolean viewChanged(MapCamera camera) {
        // Rapid wheel zoom or rotation invalidates orthographic temporal history; reprojecting across
        // a large scale jump makes TAA/GI packs (e.g. iterationRP) flash 1-2 frames of dark history blocks.
        return Math.abs(camera.pixelsPerBlock() - previousCamera.pixelsPerBlock()) > previousCamera.pixelsPerBlock() * 0.04
                || Math.abs(camera.yaw() - previousCamera.yaw()) > 1e-3
                || Math.abs(camera.angle() - previousCamera.angle()) > 1e-3
                || Math.abs(camera.roll() - previousCamera.roll()) > 1e-3;
    }

    private boolean beyondPreviousView(MapCamera camera) {
        double x = camera.x() - previousCamera.x(), y = camera.y() - previousCamera.y(), z = camera.z() - previousCamera.z();
        double span = Math.min(camera.viewWidth(), camera.viewHeight())
                / Math.max(camera.pixelsPerBlock(), previousCamera.pixelsPerBlock());
        return x * x + y * y + z * z > span * span * 0.25;
    }

    void finish(MapCamera camera, MapPackProjection projection) {
        previousCamera = camera;
        previousProjection = projection;
        lastFrame = System.nanoTime();
    }

    void reset() {
        previousCamera = null;
        previousProjection = null;
        lastFrame = 0;
    }
}
