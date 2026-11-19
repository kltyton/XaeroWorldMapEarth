package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** Transfers projected images through fenced pixel-pack buffers without waiting for the GPU. */
public final class GlProjectedTileReadback implements AutoCloseable {
    private static final int PIXELS = ProjectedGrid.IMAGE_PIXELS * ProjectedGrid.IMAGE_PIXELS;
    private static final int COLOR_BYTES = PIXELS * 8, DEPTH_BYTES = PIXELS * Float.BYTES;
    private static final int BYTES = 2 * COLOR_BYTES + DEPTH_BYTES, MAX_PENDING = 4;
    private record Transfer(int buffer, long fence, ProjectedTileStore.Image image,
                            CompletableFuture<ProjectedTileStore.Image> result) { }
    private final ArrayDeque<Transfer> pending = new ArrayDeque<>();
    private final ArrayDeque<Integer> available = new ArrayDeque<>();
    private boolean closed;

    /** On success, ownership of the preallocated image passes to the returned future. */
    public Optional<CompletableFuture<ProjectedTileStore.Image>> submit(
            GlProjectedTileBaker.Tile tile, ProjectedTileStore.Image image) {
        requireClientThread();
        if (closed) throw new IllegalStateException("Projected tile readback is closed");
        if (image.reverseDepth()) throw new IllegalArgumentException("OpenGL projected images require forward depth");
        if (pending.size() >= MAX_PENDING) return Optional.empty();
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int row = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH), skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS), align = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int swap = GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES);
        boolean created = available.isEmpty();
        int buffer = created ? GL15.glGenBuffers() : available.removeFirst();
        boolean submitted = false;
        try {
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            if (created) GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, BYTES, GL15.GL_STREAM_READ);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GlStateManager._pixelStore(GL11.GL_PACK_SWAP_BYTES, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? 1 : 0);
            GlStateManager._bindTexture(tile.lowColor());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, 0L);
            GlStateManager._bindTexture(tile.highColor());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, (long) COLOR_BYTES);
            GlStateManager._bindTexture(tile.depth());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 2L * COLOR_BYTES);
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence == 0) throw new IllegalStateException("Cannot fence projected tile readback");
            var result = new CompletableFuture<ProjectedTileStore.Image>();
            pending.addLast(new Transfer(buffer, fence, image, result));
            submitted = true;
            return Optional.of(result);
        } finally {
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, row);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, align);
            GlStateManager._pixelStore(GL11.GL_PACK_SWAP_BYTES, swap);
            GlStateManager._bindTexture(texture);
            GlStateManager._activeTexture(active);
            if (!submitted) GL15.glDeleteBuffers(buffer);
        }
    }

    /** Harvests at most one completed image per frame. */
    public void poll() {
        requireClientThread();
        if (pending.isEmpty()) return;
        Transfer transfer = pending.getFirst();
        int status = GL32.glClientWaitSync(transfer.fence(), GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0);
        if (status == GL32.GL_TIMEOUT_EXPIRED) return;
        pending.removeFirst();
        GL32.glDeleteSync(transfer.fence());
        if (status == GL32.GL_WAIT_FAILED) {
            GL15.glDeleteBuffers(transfer.buffer());
            transfer.image().close();
            transfer.result().completeExceptionally(new IllegalStateException("Projected tile GPU fence failed"));
            return;
        }
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, transfer.buffer());
            ByteBuffer data = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, BYTES, GL30.GL_MAP_READ_BIT);
            if (data == null) throw new IllegalStateException("Cannot map projected tile pixel buffer");
            boolean valid;
            try {
                transfer.image().mutableLow().put(data.slice(0, COLOR_BYTES));
                transfer.image().mutableHigh().put(data.slice(COLOR_BYTES, COLOR_BYTES));
                transfer.image().mutableDepth().put(data.slice(2 * COLOR_BYTES, DEPTH_BYTES));
            } finally { valid = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER); }
            if (!valid) throw new IllegalStateException("Projected tile pixel buffer became invalid");
            if (!transfer.result().complete(transfer.image())) transfer.image().close();
        } catch (RuntimeException failure) {
            transfer.image().close();
            transfer.result().completeExceptionally(failure);
            LogUtils.getLogger().warn("Projected tile GPU readback failed", failure);
        } catch (Error failure) {
            transfer.image().close();
            transfer.result().completeExceptionally(failure);
            throw failure;
        } finally {
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
            available.addLast(transfer.buffer());
        }
    }

    @Override public void close() {
        requireClientThread();
        if (closed) return;
        closed = true;
        for (Transfer transfer : pending) {
            GL32.glDeleteSync(transfer.fence()); GL15.glDeleteBuffers(transfer.buffer());
            transfer.image().close();
            transfer.result().completeExceptionally(new CancellationException("Projected tile readback closed"));
        }
        pending.clear();
        for (int buffer : available) GL15.glDeleteBuffers(buffer);
        available.clear();
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Projected tile readback must use the client thread");
    }
}
