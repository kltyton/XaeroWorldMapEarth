package io.github.kltyton.xaeroearth.client.ui;

import io.github.kltyton.kltytonui.parser.Color;
import io.github.kltyton.kltytonui.style.Background;
import io.github.kltyton.kltytonui.style.Text;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.mixin.GuiTexturedButtonAccess;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import xaero.map.gui.GuiTexturedButton;

/** Shares icon clipping and tooltip ownership across native GUI drawing APIs. */
final class NativeMapSkinPaint extends NativeMapSkinState {
    interface IconRenderer<G> {
        void draw(G graphics, AbstractWidget button, int mouseX, int mouseY);
        void drawRegion(G graphics, AbstractWidget button, int mouseX, int mouseY,
                        int left, int top, int right, int bottom);
    }

    private NativeMapSkinPaint() { }

    static <G> void icons(G graphics, int mouseX, int mouseY, IconRenderer<G> renderer) {
        if (mountingControls()) return;
        WidgetBounds menu = openMenuBounds();
        double scale = document.getViewport().renderScale();
        for (WidgetView view : widgets) {
            var widget = view.widget();
            if (!widget.visible || !hasNativeForeground(widget)) continue;
            String buttonSelector = "[data-native-key=\"widget-" + view.id() + "\"] .mc-button";
            var button = queryCached(buttonSelector);
            iconTint = button != null && widget instanceof GuiTexturedButton ? Text.of(button).color.getValue() : null;
            int originalX = NativeMapSkin.x(widget), originalY = NativeMapSkin.y(widget);
            boolean swatch = isDrawingSwatch(widget);
            // XaeroPlus draws the swatch at its own fixed offset. Center it on the actual
            // button box; the KUI content anchor can move with the button's bevel.
            var anchor = button == null || swatch ? button : queryCached(buttonSelector + " .xaeroearth-native-anchor");
            double centerX = originalX + widget.getWidth() / 2.0;
            double centerY = originalY + widget.getHeight() * (swatch ? 0.5 : 0.45);
            if (anchor != null) {
                var rect = anchor.getBoundingClientRect();
                if (rect.width > 0 && rect.height > 0) {
                    centerX = (rect.left + rect.width / 2) * scale;
                    centerY = (rect.top + rect.height / 2) * scale;
                }
            }
            int width = widget.getWidth(), height = widget.getHeight();
            int artWidth = 10, artHeight = 10, artX = 6, artY = 6;
            if (widget instanceof GuiTexturedButtonAccess texture) {
                artWidth = texture.earth$textureWidth();
                artHeight = texture.earth$textureHeight();
                artX = width / 2 - artWidth / 2;
                artY = height / 2 - artHeight / 2;
            }
            boolean hovered = swatch
                    ? mouseX >= originalX && mouseX < originalX + width
                        && mouseY >= originalY && mouseY < originalY + height
                    : NativeMapSkin.hovered(widget);
            // Both native renderers move their foreground up one pixel while hovered.
            int dx = (int) Math.round(centerX - artWidth / 2.0) - originalX - artX;
            int dy = (int) Math.round(centerY - artHeight / 2.0) - originalY - artY
                    + (widget.active && hovered ? 1 : 0);
            try {
                int left = originalX, top = originalY;
                int right = left + width, bottom = top + height;
                if (dx != 0 || dy != 0) NativeMapSkin.position(widget, originalX + dx, originalY + dy);
                if (menu == null || right <= menu.x() || left >= menu.x() + menu.width()
                        || bottom <= menu.y() || top >= menu.y() + menu.height()) {
                    renderer.draw(graphics, widget, mouseX + dx, mouseY + dy);
                    continue;
                }
                region(graphics, widget, mouseX + dx, mouseY + dy, left, top, Math.min(right, menu.x()), bottom, renderer);
                region(graphics, widget, mouseX + dx, mouseY + dy, Math.max(left, menu.x() + menu.width()), top, right, bottom, renderer);
                int middleLeft = Math.max(left, menu.x()), middleRight = Math.min(right, menu.x() + menu.width());
                region(graphics, widget, mouseX + dx, mouseY + dy, middleLeft, top, middleRight, Math.min(bottom, menu.y()), renderer);
                region(graphics, widget, mouseX + dx, mouseY + dy, middleLeft, Math.max(top, menu.y() + menu.height()), middleRight, bottom, renderer);
            } finally {
                if (dx != 0 || dy != 0) NativeMapSkin.position(widget, originalX, originalY);
                iconTint = null;
            }
        }
    }

    private static <G> void region(G graphics, AbstractWidget button, int mouseX, int mouseY,
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
