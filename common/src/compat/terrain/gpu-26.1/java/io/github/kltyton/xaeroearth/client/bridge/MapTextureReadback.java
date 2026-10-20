package io.github.kltyton.xaeroearth.client.bridge;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import xaero.map.region.texture.RegionTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

final class MapTextureReadback implements AutoCloseable {
    static final int SURFACE_TEXELS = 512 * 512;

    static boolean exists(RegionTexture<?> texture) {
        return texture.getGlColorTexture() != null && !texture.getGlColorTexture().texture.isClosed();
    }

    static boolean canRead(RegionTexture<?> texture) {
        return exists(texture) && texture.getGlColorTexture().texture.getFormat() == TextureFormat.RGBA8;
    }

    static byte[] colors(RegionTexture<?> texture) {
        ByteBuffer colors = texture.getDirectColorBuffer();
        if (colors == null || texture.getColorBufferFormat() != TextureFormat.RGBA8 || colors.capacity() < 16384) return null;
        byte[] rgba = new byte[16384];
        colors.duplicate().position(0).get(rgba);
        return rgba;
    }

    void read(RegionTexture<?> texture, Consumer<byte[]> complete, Runnable done) {
        var gpu = texture.getGlColorTexture();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Earth map readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16384);
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpu.texture, buffer, 0, () -> {
                try (buffer; var mapped = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false)) {
                    byte[] rgba = new byte[16384];
                    mapped.data().get(rgba);
                    complete.accept(rgba);
                } finally { done.run(); }
            }, 0, 0, 0, 64, 64);
        } catch (RuntimeException exception) {
            buffer.close(); done.run();
            throw exception;
        }
    }

    @Override public void close() {  }
}
