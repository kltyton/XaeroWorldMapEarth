package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;

/** Observes the exact row geometry that Xaero sends to its dropdown renderer. */
@Mixin(value = DropDownWidget.class, remap = false)
abstract class DropdownSkinMixin {
    @Shadow private int scroll;
    @Shadow private int yOffset;
    @Shadow private int selected;
    @Shadow @Final private boolean hasEmptyOption;
    @Shadow protected boolean openingUp;

    @Inject(method = "drawSlot", at = @At("HEAD"), cancellable = true, remap = false)
    private void xaeroearth$recordMenuSlot(
            GuiGraphics graphics,
            Component component,
            int slotIndex,
            int rowIndex,
            int mouseX,
            int mouseY,
            boolean scrolling,
            int visibleRows,
            int x,
            int y,
            CallbackInfo callbackInfo
    ) {
        int visualRow = openingUp ? -rowIndex - 1 : rowIndex;
        int rowY = y + visualRow * DropDownWidget.LINE_HEIGHT;
        int width = ((DropDownWidget) (Object) this).getWidth();
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= rowY && mouseY < rowY + DropDownWidget.LINE_HEIGHT;
        int selectionIndex = slotIndex - (hasEmptyOption ? 1 : 0);
        boolean themed = NativeMapSkin.recordSlot((DropDownWidget) (Object) this, component, slotIndex,
                x, rowY, width, ((DropDownWidget) (Object) this).active,
                ((DropDownWidget) (Object) this).visible,
                slotIndex >= 0 && selected == selectionIndex,
                hovered,
                ((DropDownWidget) (Object) this).isFocused(),
                openingUp, scroll, yOffset);
        if (themed) callbackInfo.cancel();
    }
}
