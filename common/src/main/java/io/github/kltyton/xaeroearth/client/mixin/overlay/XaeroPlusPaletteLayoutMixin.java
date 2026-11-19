package io.github.kltyton.xaeroearth.client.mixin.overlay;

import io.github.kltyton.xaeroearth.client.ClientScreen;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.integration.xaeroplus.XaeroPlusColorPickerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import xaero.map.gui.GuiMap;

/** Sizes the native palette before XaeroPlus derives its inner controls and hit targets. */
@Pseudo
@Mixin(targets = "xaeroplus.feature.drawing.ColorPickerWidget", remap = false)
abstract class XaeroPlusPaletteLayoutMixin implements XaeroPlusColorPickerAccess {
    @Shadow @Final private EditBox[] colorEntryBoxes;

    @Override public EditBox[] earth$colorEntryBoxes() { return colorEntryBoxes; }

    @ModifyVariable(method = "<init>(IIILxaeroplus/util/Color;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private static int earth$fitPalette(int width) {
        if (!EarthPreferences.ENABLED.get()) return width;
        var client = Minecraft.getInstance();
        if (!(ClientScreen.screen(client) instanceof GuiMap map)) return width;
        int minimum = 4 * (client.font.width("255") + 4) + 4;
        // The picker derives the gradient from width and appends fixed preset and RGBA rows.
        int fixedRows = 54;
        int usableHeight = Math.max(0, map.height - 12 - fixedRows);
        int usableWidth = Math.max(0, map.width - 52);
        int available = Math.min(usableWidth, (int) (usableHeight * 9L / 8));
        return Math.min(width, Math.max(minimum, available));
    }
}
