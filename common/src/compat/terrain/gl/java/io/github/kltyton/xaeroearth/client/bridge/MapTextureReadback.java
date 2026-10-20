package io.github.kltyton.xaeroearth.client.bridge;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import xaero.map.region.texture.RegionTexture;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

final class MapTextureReadback implements AutoCloseable {
    static final int SURFACE_TEXELS = 1024 * 1024;
    private final ByteBuffer readback = MemoryUtil.memAlloc(16384);

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
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int row = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH), rows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int pixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS), alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GlStateManager._bindTexture(texture.getGlColorTexture());
            readback.clear();
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback);
            byte[] rgba = new byte[16384];
            readback.get(rgba);
            complete.accept(rgba);
        } finally {
            done.run();
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, row);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, rows);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, pixels);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, alignment);
            GlStateManager._bindTexture(bound);
            GlStateManager._activeTexture(active);
        }
    }

    @Override public void close() { MemoryUtil.memFree(readback);  }
}
