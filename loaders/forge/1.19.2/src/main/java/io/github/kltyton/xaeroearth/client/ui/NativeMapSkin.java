package io.github.kltyton.xaeroearth.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import io.github.kltyton.kltytonui.render.Base;
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
import com.mojang.blaze3d.vertex.PoseStack;
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
    static final boolean MANUAL_RENDERING = true;
    private static final KuiMapDepthTarget tooltipDepth = new KuiMapDepthTarget();

    private NativeMapSkin() {
    }


    protected static void position(AbstractWidget widget, int x, int y) {
        widget.x = x;
        widget.y = y;
    }

    protected static void placeWidget(AbstractWidget widget, int width, int height, int x, int y) {
        widget.setWidth(width);
        widget.setHeight(height);
        position(widget, x, y);
    }


    /** Submits the document layer first, then restores Xaero's original foreground content. */
    public static void render(Screen target, PoseStack graphics, int mouseX, int mouseY) {
        if (target == null || graphics == null || target != screen || document == null || document.isDisposed()) return;

        publishState();
        updateFeedback();
        net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        try (var overlay = KuiServices.render().pushFilterRenderState()) {
            Base.pushDepthTest(false);
            try {
                Base.drawOverlayDocument(graphics, document);
                Base.commitDraws();
                net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            } finally {
                Base.popDepthTest();
            }
        }
        if (hidden()) return;

        WidgetBounds openMenu = openMenuBounds();
        try (var foreground = KuiServices.render().pushFilterRenderState()) {
            KuiServices.render().disableDepthTest();
            for (WidgetView view : widgets) {
                AbstractWidget widget = view.widget();
                if (!widget.visible) continue;
                if (widget instanceof GuiTexturedButton texturedButton) {
                    var button = document.querySelector("[data-native-key=\"widget-" + view.id() + "\"] .mc-button");
                    iconTint = button == null ? null : io.github.kltyton.kltytonui.style.Text.of(button).color.getValue();
                    // Xaero already lifts hovered icons by one GUI pixel.
                    iconOffset = (button != null && button.isActive ? 1 : -1)
                            + (widget.active && ((io.github.kltyton.xaeroearth.client.mixin.AbstractWidgetAccess) widget).earth$isHovered() ? 1 : 0);
                try { drawIcon(graphics, texturedButton, mouseX, mouseY, openMenu); }
                    finally { iconTint = null; }
                }
            }
            net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        }
        renderedExtractor = graphics;
        if (!(target instanceof GuiMap)) flushTooltips(graphics);
    }

    private static void drawIcon(PoseStack graphics, GuiTexturedButton button, int mouseX, int mouseY, WidgetBounds menu) {
        int left = button.x, top = button.y;
        int right = left + button.getWidth(), bottom = top + button.getHeight();
        if (menu == null || right <= menu.x() || left >= menu.x() + menu.width()
                || bottom <= menu.y() || top >= menu.y() + menu.height()) {
            button.renderButton(graphics, mouseX, mouseY, 0.0F);
            return;
        }
        drawIconRegion(graphics, button, mouseX, mouseY, left, top, Math.min(right, menu.x()), bottom);
        drawIconRegion(graphics, button, mouseX, mouseY, Math.max(left, menu.x() + menu.width()), top, right, bottom);
        int middleLeft = Math.max(left, menu.x()), middleRight = Math.min(right, menu.x() + menu.width());
        drawIconRegion(graphics, button, mouseX, mouseY, middleLeft, top, middleRight, Math.min(bottom, menu.y()));
        drawIconRegion(graphics, button, mouseX, mouseY, middleLeft, Math.max(top, menu.y() + menu.height()), middleRight, bottom);
    }

    private static void drawIconRegion(PoseStack graphics, GuiTexturedButton button, int mouseX, int mouseY,
                                       int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) return;
        net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        net.minecraft.client.gui.GuiComponent.enableScissor(left, top, right, bottom);
        try {
            button.renderButton(graphics, mouseX, mouseY, 0.0F);
            net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        } finally { net.minecraft.client.gui.GuiComponent.disableScissor(); }
    }

    public static void renderSettingsBackground(PoseStack graphics) {
        long generation = document.getRefreshGeneration();
        if (settingsSurface == null || !settingsSurface.isConnected() || settingsSurfaceGeneration != generation) {
            settingsSurface = document.querySelector(".xaeroearth-settings-surface");
            settingsSurfaceGeneration = generation;
        }
        if (settingsSurface != null) {
            net.minecraft.client.gui.GuiComponent.fill(graphics, 0, 0, screen.width, screen.height, Color.parse(Background.of(settingsSurface).color));
        }
    }

    public static void flushTooltips(PoseStack graphics) {
        if (graphics != renderedExtractor || graphics != tooltipExtractor || tooltips.isEmpty()) return;
        var pending = List.copyOf(tooltips);
        tooltips.clear();
        if (screen == null || Minecraft.getInstance().screen != screen || !EarthPreferences.ENABLED.get()) return;
        net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        try (var foreground = new KuiMapRenderState(Minecraft.getInstance().getMainRenderTarget(), tooltipDepth)) {
            // Native GUI render types enable depth testing when they submit their batches.
            KuiServices.render().setDepthMask(true);
            GlStateManager._clearDepth(1);
            GlStateManager._clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            graphics.pushPose();
            graphics.translate(0, 0, 400);
            drawingTooltips = true;
            try {
                for (TooltipView tooltip : pending) tooltip.tooltip().drawBox(graphics, tooltip.x(), tooltip.y(), tooltip.width(), tooltip.height());
                net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            } finally {
                drawingTooltips = false;
                graphics.popPose();
            }
        }
    }

    protected static boolean leftButtonDown() {
        return org.lwjgl.glfw.GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(),
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    protected static String translationKey(Component component) {
        return component.getContents() instanceof TranslatableContents translated ? translated.getKey() : null;
    }

    protected static int x(AbstractWidget widget) { return widget.x; }
    protected static int y(AbstractWidget widget) { return widget.y; }
    protected static boolean hovered(AbstractWidget widget) {
        return ((io.github.kltyton.xaeroearth.client.mixin.AbstractWidgetAccess) widget).earth$isHovered();
    }
    protected static Screen currentScreen() { return Minecraft.getInstance().screen; }
    protected static boolean sliderDragging(AbstractWidget widget, boolean down) {
        return down && screen.getFocused() == widget && screen.isDragging();
    }
    public static void close() {
        tooltipDepth.close();
        closeState();
    }
}
