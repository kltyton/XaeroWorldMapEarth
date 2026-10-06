package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSliderButton.class)
abstract class AbstractSliderButtonMixin {
    @Shadow
    protected abstract void handleCursor(GuiGraphicsExtractor graphics);

    @Shadow
    protected abstract void setValue(double value);

    @Inject(method = "setValueFromMouse", at = @At("HEAD"), cancellable = true)
    private void earth$mouseValue(MouseButtonEvent event, CallbackInfo callback) {
        Double value = NativeMapSkin.sliderValueFromMouse((AbstractSliderButton) (Object) this, event.x());
        if (value != null) {
            setValue(value);
            callback.cancel();
        }
    }

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
    private void earth$skin(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        if (NativeMapSkin.skinsSlider((AbstractSliderButton) (Object) this)) {
            handleCursor(graphics);
            callback.cancel();
        }
    }
}
