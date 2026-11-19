package io.github.kltyton.xaeroearth.client.mixin.overlay;

import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;

/** Refreshes Xaero's native cursor coordinates before addon drawing and selection handlers. */
@Mixin(value = GuiMap.class, priority = 1100)
abstract class GuiMapPointerMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void earth$press(double x, double y, int button, CallbackInfoReturnable<Boolean> callback) {
        NativeMapTerrain.updatePointer((GuiMap) (Object) this, x, y);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void earth$release(double x, double y, int button, CallbackInfoReturnable<Boolean> callback) {
        NativeMapTerrain.updatePointer((GuiMap) (Object) this, x, y);
    }
}
