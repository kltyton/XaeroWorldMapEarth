package io.github.kltyton.xaeroearth.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractWidget.class)
abstract class AbstractWidgetMixin {
    @Inject(method = "renderButton", at = @At("HEAD"), cancellable = true)
    private void earth$skinSlider(PoseStack pose, int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        if ((Object) this instanceof AbstractSliderButton slider && NativeMapSkin.skinsSlider(slider)) {
            callback.cancel();
        }
    }
}
