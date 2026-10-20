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
import net.minecraft.client.gui.GuiGraphics;
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

    private static final NativeMapSkinPaint.IconRenderer<GuiGraphics> ICONS = new NativeMapSkinPaint.IconRenderer<>() {
        @Override public void draw(GuiGraphics graphics, GuiTexturedButton button, int mouseX, int mouseY) {
            button.renderWidget(graphics, mouseX, mouseY, 0.0F);
        }
        @Override public void drawRegion(GuiGraphics graphics, GuiTexturedButton button, int mouseX, int mouseY,
                int left, int top, int right, int bottom) {
            drawIconRegion(graphics, button, mouseX, mouseY, left, top, right, bottom);
        }
    };

    private NativeMapSkin() {
    }


    protected static void placeWidget(AbstractWidget widget, int width, int height, int x, int y) {
        widget.setWidth(width);
        ((io.github.kltyton.xaeroearth.client.mixin.WidgetBoundsAccess) widget).earth$height(height);
        widget.setPosition(x, y);
    }


    /** Submits the document layer first, then restores Xaero's original foreground content. */
    public static void render(Screen target, GuiGraphics graphics, int mouseX, int mouseY) {
        if (target == null || graphics == null || target != screen || document == null || document.isDisposed()) return;

        publishState();
        updateFeedback();
        graphics.flush();
        try (var overlay = KuiServices.render().pushFilterRenderState()) {
            Base.pushDepthTest(false);
            try {
                Base.drawOverlayDocument(graphics.pose(), document);
                Base.commitDraws();
                graphics.flush();
            } finally {
                Base.popDepthTest();
            }
        }
        if (hidden()) return;

        try (var foreground = KuiServices.render().pushFilterRenderState()) {
            KuiServices.render().disableDepthTest();
            NativeMapSkinPaint.icons(graphics, mouseX, mouseY, ICONS);
            graphics.flush();
        }
        renderedExtractor = graphics;
        if (!(target instanceof GuiMap)) flushTooltips(graphics);
    }

    private static void drawIconRegion(GuiGraphics graphics, GuiTexturedButton button, int mouseX, int mouseY,
                                       int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) return;
        graphics.flush();
        graphics.enableScissor(left, top, right, bottom);
        try {
            button.renderWidget(graphics, mouseX, mouseY, 0.0F);
            graphics.flush();
        } finally { graphics.disableScissor(); }
    }

    public static void renderSettingsBackground(GuiGraphics graphics) {
        Integer color = NativeMapSkinPaint.settingsColor();
        if (color != null) graphics.fill(0, 0, screen.width, screen.height, color);
    }

    public static void flushTooltips(GuiGraphics graphics) {
        var pending = NativeMapSkinPaint.pendingTooltips(graphics);
        if (pending.isEmpty()) return;
        graphics.flush();
        try (var foreground = new KuiMapRenderState(Minecraft.getInstance().getMainRenderTarget(), tooltipDepth)) {
            // Native GUI render types enable depth testing when they submit their batches.
            KuiServices.render().setDepthMask(true);
            GlStateManager._clearDepth(1);
            GlStateManager._clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            drawingTooltips = true;
            try {
                for (TooltipView tooltip : pending) tooltip.tooltip().drawBox(graphics, tooltip.x(), tooltip.y(), tooltip.width(), tooltip.height());
                graphics.flush();
            } finally {
                drawingTooltips = false;
                graphics.pose().popPose();
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

    protected static int x(AbstractWidget widget) { return widget.getX(); }
    protected static int y(AbstractWidget widget) { return widget.getY(); }
    protected static boolean hovered(AbstractWidget widget) {
        return widget.isHovered();
    }
    protected static Screen currentScreen() { return Minecraft.getInstance().screen; }
    protected static boolean sliderDragging(AbstractWidget widget, boolean down) {
        return down && screen.getFocused() == widget && screen.isDragging();
    }
    public static void close() {
        tooltipDepth.close();
        closeState();
    }
    protected static void position(AbstractWidget widget, int x, int y) { widget.setPosition(x, y); }
}
