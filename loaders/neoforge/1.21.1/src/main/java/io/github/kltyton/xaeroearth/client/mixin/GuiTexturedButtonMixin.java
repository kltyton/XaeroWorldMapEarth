package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import xaero.map.gui.GuiTexturedButton;

@Mixin(value = GuiTexturedButton.class, remap = false)
abstract class GuiTexturedButtonMixin {
    @ModifyArgs(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V"))
    private void earth$foreground(Args args) {
        Integer color = NativeMapSkin.currentIconTint();
        if (color == null || (float) args.get(0) == 1F) return;
        args.set(0, ((color >> 16) & 255) / 255F);
        args.set(1, ((color >> 8) & 255) / 255F);
        args.set(2, (color & 255) / 255F);
        args.set(3, ((color >>> 24) & 255) / 255F);
    }

    @ModifyArg(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"), index = 2)
    private int earth$verticalOffset(int y) {
        if (NativeMapSkin.currentIconTint() != null) RenderSystem.disableDepthTest();
        return NativeMapSkin.iconY(y);
    }
}
