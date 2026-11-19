package io.github.kltyton.xaeroearth.client.mapmark;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class MapMarkWidgets {
    static Button button(Component text, int x, int y, int width, Runnable action) {
        return new Button(x, y, width, 20, text, button -> action.run());
    }
    private MapMarkWidgets() { }
}
