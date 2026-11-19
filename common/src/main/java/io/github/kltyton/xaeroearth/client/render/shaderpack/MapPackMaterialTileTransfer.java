package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.logging.LogUtils;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedGrid;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileStore;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileStore.Image;
import io.github.kltyton.xaeroearth.client.render.gl.GlPixelTransferLayout;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** Transfers material samples after a GPU fence. Compression and file I/O use the projected tile store. */
final class MapPackMaterialTileTransfer implements AutoCloseable {
    private static final int SIZE = ProjectedGrid.IMAGE_PIXELS;
    private static final int COLOR_BYTES = SIZE * SIZE * MapPackMaterialTileTextures.PLANES * 8;
    private static final long BYTES = MapPackMaterialTileTextures.TILE_BYTES;
    private static final int LIMIT = 3;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ArrayDeque<Integer> available = new ArrayDeque<>();
    private final AtomicInteger writing = new AtomicInteger();

    private record Pending(int buffer, long fence, ProjectedTileStore store, ProjectedTileStore.WriteTicket ticket,
                           String fingerprint, double x, double y, double z, boolean reversed) { }

    boolean submit(MapPackMaterialTileTextures.Tile tile, boolean reversed, ProjectedTileStore store,
                   String slot, String fingerprint) {
        if (pending.size() + writing.get() >= LIMIT) return false;
        boolean create = available.isEmpty();
        int buffer = create ? GL15.glGenBuffers() : available.removeFirst();
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPlanes = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int previousDepth = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        var ticket = store.reserve(slot);
        boolean queued = false;
        try (var pixels = GlPixelTransferLayout.pack(ByteOrder.LITTLE_ENDIAN)) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            if (create) GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, BYTES, GL15.GL_STREAM_READ);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, tile.planes());
            GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, 0L);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tile.depth());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (long) COLOR_BYTES);
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence == 0) throw new IllegalStateException("Cannot fence shader-pack material readback");
            pending.addLast(new Pending(buffer, fence, store, ticket, fingerprint, tile.x(), tile.y(), tile.z(), reversed));
            queued = true;
            return true;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousDepth);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousPlanes);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
            if (!queued) {
                ticket.close();
                GL15.glDeleteBuffers(buffer);
            }
        }
    }

    void poll() {
        if (pending.isEmpty()) return;
        var next = pending.getFirst();
        int ready = GL32.glClientWaitSync(next.fence(), GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0);
        if (ready == GL32.GL_TIMEOUT_EXPIRED) return;
        pending.removeFirst();
        GL32.glDeleteSync(next.fence());
        if (ready == GL32.GL_WAIT_FAILED) {
            next.ticket().close();
            GL15.glDeleteBuffers(next.buffer());
            LogUtils.getLogger().warn("Shader-pack material tile GPU fence failed");
            return;
        }
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        Image image = null;
        try {
            image = Image.allocate(next.x(), next.y(), next.z(), next.reversed(), Image.ColorFormat.MATERIAL_RGBA16F);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, next.buffer());
            var data = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, BYTES, GL30.GL_MAP_READ_BIT);
            if (data == null) throw new IllegalStateException("Cannot map shader-pack material pixel buffer");
            boolean valid;
            try {
                image.mutableLow().put(data.slice(0, COLOR_BYTES));
                image.mutableDepth().put(data.slice(COLOR_BYTES, (int) BYTES - COLOR_BYTES));
            } finally { valid = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER); }
            if (!valid) throw new IllegalStateException("Shader-pack material pixel buffer was invalidated");
            var saved = image;
            writing.incrementAndGet();
            try {
                next.store().write(next.ticket(), next.fingerprint(), saved).whenComplete((ignored, failure) -> {
                    saved.close();
                    writing.decrementAndGet();
                    if (failure != null) LogUtils.getLogger().debug("Cannot save shader-pack material tile", failure);
                });
            } catch (RuntimeException | Error failure) {
                writing.decrementAndGet();
                throw failure;
            }
            image = null;
        } catch (RuntimeException failure) {
            LogUtils.getLogger().warn("Cannot transfer shader-pack material tile", failure);
        } finally {
            if (image != null) image.close();
            next.ticket().close();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
            available.addLast(next.buffer());
        }
    }

    static void upload(MapPackMaterialTileTextures.Tile tile, Image image) {
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int previousPlanes = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int previousDepth = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (var pixels = GlPixelTransferLayout.unpack(ByteOrder.LITTLE_ENDIAN)) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, tile.planes());
            GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, SIZE, SIZE, MapPackMaterialTileTextures.PLANES,
                    GL11.GL_RGBA, GL30.GL_HALF_FLOAT, image.low());
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tile.depth());
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, image.depth());
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousDepth);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousPlanes);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previous);
        }
    }

    @Override public void close() {
        for (var next : pending) {
            next.ticket().close();
            GL32.glDeleteSync(next.fence());
            GL15.glDeleteBuffers(next.buffer());
        }
        pending.clear();
        for (int buffer : available) GL15.glDeleteBuffers(buffer);
        available.clear();
    }
}
