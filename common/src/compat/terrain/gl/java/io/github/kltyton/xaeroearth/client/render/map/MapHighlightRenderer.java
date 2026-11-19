package io.github.kltyton.xaeroearth.client.render.map;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/** Tints the actual fragments of the highlighted terrain instead of laying polygons across its tops. */
public final class MapHighlightRenderer implements AutoCloseable {
    private MapHighlightField uploaded;
    private DynamicTexture atlas;
    private KuiMapGlProgram program;
    private int colorTarget, triangle;

    public void draw(RenderTarget target, KuiMapDepthTarget depth, MapCamera camera, MapHighlightField field) {
        if (field == null) return;
        try (var state = new KuiMapRenderState(target, depth)) {
            if (field != uploaded) upload(field);
            int depthTexture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (program == null) {
                program = new KuiMapGlProgram("xaeroearth", "map_highlight");
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
                program.matrix("HighlightFromClip", camera.projectionMatrix().mul(camera.viewMatrix()).invert());
                program.vector("HighlightClip", 1, 0, -1, 0);
                double chunkX = Math.floor(camera.x() / 16), chunkZ = Math.floor(camera.z() / 16);
                program.vector("HighlightGrid", (float) chunkX, (float) chunkZ, field.mask(), field.probes());
                program.vector("HighlightOffset", (float) (camera.x() - 16 * chunkX),
                        (float) (camera.z() - 16 * chunkZ), 0, 0);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                GL33.glBindSampler(0, 0);
                GlStateManager._bindTexture(depthTexture);
                program.sampler("TerrainDepth", 0);
                GlStateManager._activeTexture(GL13.GL_TEXTURE1);
                GL33.glBindSampler(1, 0);
                GlStateManager._bindTexture(atlas.getId());
                program.sampler("HighlightField", 1);
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
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            }
        }
    }

    private void upload(MapHighlightField field) {
        if (atlas == null || field.width() != uploaded.width() || field.height() != uploaded.height()) {
            if (atlas != null) atlas.close();
            atlas = new DynamicTexture(field.width(), field.height(), false);
        }
        var image = atlas.getPixels();
        for (int i = 0; i < field.pixels().length; i++)
            image.setPixelRGBA(i % field.width(), i / field.width(), field.pixels()[i]);
        atlas.upload();
        uploaded = field;
    }

    @Override public void close() {
        if (atlas != null) atlas.close();
        if (program != null) {
            program.close();
            GL30.glDeleteFramebuffers(colorTarget);
            GL30.glDeleteVertexArrays(triangle);
        }
        atlas = null;
        uploaded = null;
        program = null;
    }
}
