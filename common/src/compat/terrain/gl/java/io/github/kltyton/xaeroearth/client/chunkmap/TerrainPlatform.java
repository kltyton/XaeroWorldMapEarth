package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
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
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.IntSupplier;

abstract class TerrainPlatform {
    protected final KuiNativeTerrainRenderer delegate = new KuiNativeTerrainRenderer();
    protected final SurfaceRenderer surfaceRenderer = new SurfaceRenderer();
    protected DynamicTexture coverage;

    public KuiMapDepthTarget depthTarget() { return delegate.depthTarget(); }

    protected boolean drawSurface(MapCamera camera, NativeTerrainScene.Surface surface, RenderTarget target,
                                  float brightness, boolean underground, Runnable updateCoverage,
                                  IntSupplier coverageX, IntSupplier coverageZ) {
        try (var state = new KuiMapRenderState(target, delegate.depthTarget())) {
            updateCoverage.run();
            surfaceRenderer.update(surface);
            GlStateManager._clearColor(underground ? 0 : 0.46F, underground ? 0 : 0.67F, underground ? 0 : 0.82F, 1);
            GlStateManager._clearDepth(1);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            return surface != null && surfaceRenderer.draw(camera, coverage.getId(),
                    coverageX.getAsInt(), coverageZ.getAsInt(), brightness);
        }
    }

    protected static Matrix4f projection(MapCamera camera) {
        return camera.projectionMatrix();
    }

    protected static DynamicTexture createCoverage(int size) {
        return new DynamicTexture(size, size, true);
    }

    protected static void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixelRGBA(x, y, color);
    }

}
