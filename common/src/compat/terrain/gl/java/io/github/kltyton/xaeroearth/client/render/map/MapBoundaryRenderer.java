package io.github.kltyton.xaeroearth.client.render.map;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene.Surface;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/** Fades the mapped silhouette into the atlas without changing the terrain depth or its data. */
public final class MapBoundaryRenderer implements AutoCloseable {
    private KuiMapGlProgram program;
    private int colorTarget, triangle;

    public void draw(RenderTarget target, KuiMapDepthTarget depth, MapCamera camera, Surface surface, int heightTexture) {
        try (var state = new KuiMapRenderState(target, depth)) {
            int mapDepth = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (program == null) {
                program = new KuiMapGlProgram("xaeroearth", "map_boundary");
                triangle = GL30.glGenVertexArrays();
                colorTarget = GL30.glGenFramebuffers();
            }
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, colorTarget);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, target.getColorTextureId(), 0);
            int active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            GlStateManager._activeTexture(GL13.GL_TEXTURE1);
            int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            try {
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                program.bind();
                var view = MapBoundaryView.of(camera, surface, camera.projectionMatrix());
                program.matrix("WorldFromClip", view.inverse());
                program.vector("BoundaryOrigin", view.x(), view.z(), view.step(), view.fieldRow());
                program.vector("BoundaryView", view.width(), view.height(), view.feather(), view.aspect());
                program.vector("BoundaryRay", view.cameraY(), view.slopeX(), view.slopeZ(), 0);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                GL33.glBindSampler(0, 0);
                GlStateManager._bindTexture(mapDepth);
                program.sampler("TerrainDepth", 0);
                GlStateManager._activeTexture(GL13.GL_TEXTURE1);
                GL33.glBindSampler(1, 0);
                GlStateManager._bindTexture(heightTexture);
                program.sampler("BoundaryField", 1);
                GlStateManager._glBindVertexArray(triangle);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            } finally {
                GlStateManager._activeTexture(GL13.GL_TEXTURE1);
                GlStateManager._bindTexture(prevTex1);
                GL33.glBindSampler(1, prevSampler1);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                GlStateManager._bindTexture(prevTex0);
                GL33.glBindSampler(0, prevSampler0);
                GlStateManager._activeTexture(active);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, 0, 0);
                // The depth scope restores the original attachments on the target framebuffer.
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            }
        }
    }

    @Override public void close() {
        if (program == null) return;
        program.close();
        GL30.glDeleteFramebuffers(colorTarget);
        GL30.glDeleteVertexArrays(triangle);
        program = null;
    }
}
