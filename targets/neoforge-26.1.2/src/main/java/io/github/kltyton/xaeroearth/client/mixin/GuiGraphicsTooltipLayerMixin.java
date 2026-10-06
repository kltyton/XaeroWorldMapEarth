package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsTooltipLayerMixin {
    @Inject(method = "extractDeferredElements", at = @At("RETURN"))
    private void earth$foreground(int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        NativeMapSkin.flushTooltips((GuiGraphicsExtractor) (Object) this);
    }
}
