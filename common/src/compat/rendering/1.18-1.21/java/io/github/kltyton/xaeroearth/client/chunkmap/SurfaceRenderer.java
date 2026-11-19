package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import com.mojang.blaze3d.platform.GlStateManager;
import io.github.kltyton.xaeroearth.client.render.gl.GlPixelTransferLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

final class SurfaceRenderer implements AutoCloseable {
    private final KuiMapGlProgram shader = new KuiMapGlProgram("xaeroearth", "native_surface");
    private final int vertices = GL30.glGenVertexArrays();
    private record Textures(int color, int height, int width, int depth, int heightRows) { }
    private final SurfaceTransfer<Textures> transfer = new SurfaceTransfer<>(ByteOrder.nativeOrder()) {
        @Override boolean matches(Textures textures, NativeTerrainScene.Surface data) {
            return textures.width() == data.width() && textures.depth() == data.depth()
                    && textures.heightRows() == data.heightTextureRows();
        }

        @Override Textures allocate(NativeTerrainScene.Surface data) {
            int color = GL11.glGenTextures(), height = GL11.glGenTextures();
            var textures = new Textures(color, height, data.width(), data.depth(), data.heightTextureRows());
            try {
                texture(color, GL11.GL_LINEAR);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, textures.width(), textures.depth(),
                        0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                texture(height, GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, textures.width(), textures.heightRows(),
                        0, GL11.GL_RED, GL11.GL_FLOAT, (ByteBuffer) null);
                return textures;
            } catch (RuntimeException | Error failure) {
                release(textures);
                throw failure;
            }
        }

        @Override void color(Textures textures, ByteBuffer data, int width, int row, int rows) {
            GlStateManager._bindTexture(textures.color());
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, row, width, rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        }

        @Override void height(Textures textures, ByteBuffer data, int width, int row, int rows) {
            GlStateManager._bindTexture(textures.height());
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, row, width, rows, GL11.GL_RED, GL11.GL_FLOAT, data);
        }

        @Override void release(Textures textures) {
            GlStateManager._deleteTexture(textures.color());
            GlStateManager._deleteTexture(textures.height());
        }
    };

    void update(NativeTerrainScene.Surface next) {
        if (!transfer.hasWork(next)) return;
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try (var layout = GlPixelTransferLayout.unpack(ByteOrder.nativeOrder())) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            transfer.update(next);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
        }
    }

    boolean draw(MapCamera camera, int coverage, int coverageX, int coverageZ, float brightness) {
        var surface = surface();
        if (surface == null) return false;
        shader.bind();
        shader.matrix("ProjMat", camera.projectionMatrix());
        shader.matrix("ModelViewMat", camera.viewMatrix());
        shader.vector("Grid", surface.width(), surface.depth(), surface.step(), brightness);
        shader.vector("CoverageRegion", (float) (camera.x() - coverageX * 16L), (float) (camera.z() - coverageZ * 16L), surface.patchCount(), surface.patchCount() + surface.outlineCount());
        shader.vector("SurfaceOrigin", (float) (surface.x() - camera.x()), (float) -camera.y(),
                (float) (surface.z() - camera.z()), surface.floor());
        var visible = transfer.textures();
        int[] textures = {visible.color(), visible.height(), coverage};
        String[] samplers = {"SurfaceColor", "SurfaceHeight", "Coverage"};
        int active = GlStateManager._getActiveTexture();
        int[] prevTextures = new int[textures.length];
        int[] prevSamplers = new int[textures.length];
        for (int i = 0; i < textures.length; i++) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
            prevTextures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            prevSamplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            GL33.glBindSampler(i, 0);
            GlStateManager._bindTexture(textures[i]);
            shader.sampler(samplers[i], i);
        }
        try {
            GlStateManager._glBindVertexArray(vertices);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, surface.vertexCount());
            return true;
        } finally {
            for (int i = textures.length - 1; i >= 0; i--) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(prevTextures[i]);
                GL33.glBindSampler(i, prevSamplers[i]);
            }
            GlStateManager._activeTexture(active);
        }
    }

    NativeTerrainScene.Surface surface() { return transfer.surface(); }
    int colorTexture() { return transfer.textures().color(); }
    int heightTexture() { return transfer.textures().height(); }

    private static void texture(int id, int filter) {
        GlStateManager._bindTexture(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    void reset() { transfer.reset(); }

    @Override public void close() {
        transfer.close(); shader.close(); GL30.glDeleteVertexArrays(vertices);
    }
}
