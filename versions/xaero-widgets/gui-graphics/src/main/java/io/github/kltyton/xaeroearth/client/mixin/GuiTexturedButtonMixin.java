package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import xaero.map.gui.GuiTexturedButton;

@Mixin(value = GuiTexturedButton.class)
abstract class GuiTexturedButtonMixin {
    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE",
            remap = false, target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V"))
    private void earth$foreground(float red, float green, float blue, float alpha, Operation<Void> original) {
        Integer color = NativeMapSkin.currentIconTint();
        if (color != null && red != 1F) {
            red = ((color >> 16) & 255) / 255F;
            green = ((color >> 8) & 255) / 255F;
            blue = (color & 255) / 255F;
            alpha = ((color >>> 24) & 255) / 255F;
        }
        original.call(red, green, blue, alpha);
    }

    @ModifyArg(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"), index = 2)
    private int earth$verticalOffset(int y) {
        if (NativeMapSkin.currentIconTint() != null) RenderSystem.disableDepthTest();
        return NativeMapSkin.iconY(y);
    }
}
