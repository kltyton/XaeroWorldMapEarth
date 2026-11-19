package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;

/** Reads 26.1 color attachments asynchronously, then converts their data off the render thread. */
public final class NativeProjectedTileReadback {
    private static final int PIXELS = ProjectedGrid.IMAGE_PIXELS * ProjectedGrid.IMAGE_PIXELS;
    private static final int BYTES = PIXELS * 4;

    /** Allocated on the storage worker before submission; owns all buffers until completion. */
    public static final class Staging implements AutoCloseable {
        private final ProjectedTileStore.Image image;
        private boolean closed;

        private Staging(ProjectedTileStore.Image image) { this.image = image; }

        public static Staging allocate(double cameraX, double cameraY, double cameraZ) {
            return new Staging(ProjectedTileStore.Image.allocate(cameraX, cameraY, cameraZ,
                    NativeProjectedDevice.reverseDepth(), ProjectedTileStore.Image.ColorFormat.RGBA8));
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            image.close();
        }

        private synchronized ProjectedTileStore.Image finish() {
            if (closed) throw new IllegalStateException("Projected readback is closed");
            closed = true;
            return image;
        }
    }

    /** Takes ownership of the staging buffers and returns the image to the caller on completion. */
    public CompletableFuture<ProjectedTileStore.Image> submit(NativeProjectedTileBaker.Tile tile,
                                                              Staging staging, Executor storage) {
        if (!Minecraft.getInstance().isSameThread())
            throw new IllegalStateException("Projected readback submission requires the client thread");
        var result = new Result();
        var copies = new ArrayList<CompletableFuture<Void>>(3);
        try {
            copies.add(read(tile.low().getColorTexture(), staging.image.mutableLow()));
            copies.add(read(tile.high().getColorTexture(), staging.image.mutableHigh()));
            copies.add(read(tile.encodedDepth().getColorTexture(), staging.image.mutableDepth()));
        } catch (Error fatal) {
            CompletableFuture.allOf(copies.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, failure) -> staging.close());
            throw fatal;
        }
        CompletableFuture.allOf(copies.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (failure != null) {
                staging.close();
                result.completeExceptionally(failure);
                return;
            }
            try {
                storage.execute(() -> {
                    try {
                        var image = staging.finish();
                        if (!result.complete(image)) image.close();
                    } catch (RuntimeException | Error error) {
                        staging.close();
                        result.completeExceptionally(error);
                        if (error instanceof Error fatal) throw fatal;
                    }
                });
            } catch (RuntimeException | Error error) {
                staging.close();
                result.completeExceptionally(error);
                if (error instanceof Error fatal) throw fatal;
            }
        });
        return result;
    }

    private static CompletableFuture<Void> read(GpuTexture texture, ByteBuffer destination) {
        var result = new CompletableFuture<Void>();
        GpuBuffer buffer = null;
        try {
            buffer = RenderSystem.getDevice().createBuffer(() -> "Earth projected image transfer",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, BYTES);
            var submitted = buffer;
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, submitted, 0, () -> {
                try (submitted) {
                    NativeProjectedDevice.copyMapped(submitted, destination, BYTES);
                    result.complete(null);
                } catch (RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                    if (failure instanceof Error fatal) throw fatal;
                }
            }, 0, 0, 0, ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS);
        } catch (RuntimeException | Error failure) {
            if (buffer != null) buffer.close();
            result.completeExceptionally(failure);
            if (failure instanceof Error fatal) throw fatal;
        }
        return result;
    }

    private static final class Result extends CompletableFuture<ProjectedTileStore.Image> {
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
    }
}
