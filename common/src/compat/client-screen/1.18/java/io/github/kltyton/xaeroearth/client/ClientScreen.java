package io.github.kltyton.xaeroearth.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

public final class ClientScreen {
    public static Screen screen(Minecraft minecraft) { return minecraft.screen; }
    static boolean isViewSelector(Button button) {
        return button.getMessage() instanceof TranslatableComponent translated
                && translated.getKey().startsWith("xaeroearth.view.");
    }
    public static void open(Minecraft minecraft, Screen screen) { minecraft.setScreen(screen); }
    public static net.minecraft.network.chat.Component viewLabel(String key) {
        return new TranslatableComponent(key);
    }
    public static net.minecraft.network.chat.Component literal(String text) {
        return new net.minecraft.network.chat.TextComponent(text);
    }
    private ClientScreen() { }
}
