package io.github.kltyton.xaeroearth.client.mixin;

import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractSliderButton.class)
public interface AbstractSliderButtonAccess {
    @Accessor("value")
    double earth$getValue();

    @Accessor("dragging")
    boolean earth$isDragging();
}
