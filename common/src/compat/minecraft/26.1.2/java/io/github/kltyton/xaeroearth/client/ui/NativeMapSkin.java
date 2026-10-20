package io.github.kltyton.xaeroearth.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.client.gui.KltytonGuiLayers;
import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.layout.Box;
import io.github.kltyton.kltytonui.parser.Color;
import io.github.kltyton.kltytonui.spi.KuiServices;
import io.github.kltyton.kltytonui.style.Background;
import io.github.kltyton.kltytonui.style.Text;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.mixin.AbstractSliderButtonAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import xaero.map.gui.GuiMap;
import xaero.map.gui.GuiTexturedButton;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.lib.client.gui.config.EditConfigScreen;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Paints an KUI/McUI skin over the existing Xaero map widgets. */
public final class NativeMapSkin extends NativeMapSkinState {
    static final boolean MANUAL_RENDERING = false;

    private static final NativeMapSkinPaint.IconRenderer<GuiGraphicsExtractor> ICONS = new NativeMapSkinPaint.IconRenderer<>() {
        @Override public void draw(GuiGraphicsExtractor graphics, GuiTexturedButton button, int mouseX, int mouseY) {
            button.extractContents(graphics, mouseX, mouseY, 0.0F);
        }
        @Override public void drawRegion(GuiGraphicsExtractor graphics, GuiTexturedButton button, int mouseX, int mouseY,
                int left, int top, int right, int bottom) {
            drawIconRegion(graphics, button, mouseX, mouseY, left, top, right, bottom);
        }
    };

    private NativeMapSkin() {
    }



    /** Submits the document layer first, then restores Xaero's original foreground content. */
    public static void render(Screen target, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (target == null || graphics == null || target != screen || document == null || document.isDisposed()) return;

        publishState();
        updateFeedback();
        KltytonGuiLayers.submitUi(graphics);
        if (hidden()) return;

        NativeMapSkinPaint.icons(graphics, mouseX, mouseY, ICONS);
        renderedExtractor = graphics;
        if (!(target instanceof GuiMap)) flushTooltips(graphics);
    }

    private static void drawIconRegion(GuiGraphicsExtractor graphics, GuiTexturedButton button, int mouseX, int mouseY,
                                       int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) return;
        graphics.enableScissor(left, top, right, bottom);
        try {
            button.extractContents(graphics, mouseX, mouseY, 0.0F);
        } finally { graphics.disableScissor(); }
    }

    public static void renderSettingsBackground(GuiGraphicsExtractor graphics) {
        Integer color = NativeMapSkinPaint.settingsColor();
        if (color != null) graphics.fill(0, 0, screen.width, screen.height, color);
    }

    public static void flushTooltips(GuiGraphicsExtractor graphics) {
        var pending = NativeMapSkinPaint.pendingTooltips(graphics);
        if (pending.isEmpty()) return;
        graphics.nextStratum();
        drawingTooltips = true;
        try {
            for (TooltipView tooltip : pending) tooltip.tooltip().drawBox(graphics, tooltip.x(), tooltip.y(), tooltip.width(), tooltip.height());
        } finally {
            drawingTooltips = false;
        }
    }

    protected static boolean leftButtonDown() {
        return org.lwjgl.glfw.GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().handle(),
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    protected static String translationKey(Component component) {
        return component.getContents() instanceof TranslatableContents translated ? translated.getKey() : null;
    }

    protected static int x(AbstractWidget widget) { return widget.getX(); }
    protected static int y(AbstractWidget widget) { return widget.getY(); }
    protected static boolean hovered(AbstractWidget widget) {
        return widget.isHovered();
    }
    protected static Screen currentScreen() { return Minecraft.getInstance().screen; }
    protected static boolean sliderDragging(AbstractWidget widget, boolean down) {
        return ((AbstractSliderButtonAccess) widget).earth$isDragging();
    }
    public static void close() {
        closeState();
    }
    protected static void position(AbstractWidget widget, int x, int y) { widget.setPosition(x, y); }
    protected static void placeWidget(AbstractWidget widget, int width, int height, int x, int y) {
        widget.setRectangle(width, height, x, y);
    }
}
