package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapEntityIndicators;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

@Pseudo
@Mixin(targets = "xaero.map.mods.minimap.element.MinimapElementRendererWrapper", remap = false)
abstract class MinimapRadarEntityMixin {
    @Inject(method = "renderElement(Ljava/lang/Object;ZDFDDLxaero/map/element/render/ElementRenderInfo;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;)Z",
            at = @At("RETURN"))
    private void earth$acceptedRadarModel(Object element, boolean hovered, double scale, float brightness,
                                          double offsetX, double offsetZ, ElementRenderInfo info,
                                          PoseStack graphics, MultiBufferSource.BufferSource buffers,
                                          MultiTextureRenderTypeRendererProvider textures,
                                          CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ() && element instanceof Entity entity) {
            NativeMapEntityIndicators.captureRadar(entity, info);
        }
    }
}
