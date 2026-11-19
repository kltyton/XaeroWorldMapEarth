package io.github.kltyton.xaeroearth.client.render.shaderpack;

import org.lwjgl.opengl.GL11;

/** Captures the viewport and depth range once within a single map terrain pass. */
final class MapPackViewport {
    int[] viewport;
    double[] depth;

    void capture() {
        if (viewport != null) return;
        viewport = new int[4];
        depth = new double[2];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, depth);
    }
}
