package io.github.kltyton.xaeroearth.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.gui.GuiTexturedButton;

/** Distinguishes visible native sprites from add-on click regions that have no sprite. */
@Mixin(value = GuiTexturedButton.class, remap = false)
public interface GuiTexturedButtonAccess {
    @Accessor("textureW") int earth$textureWidth();
    @Accessor("textureH") int earth$textureHeight();
}
