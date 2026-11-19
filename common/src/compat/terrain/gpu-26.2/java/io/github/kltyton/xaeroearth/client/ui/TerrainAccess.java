package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import io.github.kltyton.xaeroearth.client.chunkmap.picking.MapDepthPicker;
import com.mojang.blaze3d.opengl.GlTexture;
import net.minecraft.client.Minecraft;

abstract class TerrainAccess {
    protected static NativeTerrainScene renderer;
    private static TextureTarget background;

    protected static void saveWorldBackground(RenderTarget scene) {
        if (background == null || background.width != scene.width || background.height != scene.height
                || background.getColorTexture().getFormat() != scene.getColorTexture().getFormat()) {
            releaseWorldBackground();
            background = new TextureTarget("Earth transparent map background", scene.width, scene.height, false, scene.getColorTexture().getFormat());
        }
        copy(scene, background);
    }

    protected static boolean restoreWorldBackground(RenderTarget scene) {
        if (background == null || background.width != scene.width || background.height != scene.height) return false;
        copy(background, scene);
        return true;
    }

    private static void copy(RenderTarget src, RenderTarget dst) {
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                src.getColorTexture(), dst.getColorTexture(), 0, 0, 0, 0, 0, src.width, src.height);
    }

    protected static void releaseWorldBackground() {
        if (background != null) { background.destroyBuffers(); background = null; }
    }
    protected static RenderTarget mainTarget(Minecraft minecraft) {
        return minecraft.gameRenderer.mainRenderTarget();
    }

    protected static void captureMapDepth(RenderTarget target, MapDepthPicker picker, MapDepthPicker.View view,
                                          double x, double y) {
        if (target.getDepthTexture() instanceof GlTexture depth)
            picker.captureTexture(depth.glId(), view, target.width, target.height, true, x, y);
    }
}
