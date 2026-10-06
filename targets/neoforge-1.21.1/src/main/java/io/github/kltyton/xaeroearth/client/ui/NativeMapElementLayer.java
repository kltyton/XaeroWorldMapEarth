package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sighs.apricityui.chunkmap.AuiMapRenderState;
import com.sighs.apricityui.neoforge.RenderService;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Composites Xaero's original two-dimensional elements after translucent terrain. */
public final class NativeMapElementLayer {
    private static TextureTarget layer;
    private static int previousRead, previousDraw;
    private static boolean pending;

    private NativeMapElementLayer() { }

    public static void begin(RenderTarget target) {
        try (var state = new AuiMapRenderState(target)) {
            if (layer == null || layer.width != target.width || layer.height != target.height) {
                close();
                layer = new TextureTarget(target.width, target.height, true, Minecraft.ON_OSX);
                layer.setClearColor(0, 0, 0, 0);
            }
            layer.clear(Minecraft.ON_OSX);
        }
        pending = true;
        resume();
    }

    public static boolean pending() { return pending; }

    public static void resume() {
        previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        layer.bindWrite(false);
    }

    public static void end() {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
    }

    public static void composite(RenderTarget target) {
        if (!pending) return;
        pending = false;
        var projection = RenderService.INSTANCE.getProjectionMatrix();
        try (var state = new AuiMapRenderState(target)) {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            layer.blitToScreen(target.width, target.height, false);
        } finally {
            RenderService.INSTANCE.setProjectionMatrix(projection);
        }
    }

    public static void close() {
        pending = false;
        if (layer != null) { layer.destroyBuffers(); layer = null; }
    }
}
