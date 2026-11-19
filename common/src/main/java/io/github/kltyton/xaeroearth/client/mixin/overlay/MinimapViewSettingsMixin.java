package io.github.kltyton.xaeroearth.client.mixin.overlay;

import io.github.kltyton.xaeroearth.client.ClientScreen;
import io.github.kltyton.xaeroearth.client.ui.minimap.MinimapFraming;
import java.util.Arrays;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.lib.client.gui.CustomSettingEntry;
import xaero.lib.client.gui.GuiSettings;
import xaero.lib.client.gui.config.EditConfigScreen;
import xaero.lib.common.gui.widget.TooltipInfo;

/** Appends the Earth multiplier to Xaero's local, searchable minimap display settings. */
@Pseudo
@Mixin(targets = "xaero.common.gui.GuiMinimapViewSettings", remap = false)
abstract class MinimapViewSettingsMixin extends GuiSettings {
    MinimapViewSettingsMixin(Component title, Screen parent, Screen exit) { super(title, parent, exit); }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void earth$zoomOption(CallbackInfo callback) {
        if (!((EditConfigScreen) (Object) this).getContext().isClientSide() || !MinimapFraming.enabled()) return;
        var combined = Arrays.copyOf(entries, entries.length + 1);
        combined[entries.length] = new CustomSettingEntry<Double>(() -> false,
                ClientScreen.viewLabel("xaeroearth.minimap.framing"), new TooltipInfo(MinimapFraming.TOOLTIP),
                true, MinimapFraming::sliderCurrent, 0, MinimapFraming.SLIDER_STEPS,
                MinimapFraming::sliderValue, MinimapFraming::label,
                (previous, value) -> MinimapFraming.set(value), MinimapFraming::enabled);
        entries = combined;
    }
}
