package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import xaero.map.gui.GuiTexturedButton;

@Mixin(value = GuiTexturedButton.class, remap = false)
abstract class GuiTexturedButtonMixin {
    @ModifyArg(method = "extractContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"), index = 10)
    private int earth$foreground(int color) { return NativeMapSkin.iconTint(color); }

    @ModifyArg(method = "extractContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"), index = 3)
    private int earth$verticalOffset(int y) { return NativeMapSkin.iconY(y); }
}
