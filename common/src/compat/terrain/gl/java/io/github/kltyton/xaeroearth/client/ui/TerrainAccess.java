package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import io.github.kltyton.xaeroearth.client.chunkmap.picking.MapDepthPicker;

import net.minecraft.client.Minecraft;

abstract class TerrainAccess {
    protected static NativeTerrainScene renderer;
    private static TextureTarget background;

    protected static void saveWorldBackground(RenderTarget scene) {
        try (var state = new KuiMapRenderState(null)) {
            if (background == null || background.width != scene.width || background.height != scene.height) {
                releaseWorldBackground();
                background = new TextureTarget(scene.width, scene.height, false, Minecraft.ON_OSX);
            }
            copy(scene, background);
        }
    }

    protected static boolean restoreWorldBackground(RenderTarget scene) {
        if (background == null || background.width != scene.width || background.height != scene.height) return false;
        try (var state = new KuiMapRenderState(null)) { copy(background, scene); }
        return true;
    }

    private static void copy(RenderTarget src, RenderTarget dst) {
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dst.frameBufferId);
        try {
            GL30.glBlitFramebuffer(0, 0, src.width, src.height, 0, 0, dst.width, dst.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } finally {
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    protected static void releaseWorldBackground() {
        if (background != null) { background.destroyBuffers(); background = null; }
    }
    protected static RenderTarget mainTarget(Minecraft minecraft) {
        return minecraft.getMainRenderTarget();
    }

    protected static void captureMapDepth(RenderTarget target, MapDepthPicker picker, MapDepthPicker.View view,
                                          double x, double y) {
        try (var state = new KuiMapRenderState(target, renderer.depthTarget())) {
            picker.captureFramebuffer(target.frameBufferId, view, target.width, target.height, false, x, y);
        }
    }

    public static io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget depthTarget() {
        return renderer.depthTarget();
    }
}
