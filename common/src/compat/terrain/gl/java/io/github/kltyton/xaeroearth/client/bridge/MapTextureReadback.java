package io.github.kltyton.xaeroearth.client.bridge;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import xaero.map.region.texture.RegionTexture;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

final class MapTextureReadback implements AutoCloseable {
    static final int SURFACE_TEXELS = 1024 * 1024;
    private static final int BYTES = 64 * 64 * 4;
    private record Transfer(int buffer, long fence, Consumer<byte[]> complete, Runnable done) { }
    private final ArrayDeque<Transfer> pending = new ArrayDeque<>();
    private final ArrayDeque<Integer> available = new ArrayDeque<>();
    private ByteBuffer staging;

    static boolean exists(RegionTexture<?> texture) {
        return texture.getGlColorTexture() > 0;
    }

    static boolean canRead(RegionTexture<?> texture) {
        return exists(texture);
    }

    static byte[] colors(RegionTexture<?> texture) {
        ByteBuffer colors = texture.getDirectColorBuffer();
        if (colors == null || texture.getColorBufferFormat() != GL11.GL_RGBA || colors.capacity() < 16384) return null;
        byte[] rgba = new byte[16384];
        colors.duplicate().clear().get(rgba);
        return rgba;
    }

    void read(RegionTexture<?> texture, Consumer<byte[]> complete, Runnable done) {
        if (staging == null) staging = MemoryUtil.memAlloc(BYTES);
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int row = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH), rows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int pixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS), alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int buffer = available.isEmpty() ? GL15.glGenBuffers() : available.removeFirst();
        boolean submitted = false;
        try {
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            // Orphan the backing store before submitting a new asynchronous transfer.
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, BYTES, GL15.GL_STREAM_READ);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GlStateManager._bindTexture(texture.getGlColorTexture());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence == 0) throw new IllegalStateException("Cannot fence Xaero map texture transfer");
            pending.addLast(new Transfer(buffer, fence, complete, done));
            submitted = true;
        } finally {
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, row);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, rows);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, pixels);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, alignment);
            GlStateManager._bindTexture(bound);
            GlStateManager._activeTexture(active);
            if (!submitted) {
                GL15.glDeleteBuffers(buffer);
                done.run();
            }
        }
    }

    void poll() {
        if (pending.isEmpty()) return;
        while (!pending.isEmpty()) {
            Transfer transfer = pending.getFirst();
            int status = GL32.glClientWaitSync(transfer.fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0);
            if (status == GL32.GL_TIMEOUT_EXPIRED) break;
            pending.removeFirst();
            GL32.glDeleteSync(transfer.fence);
            try {
                if (status == GL32.GL_WAIT_FAILED) {
                    LogUtils.getLogger().warn("Cannot read the completed Xaero map texture transfer");
                    GL15.glDeleteBuffers(transfer.buffer);
                    continue;
                }
                int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
                byte[] rgba = new byte[BYTES];
                try {
                    GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, transfer.buffer);
                    staging.clear();
                    GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0, staging);
                    staging.get(rgba);
                } finally {
                    GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
                    available.addLast(transfer.buffer);
                }
                transfer.complete.accept(rgba);
            } finally { transfer.done.run(); }
        }
    }

    @Override public void close() {
        for (Transfer transfer : pending) {
            GL32.glDeleteSync(transfer.fence);
            GL15.glDeleteBuffers(transfer.buffer);
            transfer.done.run();
        }
        pending.clear();
        for (int buffer : available) GL15.glDeleteBuffers(buffer);
        available.clear();
        if (staging != null) { MemoryUtil.memFree(staging); staging = null; }
    }
}
