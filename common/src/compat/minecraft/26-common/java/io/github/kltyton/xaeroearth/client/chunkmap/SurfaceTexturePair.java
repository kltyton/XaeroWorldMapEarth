package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.function.Supplier;

/** Couples a resident height and color image with their device views. */
record SurfaceTexturePair(GpuTexture color, GpuTexture height, GpuTextureView colorView, GpuTextureView heightView)
        implements AutoCloseable {
    static SurfaceTexturePair allocate(Supplier<GpuTexture> colorTexture, Supplier<GpuTexture> heightTexture) {
        var device = RenderSystem.getDevice();
        var color = colorTexture.get();
        GpuTexture height = null;
        GpuTextureView colorView = null;
        try {
            height = heightTexture.get();
            colorView = device.createTextureView(color);
            return new SurfaceTexturePair(color, height, colorView, device.createTextureView(height));
        } catch (RuntimeException | Error failure) {
            if (colorView != null) colorView.close();
            if (height != null) height.close();
            color.close();
            throw failure;
        }
    }

    boolean matches(NativeTerrainScene.Surface data) {
        return color.getWidth(0) == data.width() && color.getHeight(0) == data.depth()
                && height.getHeight(0) == data.heightTextureRows();
    }

    @Override public void close() { colorView.close(); heightView.close(); color.close(); height.close(); }
}
