package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/** Rasterizes the shared endpoint and depth images using the active render device. */
public final class NativeProjectedTileBaker implements AutoCloseable {
    private static final int MAX_POOLED = 32;
    private static final RenderPipeline COVERAGE = NativeProjectedDevice.coveragePipeline();
    private static final RenderPipeline DEPTH = NativeProjectedDevice.depthPipeline();
    private final KuiNativeTerrainRenderer renderer = new KuiNativeTerrainRenderer();
    private final ArrayDeque<Tile> pool = new ArrayDeque<>(MAX_POOLED);

    public static final class Tile implements AutoCloseable {
        private final TextureTarget low, high, depth;
        private boolean closed;
        private Tile(TextureTarget low, TextureTarget high, TextureTarget depth) {
            this.low = low; this.high = high; this.depth = depth;
        }
        public TextureTarget low() { return low; }
        public TextureTarget high() { return high; }
        public TextureTarget encodedDepth() { return depth; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            low.destroyBuffers(); high.destroyBuffers(); depth.destroyBuffers();
        }
    }

    private Tile acquire() {
        Tile pooled = pool.pollLast();
        if (pooled != null && !pooled.closed) return pooled;
        TextureTarget low = null, high = null, depth = null;
        try {
            low = NativeProjectedDevice.target("Earth projected light minimum", true);
            high = NativeProjectedDevice.target("Earth projected light maximum", true);
            depth = NativeProjectedDevice.target("Earth projected encoded depth", false);
            return new Tile(low, high, depth);
        } catch (RuntimeException | Error failure) {
            if (low != null) low.destroyBuffers();
            if (high != null) high.destroyBuffers();
            if (depth != null) depth.destroyBuffers();
            throw failure;
        }
    }

    public void recycle(Tile tile) {
        requireClientThread();
        if (tile == null || tile.closed) return;
        if (pool.size() < MAX_POOLED) pool.addLast(tile);
        else tile.close();
    }

    public Tile bake(ProjectedGrid grid, ProjectedGrid.Cell cell, List<KuiNativeTerrainRenderer.Draw> draws,
                     NativeProjectedTileComposite composite, NativeProjectedTileComposite.Draw previous) {
        requireClientThread();
        Tile tile = acquire();
        try {
            var projection = projection(grid, cell);
            render(tile.low, projection, draws, 0, composite, grid, previous);
            render(tile.high, projection, draws, 1, composite, grid, previous);
            encode(COVERAGE, tile.low, tile.high);
            encode(COVERAGE, tile.high, tile.high);
            encode(DEPTH, tile.depth, tile.high);
            return tile;
        } catch (RuntimeException | Error failure) {
            tile.close();
            throw failure;
        }
    }

    public Tile upload(NativeProjectedUpload upload) {
        requireClientThread();
        Tile tile = acquire();
        try {
            NativeProjectedDevice.upload(tile.low, upload.low);
            NativeProjectedDevice.upload(tile.high, upload.high);
            NativeProjectedDevice.upload(tile.depth, upload.depth);
            return tile;
        } catch (RuntimeException | Error failure) {
            tile.close();
            throw failure;
        }
    }

    private static Matrix4f projection(ProjectedGrid grid, ProjectedGrid.Cell cell) {
        return NativeProjectedDevice.projection(grid.tileProjection(cell));
    }

    private void render(TextureTarget target, Matrix4f projection, List<KuiNativeTerrainRenderer.Draw> draws, float sky,
                        NativeProjectedTileComposite composite, ProjectedGrid grid, NativeProjectedTileComposite.Draw previous) {
        NativeProjectedDevice.clear(target);
        if (previous != null) composite.seed(target, grid, sky, previous);
        var endpoints = new ArrayList<KuiNativeTerrainRenderer.Draw>(draws.size());
        for (var draw : draws) endpoints.add(new KuiNativeTerrainRenderer.Draw(
                draw.mesh(), draw.modelView(), draw.chunkMask(), sky));
        renderer.drawOpaque(target, projection, false, endpoints);
    }

    private static void encode(RenderPipeline pipeline, TextureTarget output, TextureTarget input) {
        NativeProjectedDevice.encode(pipeline, output, input);
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Projected tile baking requires the client thread");
    }

    @Override public void close() {
        requireClientThread();
        while (!pool.isEmpty()) pool.removeLast().close();
        renderer.close();
    }
}
