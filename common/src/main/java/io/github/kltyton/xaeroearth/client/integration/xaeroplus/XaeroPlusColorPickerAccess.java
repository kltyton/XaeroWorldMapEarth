package io.github.kltyton.xaeroearth.client.integration.xaeroplus;

import net.minecraft.client.gui.components.EditBox;

/** Keeps the native RGBA input fields aligned when Earth positions the XaeroPlus palette. */
public interface XaeroPlusColorPickerAccess {
    EditBox[] earth$colorEntryBoxes();
}
