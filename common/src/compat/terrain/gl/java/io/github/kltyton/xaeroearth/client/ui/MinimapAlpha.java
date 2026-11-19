package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Preserves Xaero's background opacity and treats covered terrain pixels as opaque. */
final class MinimapAlpha implements AutoCloseable {
    private KuiMapGlProgram program;
    private int triangle;

    void background(RenderTarget target, int percent) {
        try (var state = new KuiMapRenderState(target)) {
            RenderSystem.colorMask(true, true, true, true);
            GlStateManager._clearColor(0, 0, 0, percent / 100f);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        }
    }

    void resolve(RenderTarget target, NativeTerrainScene scene) {
        try (var state = new KuiMapRenderState(target, scene.depthTarget())) {
            if (program == null) {
                program = new KuiMapGlProgram("xaeroearth", "minimap_alpha");
                triangle = GL30.glGenVertexArrays();
            }
            RenderSystem.colorMask(false, false, false, true);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_NOTEQUAL);
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            program.bind();
            GlStateManager._glBindVertexArray(triangle);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }
    }

    @Override public void close() {
        if (program == null) return;
        program.close();
        GL30.glDeleteVertexArrays(triangle);
        program = null;
    }
}
