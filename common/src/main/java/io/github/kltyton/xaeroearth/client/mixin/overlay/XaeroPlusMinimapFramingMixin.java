package io.github.kltyton.xaeroearth.client.mixin.overlay;

import io.github.kltyton.xaeroearth.client.ui.minimap.MinimapFraming;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces XaeroPlus's minimap scale control with Earth's independent camera zoom. */
@Pseudo
@Mixin(targets = "xaeroplus.settings.DoubleSetting", remap = false)
abstract class XaeroPlusMinimapFramingMixin {
    @Unique private static final String earth$entryConstructor =
            "Lxaeroplus/feature/extensions/XaeroPlusCustomSettingEntry;<init>(Lxaeroplus/settings/XaeroPlusSetting;"
            + "Lnet/minecraft/network/chat/Component;Lxaero/lib/common/gui/widget/TooltipInfo;ZLjava/util/function/Supplier;"
            + "IILjava/util/function/IntFunction;Ljava/util/function/Function;Ljava/util/function/BiConsumer;"
            + "Ljava/util/function/BooleanSupplier;)V";
    @Unique private boolean earth$minimapScale;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void earth$identifyScale(String name, String translationKey, String tooltip, KeyMapping key,
            double minimum, double maximum, double step, double initial, DoubleConsumer change,
            BooleanSupplier visibility, CallbackInfo callback) {
        earth$minimapScale = "xaeroplus.setting.minimap_scaling".equals(translationKey) && minimum == 1.0 && step == 1.0;
    }

    @Unique private boolean earth$usesEarthZoom() {
        return earth$minimapScale && MinimapFraming.enabled();
    }

    @ModifyArg(method = "toXaeroSettingEntry", index = 4, require = 0,
            at = @At(value = "INVOKE", target = earth$entryConstructor))
    private Supplier<Double> earth$zoomValue(Supplier<Double> original) {
        return earth$usesEarthZoom() ? MinimapFraming::sliderCurrent : original;
    }

    @ModifyArg(method = "toXaeroSettingEntry", index = 6, require = 0,
            at = @At(value = "INVOKE", target = earth$entryConstructor))
    private int earth$addCloseupSteps(int last) {
        return earth$usesEarthZoom() ? MinimapFraming.SLIDER_STEPS : last;
    }

    @ModifyArg(method = "toXaeroSettingEntry", index = 7, require = 0,
            at = @At(value = "INVOKE", target = earth$entryConstructor))
    private IntFunction<Double> earth$mapCloseupStep(IntFunction<Double> original) {
        if (!earth$usesEarthZoom()) return original;
        return MinimapFraming::sliderValue;
    }

    @ModifyArg(method = "toXaeroSettingEntry", index = 8, require = 0,
            at = @At(value = "INVOKE", target = earth$entryConstructor))
    private Function<Double, Component> earth$formatZoom(Function<Double, Component> original) {
        return earth$usesEarthZoom() ? MinimapFraming::label : original;
    }

    @ModifyArg(method = "toXaeroSettingEntry", index = 9, require = 0,
            at = @At(value = "INVOKE", target = earth$entryConstructor))
    private BiConsumer<Double, Double> earth$setZoom(BiConsumer<Double, Double> original) {
        return earth$usesEarthZoom() ? (previous, value) -> MinimapFraming.set(value) : original;
    }

    @ModifyArg(method = "toXaeroSettingEntry", require = 0, at = @At(value = "INVOKE",
            target = "Lxaero/lib/common/gui/widget/TooltipInfo;<init>(Ljava/lang/String;)V"))
    private String earth$rangeTooltip(String original) {
        return earth$usesEarthZoom() ? MinimapFraming.TOOLTIP : original;
    }

}
