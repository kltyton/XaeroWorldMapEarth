package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import org.joml.Matrix4f;

/** The physical raster extent of a pack pass and the camera used to project it. */
record MapPackRegion(MapCamera camera, int x, int bottom, int width, int height) {
    private static final int EDGE_PIXELS = 16;

    static MapPackRegion of(MapCamera destination, int framebufferWidth, int framebufferHeight) {
        // The world map camera is measured in logical GUI units. Its projection covers
        // the whole destination, and the shader attachments must use physical pixels.
        if (!destination.minimap()) return new MapPackRegion(destination, 0, 0, framebufferWidth, framebufferHeight);
        // Leave nearby samples for reconstruction filters around Xaero's cropped HUD area.
        int width = Math.min(destination.width(), destination.viewWidth() + 2 * EDGE_PIXELS + 1);
        int height = Math.min(destination.height(), destination.viewHeight() + 2 * EDGE_PIXELS + 1);
        if (width == destination.width() && height == destination.height())
            return new MapPackRegion(destination, 0, 0, width, height);
        int left = start(destination.viewX(), destination.width() - width);
        int top = start(destination.viewY(), destination.height() - height);
        var camera = new MapCamera(destination.x(), destination.y(), destination.z(), destination.pixelsPerBlock(),
                destination.yaw(), destination.angle(), destination.mode(), width, height, destination.unit(),
                destination.fieldOfView(), destination.viewWidth(), destination.viewHeight(),
                destination.viewX() - left, destination.viewY() - top, destination.roll(),
                destination.matchPlanarFootprint(), true);
        return new MapPackRegion(camera, left, destination.height() - top - height, width, height);
    }

    private static int start(double wanted, int last) {
        return Math.max(0, Math.min(last, (int) Math.floor(wanted) - EDGE_PIXELS));
    }

    /** Crops the device projection in X/Y, preserving the destination's depth convention. */
    Matrix4f projection(MapCamera destination, Matrix4f projection) {
        if (destination == camera) return projection;
        return new Matrix4f().translation((destination.width() - 2f * x - width) / width,
                (destination.height() - 2f * bottom - height) / height, 0)
                .scale((float) destination.width() / width, (float) destination.height() / height, 1)
                .mul(projection);
    }
}
