package io.github.kltyton.xaeroearth.client.render.map;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Recolors only pixels whose map depth still contains the clear value. */
public final class MapFogRenderer implements AutoCloseable {
    private KuiMapGlProgram program;
    private int vao;

    public void draw(RenderTarget target, MapCamera camera, boolean cave, KuiMapDepthTarget depth) {
        try (var state = new KuiMapRenderState(target, depth)) {
            if (program == null) {
                program = new KuiMapGlProgram("xaeroearth", "map_fog");
                vao = GL30.glGenVertexArrays();
            }
            var view = MapFogView.of(camera, cave, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_EQUAL);
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            program.bind();
            program.vector("FogView", view.aspect(), view.cave(), view.far(), 0);
            GlStateManager._glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }
    }

    @Override public void close() {
        if (program == null) return;
        program.close();
        GL30.glDeleteVertexArrays(vao);
        program = null;
    }
}
