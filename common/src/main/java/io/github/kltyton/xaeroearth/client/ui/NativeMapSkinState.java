package io.github.kltyton.xaeroearth.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kltyton.kltytonui.KltytonUI;
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
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
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

abstract class NativeMapSkinState {
    protected static final String DOCUMENT_PATH = "xaeroearth/theme.html";

    protected static Screen screen;
    protected static Document document;
    protected static long sentGeneration = Long.MIN_VALUE;
    protected static String sentState;
    protected static int nextId;
    protected static Integer iconTint;
    protected static int iconOffset;
    protected static boolean pointerDown;
    protected static String pressedKey;
    protected static Element settingsSurface;
    protected static long settingsSurfaceGeneration = Long.MIN_VALUE;
    protected static Object renderedExtractor;
    protected static Object tooltipExtractor;
    protected static boolean drawingTooltips;
    protected static final List<TooltipView> tooltips = new ArrayList<>();
    protected static final Map<String, Integer> widgetIds = new java.util.HashMap<>();
    protected static final Map<DropDownWidget, Integer> dropdownIds = new IdentityHashMap<>();
    protected static final List<WidgetView> widgets = new ArrayList<>();
    protected static final List<SlotView> slots = new ArrayList<>();
    protected static final Map<AbstractWidget, WidgetBounds> originalBounds = new IdentityHashMap<>();
    protected static final Map<AbstractWidget, String> docks = new IdentityHashMap<>();

    public static void prewarm() {
        if (document == null || document.isDisposed()) {
            document = KltytonUI.createDocument(DOCUMENT_PATH);
            if (NativeMapSkin.MANUAL_RENDERING) document.setManuallyRendered(true);
            sentGeneration = Long.MIN_VALUE;
            sentState = null;
            Document.runWithContext(document, () -> KuiServices.script().eval(
                    "xaeroMapSkinUpdate({visible:false,controls:[]});", null, "xaeroearth/map-skin-prewarm"));
        }
    }

    public static void open(Screen target) {
        if (target == null) return;
        if (screen != target) {
            restoreBounds();
            screen = target;
            widgets.clear(); slots.clear(); sentState = null;
            pointerDown = NativeMapSkin.leftButtonDown();
            pressedKey = null;
        }
        prewarm();
    }

    /** Starts the per-frame snapshot before the native screen extracts its widgets. */
    public static void beginFrame(Screen target) {
        if (target == null) return;
        if (screen != target || document == null || document.isDisposed()) open(target);
        widgets.clear();
        slots.clear();
        tooltips.clear();
        tooltipExtractor = null;
        renderedExtractor = null;
        if (hidden()) return;
        if (target instanceof GuiMap) layoutMapControls(target);

        for (GuiEventListener child : target.children()) {
            if (child instanceof DropDownWidget || !(child instanceof AbstractWidget widget)
                    || !(widget instanceof AbstractButton || widget instanceof AbstractSliderButton)) continue;
            String rectangle = NativeMapSkin.x(widget) + ":" + NativeMapSkin.y(widget) + ":" + widget.getWidth() + ":" + widget.getHeight();
            int id = widgetIds.computeIfAbsent(rectangle, ignored -> nextId++);
            widgets.add(new WidgetView(widget, id));
        }
    }

    protected static void layoutMapControls(Screen target) {
        docks.clear();
        boolean nativeMenu = target instanceof GuiMap map && (map.waypointMenu || map.playersMenu);
        boolean caveMenu = target instanceof GuiMap map
                && ((io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess) map).earth$caveModeOptions().isEnabled();
        int contentWidth = target.width;
        if (nativeMenu) for (GuiEventListener child : target.children()) {
            if (child instanceof net.minecraft.client.gui.components.EditBox field && field.visible)
                contentWidth = Math.min(contentWidth, NativeMapSkin.x(field));
        }
        List<AbstractWidget> left = new ArrayList<>();
        List<AbstractWidget> right = new ArrayList<>();
        List<AbstractWidget> views = new ArrayList<>();
        for (GuiEventListener child : target.children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            String key = NativeMapSkin.translationKey(widget.getMessage());
            if (!(widget instanceof GuiTexturedButton) && (key == null || !key.startsWith("xaeroearth.view."))) continue;
            WidgetBounds original = originalBounds.computeIfAbsent(widget,
                    ignored -> new WidgetBounds(NativeMapSkin.x(widget), NativeMapSkin.y(widget), widget.getWidth(), widget.getHeight()));
            if (!widget.visible) continue;
            if (!(widget instanceof GuiTexturedButton)) views.add(widget);
            else if (original.x() >= target.width / 2) {
                if (nativeMenu) NativeMapSkin.placeWidget(widget, original.width(), original.height(), original.x(), original.y());
                else right.add(widget);
            }
            else if (original.y() < 40) {
                NativeMapSkin.position(widget, 6, 6);
                docks.put(widget, "settings");
            } else left.add(widget);
        }
        layoutDock(left, target, false);
        layoutDock(right, target, true);
        int viewWidth = Math.max(36, Math.min(64, (contentWidth - 88) / Math.max(1, views.size())));
        int totalWidth = views.size() * viewWidth;
        for (int i = 0; i < views.size(); i++) {
            AbstractWidget widget = views.get(i);
            NativeMapSkin.placeWidget(widget, viewWidth, 20, (contentWidth - totalWidth) / 2 + i * viewWidth,
                    Math.max(6, target.height - (caveMenu ? 84 : 40)));
            docks.put(widget, "views");
        }
    }

    protected static void layoutDock(List<AbstractWidget> controls, Screen target, boolean right) {
        controls.sort(java.util.Comparator.comparingInt(widget -> originalBounds.get(widget).y()));
        int cellHeight = controls.stream().mapToInt(AbstractWidget::getHeight).max().orElse(20) + 2;
        int cellWidth = controls.stream().mapToInt(AbstractWidget::getWidth).max().orElse(20) + 2;
        int rows = Math.max(1, Math.min(controls.size(), (target.height - 48) / cellHeight));
        int top = Math.max(6, target.height - 6 - rows * cellHeight + 2);
        for (int i = 0; i < controls.size(); i++) {
            AbstractWidget widget = controls.get(i);
            int column = i / rows;
            NativeMapSkin.position(widget, right ? target.width - 6 - widget.getWidth() - column * cellWidth : 6 + column * cellWidth,
                    top + i % rows * cellHeight);
            docks.put(widget, right ? "tools-right" : "tools-left");
        }
    }

    protected static void restoreBounds() {
        originalBounds.forEach((widget, bounds) -> NativeMapSkin.placeWidget(widget, bounds.width(), bounds.height(), bounds.x(), bounds.y()));
        originalBounds.clear();
        docks.clear();
    }

    public static boolean recordSlot(
            DropDownWidget dropdown,
            Component component,
            int slotIndex,
            int x,
            int y,
            int width,
            boolean active,
            boolean visible,
            boolean selected,
            boolean hovered,
            boolean focused,
            boolean openingUp,
            int scroll,
            int yOffset
    ) {
        if (screen == null || document == null || document.isDisposed() || NativeMapSkin.currentScreen() != screen
                || dropdown == null || component == null || hidden()) return false;
        int dropdownId = dropdownIds.computeIfAbsent(dropdown, ignored -> nextId++);
        slots.add(new SlotView(dropdownId, slots.size(), component, slotIndex,
                x, y, width, active, visible, selected, hovered, focused, openingUp, scroll, yOffset));
        return true;
    }

    protected static WidgetBounds openMenuBounds() {
        var openIds = new java.util.HashSet<Integer>();
        dropdownIds.forEach((dropdown, id) -> { if (!dropdown.isClosed()) openIds.add(id); });
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (SlotView slot : slots) {
            if (!slot.visible() || !openIds.contains(slot.dropdownId())) continue;
            left = Math.min(left, slot.x() - 1); top = Math.min(top, slot.y() - 1);
            right = Math.max(right, slot.x() + slot.width() + 1); bottom = Math.max(bottom, slot.y() + 12);
        }
        return left == Integer.MAX_VALUE ? null : new WidgetBounds(left, top, right - left, bottom - top);
    }

    public static boolean settingsBackground() {
        return screen instanceof EditConfigScreen && document != null && !document.isDisposed()
                && EarthPreferences.ENABLED.get() && NativeMapSkin.currentScreen() == screen;
    }

    public static boolean deferTooltip(Tooltip tooltip, Object graphics, int x, int y, int width, int height) {
        if (drawingTooltips || screen == null || document == null || document.isDisposed()
                || !EarthPreferences.ENABLED.get() || NativeMapSkin.currentScreen() != screen || hidden()) return false;
        if (tooltipExtractor != graphics) {
            tooltips.clear();
            tooltipExtractor = graphics;
        }
        tooltips.add(new TooltipView(tooltip, x, y, width, height));
        return true;
    }

    public static Integer currentIconTint() { return iconTint; }

    public static int iconTint(int original) { return iconTint == null ? original : iconTint; }

    public static int iconY(int original) { return iconTint == null ? original : original + iconOffset; }

    public static boolean skinsSlider(AbstractSliderButton slider) {
        if (screen == null || document == null || document.isDisposed() || hidden()
                || !EarthPreferences.ENABLED.get() || NativeMapSkin.currentScreen() != screen) return false;
        for (WidgetView view : widgets) if (view.widget() == slider) return true;
        return false;
    }

    public static Double sliderValueFromMouse(AbstractSliderButton slider, double mouseX) {
        if (!skinsSlider(slider)) return null;
        for (WidgetView view : widgets) {
            if (view.widget() != slider) continue;
            Element visual = document.querySelector("[data-native-key=\"widget-" + view.id() + "\"] .mc-slider__visual");
            if (visual == null) return null;
            Element.DOMRect bounds = visual.getBoundingClientRect();
            if (bounds.width <= 0) return null;
            // KUI's visual bounds include CSS transforms; viewport scale maps them to GUI coordinates.
            return (mouseX / document.getViewport().renderScale() - bounds.left) / bounds.width;
        }
        return null;
    }

    protected static void updateFeedback() {
        boolean down = NativeMapSkin.leftButtonDown();
        SlotView hoveredSlot = null;
        for (SlotView slot : slots) if (slot.visible() && slot.hovered()) hoveredSlot = slot;
        if (!down || hidden()) pressedKey = null;
        else if (!pointerDown) {
            pressedKey = null;
            if (hoveredSlot != null && hoveredSlot.active()) {
                pressedKey = slotKey(hoveredSlot);
            } else if (hoveredSlot == null) {
                for (WidgetView view : widgets) {
                    AbstractWidget widget = view.widget();
                    if (widget.visible && widget.active && NativeMapSkin.hovered(widget)) pressedKey = "widget-" + view.id();
                }
            }
        }
        pointerDown = down;
        for (WidgetView view : widgets) {
            AbstractWidget widget = view.widget();
            String key = "widget-" + view.id();
            Element root = document.querySelector("[data-native-key=\"" + key + "\"]");
            if (root == null) continue;
            boolean enabled = widget.visible && widget.active && !hidden();
            boolean hovered = enabled && hoveredSlot == null && NativeMapSkin.hovered(widget);
            boolean pressed = enabled && (widget instanceof AbstractSliderButton
                    ? NativeMapSkin.sliderDragging(widget, down)
                    : key.equals(pressedKey));
            if (widget instanceof AbstractSliderButton) {
                Element slider = root.querySelector(".mc-slider");
                Element input = root.querySelector(".mc-slider__input");
                if (slider != null) {
                    slider.setHover(hovered);
                    Element label = root.querySelector(".mc-form-field__label");
                    Element field = root.querySelector(".mc-form-field");
                    if (label != null && field != null) fitCaption(field, label, label.getTextContent());
                }
                if (input != null) input.setActive(pressed);
            } else {
                Element button = root.querySelector(".mc-button");
                if (button != null) {
                    button.setHover(hovered);
                    button.setActive(pressed);
                    if (!(widget instanceof GuiTexturedButton)) {
                        fitCaption(button, root.querySelector(".mc-button__content"), widget.getMessage().getString());
                    }
                }
            }
        }
        for (SlotView slot : slots) {
            String key = slotKey(slot);
            Element label = document.querySelector("[data-native-key=\"" + key + "\"]");
            Element button = label == null ? null : label.closest(".mc-list__item");
            if (button != null) {
                button.setHover(slot.visible() && slot.active() && slot.hovered() && !hidden());
                button.setActive(slot.visible() && slot.active() && key.equals(pressedKey) && !hidden());
            }
        }
    }

    protected static String slotKey(SlotView slot) {
        return "slot-" + slot.dropdownId() + "-" + slot.ordinal();
    }

    protected static void fitCaption(Element control, Element caption, String label) {
        if (caption == null || label.isEmpty()) return;
        Text actual = Text.of(caption);
        Text measuring = new Text();
        measuring.fontSize = Text.of(control).fontSize;
        measuring.fontFamily = actual.fontFamily;
        measuring.fontWeight = actual.fontWeight;
        measuring.oblique = actual.oblique;
        measuring.strokeWidth = actual.strokeWidth;
        measuring.letterSpacing = actual.letterSpacing;
        double width = Text.measureLine(measuring, label);
        double available = Box.of(control).rawInnerSize().width();
        Element value = control.querySelector(".mc-slider__value");
        if (value != null) {
            available -= Text.measureLine(Text.of(value), value.getTextContent()) + measuring.fontSize / 2;
        }
        if (width <= 0 || available <= 0) return;
        String fittedSize = Double.toString(measuring.fontSize * Math.min(1.0, available / width)) + "px";
        if (!fittedSize.equals(caption.getInlineStylePropertyValue("font-size"))) {
            caption.setInlineStyleProperty("font-size", fittedSize);
        }
    }

    public static void suspend() {
        if (screen == null) return;
        restoreBounds();
        screen = null; widgets.clear(); slots.clear(); sentState = null;
        widgetIds.clear(); dropdownIds.clear(); nextId = 0;
        pressedKey = null;
        tooltips.clear(); tooltipExtractor = null; renderedExtractor = null;
        if (document != null && !document.isDisposed()) Document.runWithContext(document,
                () -> KuiServices.script().eval("xaeroMapSkinUpdate({visible:false,controls:[]});", null, "xaeroearth/map-skin-hide"));
    }

    protected static void closeState() {
        restoreBounds();
        if (document != null && !document.isDisposed()) {
            if (document.body != null) {
                Event.triggerSingle(new Event(document.body, "unload", false));
            }
            document.remove();
        }
        document = null;
        screen = null;
        sentGeneration = Long.MIN_VALUE;
        sentState = null;
        nextId = 0;
        pointerDown = false;
        pressedKey = null;
        settingsSurface = null; settingsSurfaceGeneration = Long.MIN_VALUE;
        tooltips.clear(); tooltipExtractor = null; renderedExtractor = null;
        widgetIds.clear();
        dropdownIds.clear();
        widgets.clear();
        slots.clear();
    }

    protected static void publishState() {
        JsonObject state = new JsonObject();
        state.addProperty("visible", !hidden());
        JsonArray controls = new JsonArray();
        double documentScale = document.getViewport().renderScale();
        state.addProperty("settings", screen instanceof EditConfigScreen);
        state.addProperty("width", screen.width / documentScale);
        state.addProperty("height", screen.height / documentScale);
        for (WidgetView view : widgets) {
            AbstractWidget widget = view.widget();
            JsonObject control = new JsonObject();
            control.addProperty("key", "widget-" + view.id());
            String translationKey = NativeMapSkin.translationKey(widget.getMessage());
            boolean viewButton = translationKey != null && translationKey.startsWith("xaeroearth.view.");
            control.addProperty("role", widget instanceof AbstractSliderButton ? "slider"
                    : widget instanceof GuiTexturedButton ? "icon" : viewButton ? "view" : "button");
            if (widget instanceof AbstractSliderButton) {
                control.addProperty("value", ((AbstractSliderButtonAccess) widget).earth$getValue());
            }
            control.addProperty("label", widget instanceof GuiTexturedButton ? "" : widget.getMessage().getString());
            control.addProperty("dock", docks.getOrDefault(widget, ""));
            control.addProperty("primary", "gui.done".equals(translationKey));
            control.addProperty("selected", viewButton && translationKey.equals(
                    "xaeroearth.view." + EarthPreferences.VIEW.get().name().toLowerCase(Locale.ROOT)));
            control.addProperty("x", NativeMapSkin.x(widget) / documentScale);
            control.addProperty("y", NativeMapSkin.y(widget) / documentScale);
            control.addProperty("width", widget.getWidth() / documentScale);
            control.addProperty("height", widget.getHeight() / documentScale);
            control.addProperty("visible", widget.visible);
            control.addProperty("active", widget.active);
            control.addProperty("hovered", NativeMapSkin.hovered(widget));
            control.addProperty("focused", widget.isFocused());
            controls.add(control);
        }
        var expandedMenus = new java.util.HashSet<Integer>();
        dropdownIds.forEach((dropdown, id) -> { if (!dropdown.isClosed()) expandedMenus.add(id); });
        for (SlotView slot : slots) {
            JsonObject control = new JsonObject();
            control.addProperty("key", slotKey(slot));
            control.addProperty("role", "menu");
            control.addProperty("menuId", slot.dropdownId());
            control.addProperty("expanded", expandedMenus.contains(slot.dropdownId()));
            JsonArray runs = new JsonArray();
            slot.component().visit((style, text) -> {
                JsonObject run = new JsonObject(); run.addProperty("text", text);
                if (style.getColor() != null) {
                    int rgb = style.getColor().getValue() & 0xFFFFFF;
                    if (((rgb >> 16) & 255) != ((rgb >> 8) & 255) || ((rgb >> 8) & 255) != (rgb & 255))
                        run.addProperty("color", String.format(Locale.ROOT, "#%06x", rgb));
                }
                runs.add(run); return java.util.Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY);
            control.add("runs", runs);
            control.addProperty("x", slot.x() / documentScale);
            control.addProperty("y", slot.y() / documentScale);
            control.addProperty("width", slot.width() / documentScale);
            control.addProperty("height", 11 / documentScale);
            control.addProperty("visible", slot.visible());
            control.addProperty("active", slot.active());
            control.addProperty("slotIndex", slot.slotIndex());
            control.addProperty("selected", slot.selected());
            control.addProperty("hovered", slot.hovered());
            control.addProperty("focused", slot.focused());
            control.addProperty("openingUp", slot.openingUp());
            control.addProperty("scroll", slot.scroll());
            control.addProperty("yOffset", slot.yOffset());
            controls.add(control);
        }
        state.add("controls", controls);

        String serialized = state.toString();
        long generation = document.getRefreshGeneration();
        if (generation == sentGeneration && serialized.equals(sentState)) return;
        Document.runWithContext(document, () -> KuiServices.script().eval(
                "if (typeof xaeroMapSkinUpdate === 'function') xaeroMapSkinUpdate(" + serialized + ");",
                null,
                "xaeroearth/map-skin-state"
        ));
        sentGeneration = generation;
        sentState = serialized;
    }

    protected static boolean hidden() { return screen instanceof GuiMap && GuiMap.hiddenUI; }

    protected record WidgetView(AbstractWidget widget, int id) {
    }

    protected record WidgetBounds(int x, int y, int width, int height) {
    }

    protected record TooltipView(Tooltip tooltip, int x, int y, int width, int height) {
    }

    protected record SlotView(
            int dropdownId,
            int ordinal,
            Component component,
            int slotIndex,
            int x,
            int y,
            int width,
            boolean active,
            boolean visible,
            boolean selected,
            boolean hovered,
            boolean focused,
            boolean openingUp,
            int scroll,
            int yOffset
    ) {
    }
}
