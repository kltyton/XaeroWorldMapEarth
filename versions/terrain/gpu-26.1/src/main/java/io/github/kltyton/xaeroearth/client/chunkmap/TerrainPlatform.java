package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeMesh;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.IntSupplier;

abstract class TerrainPlatform {
    protected final KuiNativeTerrainRenderer delegate = new KuiNativeTerrainRenderer();
    protected final SurfaceRenderer surfaceRenderer = new SurfaceRenderer();
    protected DynamicTexture coverage;

    protected boolean drawSurface(MapCamera camera, NativeTerrainScene.Surface surface, RenderTarget target,
                                  float brightness, boolean underground, Runnable updateCoverage,
                                  IntSupplier coverageX, IntSupplier coverageZ) {
        updateCoverage.run();
        surfaceRenderer.update(surface);
        boolean drawn = surface != null && surfaceRenderer.draw(camera, target, coverage.getTextureView(),
                coverageX.getAsInt(), coverageZ.getAsInt(), brightness, true, underground);
        if (!drawn) clear(target, underground);
        return drawn;
    }

    protected static Matrix4f projection(MapCamera camera) {
        return camera.nativeProjectionMatrix(RenderSystem.getDevice().isZZeroToOne());
    }

    protected static DynamicTexture createCoverage(int size) {
        return new DynamicTexture(() -> "KUI map coverage", size, size, true);
    }

    protected static void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixel(x, y, color);
    }

    private static void clear(RenderTarget target, boolean underground) {
        int clearColor = (255 << 24) | ((int) ((underground ? 0 : 0.46F) * 255) << 16)
                | ((int) ((underground ? 0 : 0.67F) * 255) << 8) | (int) ((underground ? 0 : 0.82F) * 255);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Earth native terrain clear", target.getColorTextureView(), OptionalInt.of(clearColor),
                target.getDepthTextureView(), OptionalDouble.of(1))) { }
    }

}
