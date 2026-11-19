package io.github.kltyton.xaeroearth.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class ClientScreen {
    public static Screen screen(Minecraft minecraft) { return minecraft.gui.screen(); }
    static boolean isViewSelector(Button button) {
        return button.getMessage().getContents() instanceof TranslatableContents translated
                && translated.getKey().startsWith("xaeroearth.view.");
    }
    public static void open(Minecraft minecraft, Screen screen) { minecraft.gui.setScreen(screen); }
    public static net.minecraft.network.chat.Component viewLabel(String key) {
        return net.minecraft.network.chat.Component.translatable(key);
    }
    public static net.minecraft.network.chat.Component literal(String text) {
        return net.minecraft.network.chat.Component.literal(text);
    }
    private ClientScreen() { }
}
