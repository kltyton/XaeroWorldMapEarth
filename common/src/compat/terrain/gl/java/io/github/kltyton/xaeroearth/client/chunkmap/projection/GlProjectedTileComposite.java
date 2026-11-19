package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

/** Depth-composites stationary projected images into the live map surface. */
public final class GlProjectedTileComposite implements AutoCloseable {
    public record Draw(ProjectedGrid.Cell cell, ProjectedGrid.View view, GlProjectedTileBaker.Tile image,
                       double cameraX, double cameraY, double cameraZ) { }
    private KuiMapGlProgram program;
    private int array;

    public void draw(RenderTarget target, KuiMapDepthTarget depth, ProjectedGrid grid, float sky, List<Draw> draws) {
        draw(target, depth, grid, sky, draws, false);
    }

    void seed(RenderTarget target, KuiMapDepthTarget depth, ProjectedGrid grid, float sky, Draw draw) {
        draw(target, depth, grid, sky, List.of(draw), true);
    }

    private void draw(RenderTarget target, KuiMapDepthTarget depth, ProjectedGrid grid, float sky,
                      List<Draw> draws, boolean entireImage) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Projected tile drawing must use the client thread");
        if (draws.isEmpty()) return;
        try (var state = new KuiMapRenderState(target, depth); var stack = MemoryStack.stackPush()) {
            if (program == null) {
                program = new KuiMapGlProgram("xaeroearth", "projected_tile");
                array = GL30.glGenVertexArrays();
            }
            int active = GlStateManager._getActiveTexture();
            int oldLow = bind(GL13.GL_TEXTURE0, 0);
            int oldSampler0 = unbindSampler(0);
            int oldHigh = bind(GL13.GL_TEXTURE1, 0);
            int oldSampler1 = unbindSampler(1);
            int oldDepth = bind(GL13.GL_TEXTURE2, 0);
            int oldSampler2 = unbindSampler(2);
            var scissor = stack.mallocInt(4);
            boolean wasScissored = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
            try {
                RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL); RenderSystem.depthMask(true);
                RenderSystem.disableCull(); RenderSystem.disableBlend();
                if (entireImage) GlStateManager._disableScissorTest();
                else GlStateManager._enableScissorTest();
                program.bind();
                program.sampler("LowColor", 0); program.sampler("HighColor", 1); program.sampler("TileDepth", 2);
                GlStateManager._glBindVertexArray(array);
                for (Draw draw : draws) {
                    if (!grid.view().equals(draw.view())) throw new IllegalArgumentException("Projected image view changed");
                    if (!entireImage) {
                        var clip = grid.clip(draw.cell());
                        if (clip == null) continue;
                        GlStateManager._scissorBox(clip.x(), clip.y(), clip.width(), clip.height());
                    }
                    setTexture(GL13.GL_TEXTURE0, draw.image().lowColor());
                    setTexture(GL13.GL_TEXTURE1, draw.image().highColor());
                    setTexture(GL13.GL_TEXTURE2, draw.image().depth());
                    float x = entireImage ? 0 : (float) grid.cellScreenX(draw.cell().x());
                    float y = entireImage ? 0 : (float) grid.cellScreenY(draw.cell().y());
                    program.vector("Rect", x, y, target.width, target.height);
                    float shift = (float) grid.depthOffsetFrom(draw.cameraX(), draw.cameraY(), draw.cameraZ(), false);
                    program.vector("TileParams", sky, shift,
                            entireImage ? ProjectedGrid.IMAGE_PIXELS : ProjectedGrid.TILE_PIXELS,
                            entireImage ? 0 : ProjectedGrid.GUTTER);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                }
            } finally {
                GlStateManager._scissorBox(scissor.get(0), scissor.get(1), scissor.get(2), scissor.get(3));
                if (wasScissored) GlStateManager._enableScissorTest();
                else GlStateManager._disableScissorTest();
                setTexture(GL13.GL_TEXTURE2, oldDepth); GL33.glBindSampler(2, oldSampler2);
                setTexture(GL13.GL_TEXTURE1, oldHigh); GL33.glBindSampler(1, oldSampler1);
                setTexture(GL13.GL_TEXTURE0, oldLow); GL33.glBindSampler(0, oldSampler0);
                GlStateManager._activeTexture(active);
            }
        }
    }

    private static int unbindSampler(int unit) {
        int previous = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        // Active world shader packs (Iris/Oculus) can leave shadow-comparison samplers bound on texture units.
        GL33.glBindSampler(unit, 0);
        return previous;
    }

    private static int bind(int unit, int texture) {
        GlStateManager._activeTexture(unit);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(texture);
        return previous;
    }

    private static void setTexture(int unit, int texture) {
        GlStateManager._activeTexture(unit);
        GlStateManager._bindTexture(texture);
    }

    @Override public void close() {
        if (program != null) { program.close(); program = null; }
        if (array != 0) { GL30.glDeleteVertexArrays(array); array = 0; }
    }
}
