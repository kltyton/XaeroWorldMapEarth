package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/** Copies shader-pack terrain pixels and their matching depth into the Earth map. */
final class GlMapPackComposite implements AutoCloseable {
    private KuiMapGlProgram program;
    private int array;

    void render(RenderTarget input, RenderTarget output, KuiMapDepthTarget depth, MapPackDepthMapping mapping,
                MapPackRegion region, MapPackLodTransition<Integer> transition) {
        try (var scope = new KuiMapRenderState(output, depth)) {
            if (program == null) {
                program = new KuiMapGlProgram("xaeroearth", "map_pack_composite");
                array = GL30.glGenVertexArrays();
            }
            int active = GlStateManager._getActiveTexture();
            int color = input.getColorTextureId();
            int[] textures = {color, input.getDepthTextureId(), transition == null ? color : transition.color(),
                    transition == null ? color : transition.height(), transition == null ? color : transition.coverage()};
            int[] previous = new int[textures.length];
            int[] samplers = new int[textures.length];
            for (int i = 0; i < textures.length; i++) {
                previous[i] = bind(GL13.GL_TEXTURE0 + i, textures[i]);
                samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                // A world shader can leave a shadow comparison sampler on the raw depth unit.
                GL33.glBindSampler(i, 0);
            }
            try {
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.depthMask(true);
                RenderSystem.disableCull();
                RenderSystem.disableBlend();
                program.bind();
                program.sampler("PackColor", 0);
                program.sampler("PackDepth", 1);
                program.sampler("CoarseColor", 2);
                program.sampler("CoarseHeight", 3);
                program.sampler("DetailCoverage", 4);
                program.vector("PackRaster", mapping.xScale(), mapping.yScale(), mapping.xOffset(), mapping.yOffset());
                program.vector("PackOrigin", region.x(), region.bottom(), 0, 0);
                var view = transition == null ? MapPackLodTransition.View.DISABLED : transition.view();
                program.matrix("PackWorldFromClip", view.inverse());
                program.vector("CoarseSurface", view.originX(), view.originZ(), view.step(), view.brightness());
                program.vector("CoarseGrid", view.coverageX(), view.coverageZ(), view.width(), view.cameraY());
                program.vector("CoarseDepth", view.depthScale(), view.depthBias(), view.packedHeight(), 0);
                GlStateManager._glBindVertexArray(array);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            } finally {
                for (int i = previous.length - 1; i >= 0; i--) {
                    bind(GL13.GL_TEXTURE0 + i, previous[i]);
                    GL33.glBindSampler(i, samplers[i]);
                }
                GlStateManager._activeTexture(active);
            }
        }
    }

    private static int bind(int unit, int texture) {
        GlStateManager._activeTexture(unit);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(texture);
        return previous;
    }

    @Override public void close() {
        if (program != null) { program.close(); program = null; }
        if (array != 0) GL30.glDeleteVertexArrays(array);
        array = 0;
    }
}
