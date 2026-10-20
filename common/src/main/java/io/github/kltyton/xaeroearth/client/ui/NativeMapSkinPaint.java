package io.github.kltyton.xaeroearth.client.ui;

import io.github.kltyton.kltytonui.parser.Color;
import io.github.kltyton.kltytonui.style.Background;
import io.github.kltyton.kltytonui.style.Text;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import java.util.List;
import xaero.map.gui.GuiTexturedButton;

/** Shares icon clipping and tooltip ownership across native GUI drawing APIs. */
final class NativeMapSkinPaint extends NativeMapSkinState {
    interface IconRenderer<G> {
        void draw(G graphics, GuiTexturedButton button, int mouseX, int mouseY);
        void drawRegion(G graphics, GuiTexturedButton button, int mouseX, int mouseY,
                        int left, int top, int right, int bottom);
    }

    private NativeMapSkinPaint() { }

    static <G> void icons(G graphics, int mouseX, int mouseY, IconRenderer<G> renderer) {
        WidgetBounds menu = openMenuBounds();
        for (WidgetView view : widgets) {
            var widget = view.widget();
            if (!widget.visible || !(widget instanceof GuiTexturedButton textured)) continue;
            var button = document.querySelector("[data-native-key=\"widget-" + view.id() + "\"] .mc-button");
            iconTint = button == null ? null : Text.of(button).color.getValue();
            // Xaero lifts hovered icons by one GUI pixel.
            iconOffset = (button != null && button.isActive ? 1 : -1)
                    + (widget.active && NativeMapSkin.hovered(widget) ? 1 : 0);
            try {
                int left = NativeMapSkin.x(textured), top = NativeMapSkin.y(textured);
                int right = left + textured.getWidth(), bottom = top + textured.getHeight();
                if (menu == null || right <= menu.x() || left >= menu.x() + menu.width()
                        || bottom <= menu.y() || top >= menu.y() + menu.height()) {
                    renderer.draw(graphics, textured, mouseX, mouseY);
                    continue;
                }
                region(graphics, textured, mouseX, mouseY, left, top, Math.min(right, menu.x()), bottom, renderer);
                region(graphics, textured, mouseX, mouseY, Math.max(left, menu.x() + menu.width()), top, right, bottom, renderer);
                int middleLeft = Math.max(left, menu.x()), middleRight = Math.min(right, menu.x() + menu.width());
                region(graphics, textured, mouseX, mouseY, middleLeft, top, middleRight, Math.min(bottom, menu.y()), renderer);
                region(graphics, textured, mouseX, mouseY, middleLeft, Math.max(top, menu.y() + menu.height()), middleRight, bottom, renderer);
            } finally { iconTint = null; }
        }
    }

    private static <G> void region(G graphics, GuiTexturedButton button, int mouseX, int mouseY,
            int left, int top, int right, int bottom, IconRenderer<G> renderer) {
        if (right > left && bottom > top) renderer.drawRegion(graphics, button, mouseX, mouseY, left, top, right, bottom);
    }

    static Integer settingsColor() {
        long generation = document.getRefreshGeneration();
        if (settingsSurface == null || !settingsSurface.isConnected() || settingsSurfaceGeneration != generation) {
            settingsSurface = document.querySelector(".xaeroearth-settings-surface");
            settingsSurfaceGeneration = generation;
        }
        return settingsSurface == null ? null : Color.parse(Background.of(settingsSurface).color);
    }

    static List<TooltipView> pendingTooltips(Object graphics) {
        if (graphics != renderedExtractor || graphics != tooltipExtractor || tooltips.isEmpty()) return List.of();
        var pending = List.copyOf(tooltips);
        tooltips.clear();
        return screen == null || NativeMapSkin.currentScreen() != screen || !EarthPreferences.ENABLED.get()
                ? List.of() : pending;
    }
}
