package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** OpenGL device adapter for the shared projected-image scheduler. */
public final class GlProjectedTerrain implements AutoCloseable {
    private final ProjectedTerrain<GlProjectedTileBaker.Tile, ProjectedTileStore.Image, ProjectedTileStore.Image> terrain =
            new ProjectedTerrain<>(new Backend());

    public ProjectionPreflight preflight(RenderTarget target, MapCamera camera, List<ProjectedTileIndex.Mesh> meshes,
                                         ChunkTileSource source, MapScope scope) {
        return terrain.preflight(target, camera, meshes, source, scope);
    }

    public ProjectedDrawCoverage draw(RenderTarget target, KuiMapDepthTarget depth, MapCamera camera,
                        List<KuiNativeTerrainRenderer.Draw> draws, List<ProjectedTileIndex.Mesh> meshes,
                        ChunkTileSource source, MapScope scope, float brightness) {
        return terrain.draw(target, depth, camera, draws, meshes, source, scope, brightness);
    }

    public void reset() { terrain.reset(); }
    public void poll() { terrain.poll(); }
    public ProjectedTerrain.Diagnostics diagnostics() { return terrain.diagnostics(); }
    @Override public void close() { terrain.close(); }

    private static final class Backend implements ProjectedTerrainBackend<GlProjectedTileBaker.Tile,
            ProjectedTileStore.Image, ProjectedTileStore.Image> {
        private final GlProjectedTileBaker baker = new GlProjectedTileBaker();
        private final GlProjectedTileComposite composite = new GlProjectedTileComposite();
        private final GlProjectedTileReadback readback = new GlProjectedTileReadback();

        // Isolate legacy GL projected disk tiles baked before external GL_SCISSOR_TEST and
        // pixel-unpack state were reset in GlProjectedTileBaker, without invalidating model caches.
        @Override public String cacheVariant() { return "|gl-scissor-v1"; }

        @Override public GlProjectedTileBaker.Tile bake(ProjectedGrid grid, ProjectedGrid.Cell cell,
                                                       List<KuiNativeTerrainRenderer.Draw> draws,
                                                       Draw<GlProjectedTileBaker.Tile> previous) {
            return baker.bake(grid, cell, draws, composite, previous == null ? null : view(grid, previous));
        }
        @Override public ProjectedTileStore.Image prepareUpload(ProjectedTileStore.Image image) { return image; }
        @Override public GlProjectedTileBaker.Tile upload(ProjectedTileStore.Image image) { return baker.upload(image); }
        @Override public ProjectedTileStore.Image allocate(double x, double y, double z) {
            return ProjectedTileStore.Image.allocate(x, y, z, false);
        }
        @Override public Optional<CompletableFuture<ProjectedTileStore.Image>> readback(
                GlProjectedTileBaker.Tile image, ProjectedTileStore.Image allocation, Executor storage) {
            return readback.submit(image, allocation);
        }
        @Override public void composite(RenderTarget target, Object depth, ProjectedGrid grid, float brightness,
                                        List<Draw<GlProjectedTileBaker.Tile>> draws) {
            composite.draw(target, (KuiMapDepthTarget) depth, grid, brightness,
                    draws.stream().map(draw -> view(grid, draw)).toList());
        }
        private GlProjectedTileComposite.Draw view(ProjectedGrid grid, Draw<GlProjectedTileBaker.Tile> draw) {
            return new GlProjectedTileComposite.Draw(draw.cell(), grid.view(), draw.image(), draw.cameraX(),
                    draw.cameraY(), draw.cameraZ());
        }
        @Override public void closeImage(GlProjectedTileBaker.Tile image) { baker.recycle(image); }
        @Override public void closeAllocation(ProjectedTileStore.Image allocation) { allocation.close(); }
        @Override public void closeUpload(ProjectedTileStore.Image upload) { upload.close(); }
        @Override public void poll() { readback.poll(); }
        @Override public void close() { readback.close(); composite.close(); baker.close(); }
    }
}
