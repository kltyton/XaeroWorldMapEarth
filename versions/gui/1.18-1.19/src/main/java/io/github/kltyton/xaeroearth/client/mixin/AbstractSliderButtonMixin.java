package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSliderButton.class)
abstract class AbstractSliderButtonMixin {
    @Inject(method = "setValueFromMouse", at = @At("HEAD"), cancellable = true)
    private void earth$mouseValue(double mouseX, CallbackInfo callback) {
        Double value = NativeMapSkin.sliderValueFromMouse((AbstractSliderButton) (Object) this, mouseX);
        if (value != null) {
            ((AbstractSliderButtonAccess) this).earth$setValue(value);
            callback.cancel();
        }
    }

}
