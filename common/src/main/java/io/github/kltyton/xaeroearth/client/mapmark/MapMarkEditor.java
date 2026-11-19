package io.github.kltyton.xaeroearth.client.mapmark;

import io.github.kltyton.xaeroearth.client.ClientScreen;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A native text field lets map annotations use Minecraft's keyboard and IME handling. */
final class MapMarkEditor extends Screen {
    private final Screen parent;
    private final Consumer<String> save;
    private EditBox input;
    private String value;

    MapMarkEditor(Screen parent, Component title, String value, Consumer<String> save) {
        super(title);
        this.parent = parent;
        this.value = value;
        this.save = save;
    }

    @Override protected void init() {
        int panel = Math.min(280, Math.max(90, width - 30));
        int x = (width - panel) / 2;
        int y = height / 2 - 19;
        input = new EditBox(font, x, y, panel, 20, getTitle());
        input.setMaxLength(MapMark.TEXT_LIMIT);
        input.setValue(value);
        input.setResponder(text -> value = text);
        addRenderableWidget(input);
        int buttonWidth = (panel - 6) / 2;
        var accept = MapMarkWidgets.button(ClientScreen.viewLabel("gui.done"), x, y + 26, buttonWidth, this::finish);
        addRenderableWidget(accept);
        addRenderableWidget(MapMarkWidgets.button(ClientScreen.viewLabel("gui.cancel"),
                x + buttonWidth + 6, y + 26, buttonWidth, this::onClose));
    }

    private void finish() {
        String label = value.strip();
        if (label.isEmpty()) return;
        save.accept(label);
        onClose();
    }

    @Override public void onClose() { ClientScreen.open(Minecraft.getInstance(), parent); }
}
