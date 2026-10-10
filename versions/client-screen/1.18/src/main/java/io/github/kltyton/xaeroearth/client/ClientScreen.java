package io.github.kltyton.xaeroearth.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

final class ClientScreen {
    static Screen screen(Minecraft minecraft) { return minecraft.screen; }
    static boolean isViewSelector(Button button) {
        return button.getMessage() instanceof TranslatableComponent translated
                && translated.getKey().startsWith("xaeroearth.view.");
    }
    private ClientScreen() { }
}
