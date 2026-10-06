package io.github.kltyton.xaeroearth.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.kltyton.xaeroearth.client.ui.NativeMapEntityIndicators;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.gui.GuiGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.radar.tracker.PlayerTrackerMapElement;
import xaero.map.radar.tracker.PlayerTrackerMapElementRenderer;

@Mixin(value = PlayerTrackerMapElementRenderer.class, remap = false)
abstract class TrackedPlayerEntityIndicatorMixin {
    @org.spongepowered.asm.mixin.injection.Desc(id = "trackedRender", owner = xaero.map.radar.tracker.PlayerTrackerMapElementRenderer.class, value = "renderElement", args = {xaero.map.radar.tracker.PlayerTrackerMapElement.class, boolean.class, double.class, float.class, double.class, double.class, xaero.map.element.render.ElementRenderInfo.class, net.minecraft.client.gui.GuiGraphics.class, net.minecraft.client.renderer.MultiBufferSource.BufferSource.class, xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider.class}, ret = boolean.class)
    @WrapOperation(method = "@Desc(trackedRender)",
            at = @At(value = "INVOKE", desc = @org.spongepowered.asm.mixin.injection.Desc(owner = xaero.map.graphics.MapRenderHelper.class, value = "blitIntoMultiTextureRenderer", args = {org.joml.Matrix4f.class, xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer.class, float.class, float.class, int.class, int.class, int.class, int.class, int.class, int.class, float.class, float.class, float.class, float.class, int.class, int.class, int.class}, ret = void.class)))
    private void earth$trackedModel(Matrix4f pose, MultiTextureRenderTypeRenderer renderer, float x, float y,
                                    int u, int v, int width, int height, int uvWidth, int uvHeight,
                                    float red, float green, float blue, float alpha,
                                    int textureWidth, int textureHeight, int texture, Operation<Void> original,
                                    PlayerTrackerMapElement<?> marker, boolean hovered, double scale, float brightness,
                                    double offsetX, double offsetZ, ElementRenderInfo info, GuiGraphics graphics,
                                    MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textures) {
        if (!NativeMapEntityIndicators.replaceTracked(marker, info)) {
            original.call(pose, renderer, x, y, u, v, width, height, uvWidth, uvHeight,
                    red, green, blue, alpha, textureWidth, textureHeight, texture);
        }
    }
}
