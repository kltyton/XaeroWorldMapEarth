package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.render.gl.GlPixelTransferLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/** Rasterizes opaque static terrain into independently owned color and depth images. */
public final class GlProjectedTileBaker implements AutoCloseable {
    private static final int MAX_POOLED = 32;
    private final KuiNativeTerrainRenderer renderer = new KuiNativeTerrainRenderer();
    private final java.util.ArrayDeque<Tile> pool = new java.util.ArrayDeque<>(MAX_POOLED);
    private KuiMapGlProgram coverage;
    private int coverageArray;
    private boolean closed;

    public static final class Tile implements AutoCloseable {
        private final TextureTarget low, high;
        private boolean closed;

        private Tile(TextureTarget low, TextureTarget high) { this.low = low; this.high = high; }
        public int lowColor() { return low.getColorTextureId(); }
        public int highColor() { return high.getColorTextureId(); }
        public int depth() { return low.getDepthTextureId(); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            low.destroyBuffers(); high.destroyBuffers();
        }
    }

    private Tile acquire() {
        Tile recycled = pool.pollFirst();
        if (recycled != null && !recycled.closed) return recycled;
        TextureTarget low = null, high = null;
        try {
            low = target(true); high = target(false);
            return new Tile(low, high);
        } catch (RuntimeException | Error failure) {
            if (low != null) low.destroyBuffers();
            if (high != null) high.destroyBuffers();
            throw failure;
        }
    }

    public void recycle(Tile tile) {
        if (tile == null || tile.closed) return;
        if (!closed && pool.size() < MAX_POOLED) pool.addFirst(tile);
        else tile.close();
    }

    /** The caller retains the supplied GPU meshes; both endpoint images use the same geometry. */
    public Tile bake(ProjectedGrid grid, ProjectedGrid.Cell cell, List<KuiNativeTerrainRenderer.Draw> draws,
                     GlProjectedTileComposite composite, GlProjectedTileComposite.Draw previous) {
        requireClientThread();
        Tile tile = null;
        try (var graphics = new KuiMapRenderState(null)) {
            boolean wasScissored = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            // A HUD or GUI scissor box must never clip offscreen tile clears, depth blits, or coverage passes.
            if (wasScissored) GlStateManager._disableScissorTest();
            try {
                tile = acquire();
                Matrix4f projection = grid.tileProjection(cell);
                render(tile.low, projection, draws, 0, composite, grid, previous);
                render(tile.high, projection, draws, 1, composite, grid, previous);
                copyDepth(tile);
                setCoverage(tile.low, tile.low.getDepthTextureId());
                setCoverage(tile.high, tile.low.getDepthTextureId());
                return tile;
            } finally {
                if (wasScissored) GlStateManager._enableScissorTest();
            }
        } catch (RuntimeException | Error failure) {
            if (tile != null) tile.close();
            throw failure;
        }
    }

    private void copyDepth(Tile tile) {
        try (var state = new KuiMapRenderState(tile.high, renderer.depthTarget())) {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, tile.high.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, tile.low.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS,
                    0, 0, ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }
    }

    private void setCoverage(TextureTarget target, int depth) {
        try (var state = new KuiMapRenderState(target, renderer.depthTarget())) {
            if (coverage == null) {
                coverage = new KuiMapGlProgram("xaeroearth", "projected_coverage");
                coverageArray = GL30.glGenVertexArrays();
            }
            int active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int sampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            try {
                GL33.glBindSampler(0, 0);
                GlStateManager._bindTexture(depth);
                RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
                RenderSystem.disableCull(); RenderSystem.disableBlend();
                RenderSystem.colorMask(false, false, false, true);
                coverage.bind(); coverage.sampler("TileDepth", 0);
                GlStateManager._glBindVertexArray(coverageArray);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            } finally {
                GL33.glBindSampler(0, sampler);
                GlStateManager._bindTexture(previous);
                GlStateManager._activeTexture(active);
            }
        }
    }

    /** Uploads a decoded disk image without changing ownership of its native buffers. */
    public Tile upload(ProjectedTileStore.Image image) {
        requireClientThread();
        if (image.reverseDepth()) throw new IllegalArgumentException("OpenGL projected images require forward depth");
        if (image.colorFormat() != ProjectedTileStore.Image.ColorFormat.RGBA16F)
            throw new IllegalArgumentException("OpenGL projected images require half-float color");
        Tile tile = null;
        try (var graphics = new KuiMapRenderState(null)) {
            tile = acquire();
            int active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            try (var layout = GlPixelTransferLayout.unpack(ByteOrder.nativeOrder())) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                uploadTexture(tile.low.getColorTextureId(), GL11.GL_RGBA, GL30.GL_HALF_FLOAT, image.low());
                uploadTexture(tile.high.getColorTextureId(), GL11.GL_RGBA, GL30.GL_HALF_FLOAT, image.high());
                uploadTexture(tile.low.getDepthTextureId(), GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, image.depth());
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
                GlStateManager._bindTexture(previous);
                GlStateManager._activeTexture(active);
            }
            return tile;
        } catch (RuntimeException | Error failure) {
            if (tile != null) tile.close();
            throw failure;
        }
    }

    private static void uploadTexture(int texture, int format, int type, ByteBuffer pixels) {
        GlStateManager._bindTexture(texture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS, format, type, pixels);
    }

    private void render(TextureTarget target, Matrix4f projection, List<KuiNativeTerrainRenderer.Draw> draws, float sky,
                        GlProjectedTileComposite composite, ProjectedGrid grid, GlProjectedTileComposite.Draw previous) {
        try (var state = new KuiMapRenderState(target, renderer.depthTarget())) {
            RenderSystem.depthMask(true);
            GlStateManager._clearColor(0, 0, 0, 0);
            GlStateManager._clearDepth(1);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
        if (previous != null) composite.seed(target, renderer.depthTarget(), grid, sky, previous);
        var endpoint = new ArrayList<KuiNativeTerrainRenderer.Draw>(draws.size());
        for (var draw : draws) endpoint.add(new KuiNativeTerrainRenderer.Draw(draw.mesh(), draw.modelView(), draw.chunkMask(), sky));
        renderer.drawOpaque(target, projection, false, endpoint);
    }

    private static TextureTarget target(boolean depth) {
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        TextureTarget target = null;
        try {
            target = new TextureTarget(ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS, depth, Minecraft.ON_OSX);
            GlStateManager._bindTexture(target.getColorTextureId());
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
                    ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS, 0,
                    GL11.GL_RGBA, GL30.GL_HALF_FLOAT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            if (depth) {
                // The source KUI depth is DEPTH_COMPONENT32F; depth blits require matching formats.
                GlStateManager._bindTexture(target.getDepthTextureId());
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
                        ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS, 0,
                        GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            }
            return target;
        } catch (RuntimeException | Error failure) {
            if (target != null) target.destroyBuffers();
            throw failure;
        } finally {
            GlStateManager._bindTexture(previous);
            GlStateManager._activeTexture(active);
        }
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Projected tile baking must use the client thread");
    }

    @Override public void close() {
        requireClientThread();
        closed = true;
        while (!pool.isEmpty()) pool.removeFirst().close();
        if (coverage != null) { coverage.close(); coverage = null; }
        if (coverageArray != 0) { GL30.glDeleteVertexArrays(coverageArray); coverageArray = 0; }
        renderer.close();
    }
}
