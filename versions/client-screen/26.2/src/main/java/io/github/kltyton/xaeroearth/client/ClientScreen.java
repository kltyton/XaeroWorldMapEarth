package io.github.kltyton.xaeroearth.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

final class ClientScreen {
    static Screen screen(Minecraft minecraft) { return minecraft.gui.screen(); }
    static boolean isViewSelector(Button button) {
        return button.getMessage().getContents() instanceof TranslatableContents translated
                && translated.getKey().startsWith("xaeroearth.view.");
    }
    private ClientScreen() { }
}
