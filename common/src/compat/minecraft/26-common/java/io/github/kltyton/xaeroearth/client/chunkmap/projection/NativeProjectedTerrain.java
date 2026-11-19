package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeUniformFrameScope;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Minecraft render-device adapter for projected map images. */
public final class NativeProjectedTerrain implements AutoCloseable {
    private final ProjectedTerrain<NativeProjectedTileBaker.Tile, NativeProjectedTileReadback.Staging,
            NativeProjectedUpload> terrain = new ProjectedTerrain<>(new Backend());

    public ProjectionPreflight preflight(RenderTarget target, MapCamera camera, List<ProjectedTileIndex.Mesh> meshes,
                                         ChunkTileSource source, MapScope scope) {
        return terrain.preflight(target, camera, meshes, source, scope);
    }

    public ProjectedDrawCoverage draw(RenderTarget target, MapCamera camera, List<KuiNativeTerrainRenderer.Draw> draws,
                        List<ProjectedTileIndex.Mesh> meshes, ChunkTileSource source, MapScope scope,
                        float brightness) {
        try (var frame = NativeUniformFrameScope.open()) {
            return terrain.draw(target, null, camera, draws, meshes, source, scope, brightness);
        }
    }

    public void reset() { terrain.reset(); }
    public void poll() { terrain.poll(); }
    public ProjectedTerrain.Diagnostics diagnostics() { return terrain.diagnostics(); }
    @Override public void close() { terrain.close(); }

    private static final class Backend implements ProjectedTerrainBackend<NativeProjectedTileBaker.Tile,
            NativeProjectedTileReadback.Staging, NativeProjectedUpload> {
        private static final int MAX_PENDING = 4;
        private final NativeProjectedTileBaker baker = new NativeProjectedTileBaker();
        private final NativeProjectedTileComposite composite = new NativeProjectedTileComposite();
        private final NativeProjectedTileReadback readback = new NativeProjectedTileReadback();
        private final AtomicInteger pending = new AtomicInteger();

        @Override public String cacheVariant() { return NativeProjectedDevice.cacheVariant(); }
        @Override public void beginCompositeFrame() { composite.beginFrame(); }
        @Override public NativeProjectedTileBaker.Tile bake(ProjectedGrid grid, ProjectedGrid.Cell cell,
                                                           List<KuiNativeTerrainRenderer.Draw> draws,
                                                           Draw<NativeProjectedTileBaker.Tile> previous) {
            return baker.bake(grid, cell, draws, composite, previous == null ? null : view(previous));
        }
        @Override public NativeProjectedUpload prepareUpload(ProjectedTileStore.Image image) {
            return NativeProjectedUpload.from(image);
        }
        @Override public NativeProjectedTileBaker.Tile upload(NativeProjectedUpload upload) {
            return baker.upload(upload);
        }
        @Override public NativeProjectedTileReadback.Staging allocate(double x, double y, double z) {
            return NativeProjectedTileReadback.Staging.allocate(x, y, z);
        }
        @Override public Optional<CompletableFuture<ProjectedTileStore.Image>> readback(
                NativeProjectedTileBaker.Tile image, NativeProjectedTileReadback.Staging allocation, Executor storage) {
            if (pending.get() >= MAX_PENDING) return Optional.empty();
            pending.incrementAndGet();
            try {
                var transfer = readback.submit(image, allocation, storage);
                transfer.whenComplete((ignored, failure) -> pending.decrementAndGet());
                return Optional.of(transfer);
            } catch (RuntimeException | Error failure) {
                pending.decrementAndGet();
                throw failure;
            }
        }
        @Override public void composite(RenderTarget target, Object depth, ProjectedGrid grid, float brightness,
                                        List<Draw<NativeProjectedTileBaker.Tile>> draws) {
            composite.draw(target, grid, brightness, draws.stream().map(this::view).toList());
        }
        private NativeProjectedTileComposite.Draw view(Draw<NativeProjectedTileBaker.Tile> draw) {
            return new NativeProjectedTileComposite.Draw(draw.cell(), draw.image(), draw.cameraX(),
                    draw.cameraY(), draw.cameraZ());
        }
        @Override public void closeImage(NativeProjectedTileBaker.Tile image) { baker.recycle(image); }
        @Override public void closeAllocation(NativeProjectedTileReadback.Staging allocation) { allocation.close(); }
        @Override public void closeUpload(NativeProjectedUpload upload) { upload.close(); }
        @Override public void poll() { }
        @Override public void close() { composite.close(); baker.close(); }
    }
}
