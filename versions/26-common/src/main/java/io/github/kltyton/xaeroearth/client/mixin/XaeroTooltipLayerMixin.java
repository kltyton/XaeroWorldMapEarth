package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.lib.client.gui.widget.Tooltip;

@Mixin(value = Tooltip.class, remap = false)
abstract class XaeroTooltipLayerMixin {
    @Inject(method = "drawBox", at = @At("HEAD"), cancellable = true)
    private void earth$foreground(GuiGraphicsExtractor graphics, int x, int y, int width, int height, CallbackInfo callback) {
        if (NativeMapSkin.deferTooltip((Tooltip) (Object) this, graphics, x, y, width, height)) callback.cancel();
    }
}
