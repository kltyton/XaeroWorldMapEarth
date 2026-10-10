package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapEntityIndicators;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.gui.GuiGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

@Pseudo
@Mixin(targets = "xaero.map.mods.minimap.element.MinimapElementRendererWrapper", remap = false)
abstract class MinimapRadarEntityMixin {
    @Inject(target = @org.spongepowered.asm.mixin.injection.Desc(owner = xaero.map.mods.minimap.element.MinimapElementRendererWrapper.class, value = "renderElement", args = {java.lang.Object.class, boolean.class, double.class, float.class, double.class, double.class, xaero.map.element.render.ElementRenderInfo.class, net.minecraft.client.gui.GuiGraphics.class, net.minecraft.client.renderer.MultiBufferSource.BufferSource.class, xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider.class}, ret = boolean.class),
            at = @At("RETURN"))
    private void earth$acceptedRadarModel(Object element, boolean hovered, double scale, float brightness,
                                          double offsetX, double offsetZ, ElementRenderInfo info,
                                          GuiGraphics graphics, MultiBufferSource.BufferSource buffers,
                                          MultiTextureRenderTypeRendererProvider textures,
                                          CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ() && element instanceof Entity entity) {
            NativeMapEntityIndicators.captureRadar(entity, info);
        }
    }
}
