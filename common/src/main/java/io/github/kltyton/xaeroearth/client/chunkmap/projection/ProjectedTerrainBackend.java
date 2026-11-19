package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Device-specific operations for the shared projected-image scheduler. */
public interface ProjectedTerrainBackend<I, A, U> extends AutoCloseable {
    record Draw<I>(ProjectedGrid.Cell cell, I image, double cameraX, double cameraY, double cameraZ) { }

    default String cacheVariant() { return ""; }
    default void beginCompositeFrame() { }
    I bake(ProjectedGrid grid, ProjectedGrid.Cell cell, List<KuiNativeTerrainRenderer.Draw> draws, Draw<I> previous);
    /** Takes ownership of the disk image, including on failure. */
    U prepareUpload(ProjectedTileStore.Image image);
    I upload(U data);
    A allocate(double cameraX, double cameraY, double cameraZ);
    Optional<CompletableFuture<ProjectedTileStore.Image>> readback(I image, A allocation, Executor storage);
    void composite(RenderTarget target, Object depth, ProjectedGrid grid, float brightness, List<Draw<I>> draws);
    void closeImage(I image);
    void closeAllocation(A allocation);
    void closeUpload(U upload);
    void poll();
    @Override void close();
}
