package io.github.kltyton.xaeroearth.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.layout.Box;
import io.github.kltyton.kltytonui.parser.Color;
import io.github.kltyton.kltytonui.style.Background;
import io.github.kltyton.kltytonui.style.Text;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.mixin.AbstractSliderButtonAccess;
import io.github.kltyton.xaeroearth.client.mixin.GuiTexturedButtonAccess;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import io.github.kltyton.xaeroearth.client.integration.xaeroplus.XaeroPlusColorPickerAccess;
import io.github.kltyton.xaeroearth.client.ui.measurement.MapRuler;
import io.github.kltyton.xaeroearth.client.navigation.NavigationTrace;
import io.github.kltyton.xaeroearth.client.mapmark.MapAnnotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import xaero.map.gui.GuiMap;
import xaero.map.gui.GuiTexturedButton;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.lib.client.gui.config.EditConfigScreen;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

abstract class NativeMapSkinState {
    protected static final String DOCUMENT_PATH = "xaeroearth/theme.html";
    private static final String SKIN_STATE_EVENT = "xaeroearth-skin-state";
    private static final String SKIN_OVERLAY_EVENT = "xaeroearth-skin-overlay";
    private static final String[] DOCK_KEYS = { "settings", "tools-left", "tools-right", "views" };

    protected static Screen screen;
    protected static Document document;
    protected static long sentGeneration = Long.MIN_VALUE;
    protected static JsonObject sentState;
    private static JsonObject sentOverlay;
    private static final Map<String, Boolean> announcedVisibility = new java.util.HashMap<>();
    protected static int nextId;
    protected static Integer iconTint;
    protected static int iconOffset;
    protected static boolean pointerDown;
    protected static String pressedKey;
    protected static Element settingsSurface;
    protected static long settingsSurfaceGeneration = Long.MIN_VALUE;
    private static final Map<String, Element> cachedElements = new java.util.HashMap<>();
    private static long elementCacheGeneration = Long.MIN_VALUE;
    protected static Object renderedExtractor;
    protected static Object tooltipExtractor;
    protected static boolean drawingTooltips;
    private static AbstractWidget visibleColorPicker;
    private static boolean paletteCoveredLabels;
    private static List<? extends GuiEventListener> lastChildren = List.of();
    protected static final List<TooltipView> tooltips = new ArrayList<>();
    protected static final Map<AbstractWidget, Integer> widgetIds = new IdentityHashMap<>();
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
        }
    }

    public static void open(Screen target) {
        if (target == null) return;
        if (screen != target) {
            restoreBounds();
            screen = target;
            widgets.clear(); slots.clear(); sentState = null; sentOverlay = null;
            widgetIds.clear(); dropdownIds.clear(); nextId = 0;
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
        visibleColorPicker = null;
        tooltipExtractor = null;
        renderedExtractor = null;
        var children = target.children();
        reconcileChildren(children);
        if (hidden()) return;
        if (target instanceof GuiMap) layoutMapControls(target);

        for (GuiEventListener child : children) {
            if (child instanceof DropDownWidget || !(child instanceof AbstractWidget widget)
                    || !(widget instanceof AbstractButton || widget instanceof AbstractSliderButton)
                    || isTexturelessHotspot(widget)) continue;
            // Native controls can share bounds, especially when XaeroPlus swaps drawing modes.
            int id = widgetIds.computeIfAbsent(widget, ignored -> nextId++);
            widgets.add(new WidgetView(widget, id));
        }
    }

    /** XaeroPlus can replace the controls without replacing the GuiMap instance. */
    private static void reconcileChildren(List<? extends GuiEventListener> children) {
        int shared = Math.min(lastChildren.size(), children.size());
        int index = 0;
        while (index < shared && lastChildren.get(index) == children.get(index)) index++;
        if (index == shared && lastChildren.size() == children.size()) return;
        var current = Collections.newSetFromMap(new IdentityHashMap<GuiEventListener, Boolean>());
        current.addAll(children);
        var originals = originalBounds.entrySet().iterator();
        while (originals.hasNext()) {
            var entry = originals.next();
            if (current.contains(entry.getKey())) continue;
            restoreBounds(entry.getKey(), entry.getValue());
            originals.remove();
        }
        widgetIds.keySet().removeIf(widget -> !current.contains(widget));
        lastChildren = List.copyOf(children);
    }

    protected static boolean hasNativeForeground(AbstractWidget widget) {
        // The optional XaeroPlus swatch is a TooltipButton that draws its current color, not a sprite.
        return widget instanceof GuiTexturedButton
                || isDrawingSwatch(widget);
    }

    private static boolean isTexturelessHotspot(AbstractWidget widget) {
        return widget instanceof GuiTexturedButtonAccess sprite
                && (sprite.earth$textureWidth() <= 0 || sprite.earth$textureHeight() <= 0);
    }

    static boolean isDrawingSwatch(AbstractWidget widget) {
        return widget.getClass().getName().equals("xaeroplus.feature.drawing.DrawingColorPickerButton");
    }

    protected static void layoutMapControls(Screen target) {
        final int inset = 6, gap = 6, viewHeight = 20;
        docks.clear();
        boolean nativeMenu = target instanceof GuiMap map && (map.waypointMenu || map.playersMenu);
        boolean caveMenu = target instanceof GuiMap map
                && ((GuiMapAccess) map).earth$caveModeOptions().isEnabled();
        int contentWidth = target.width;
        if (nativeMenu) for (GuiEventListener child : target.children()) {
            if (child instanceof EditBox field && field.visible)
                contentWidth = Math.min(contentWidth, NativeMapSkin.x(field));
        }
        List<AbstractWidget> left = new ArrayList<>();
        List<AbstractWidget> right = new ArrayList<>();
        List<AbstractWidget> views = new ArrayList<>();
        AbstractWidget colorPicker = null;
        AbstractWidget settingsButton = ((GuiMapAccess) target).earth$settingsButton();
        boolean drawing = false;
        for (GuiEventListener child : target.children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            String widgetType = widget.getClass().getName();
            if (widgetType.equals("xaeroplus.feature.drawing.ColorPickerWidget")) {
                rememberBounds(widget);
                if (widget.visible) colorPicker = widget;
                continue;
            }
            if (widget.visible && isDrawingSwatch(widget)) drawing = true;
            if (isTexturelessHotspot(widget)) continue;
            String key = NativeMapSkin.translationKey(widget.getMessage());
            boolean icon = hasNativeForeground(widget);
            if (!icon && (key == null || !key.startsWith("xaeroearth.view."))) continue;
            WidgetBounds original = rememberBounds(widget);
            if (!widget.visible) continue;
            if (!icon) views.add(widget);
            else if (widget == settingsButton) {
                NativeMapSkin.position(widget, inset, inset);
                docks.put(widget, "settings");
            } else if (original.x() >= target.width / 2) {
                if (nativeMenu) NativeMapSkin.placeWidget(widget, original.width(), original.height(), original.x(), original.y());
                else right.add(widget);
            }
            else left.add(widget);
        }
        // XaeroPlus draws its input hints in the left footer independently of the widget list.
        int hintHeight = drawing ? 2 + I18n.get("xaeroplus.gui.world_map.drawing_mode_controls")
                .split("\n").length * (Minecraft.getInstance().font.lineHeight + 1) : 0;
        layoutDock(left, target, false, inset + hintHeight);
        layoutDock(right, target, true, inset);
        int viewTop = Math.max(inset, target.height - (caveMenu ? 92 : 48));
        int viewLeft = left.stream().mapToInt(w -> NativeMapSkin.x(w) + w.getWidth()).max().orElse(inset) + gap;
        int viewRight = right.stream().mapToInt(NativeMapSkin::x).min().orElse(contentWidth - inset) - gap;
        if (colorPicker instanceof XaeroPlusColorPickerAccess) {
            var palette = originalBounds.get(colorPicker);
            int rightEdge = viewRight - viewLeft >= palette.width() ? viewRight : contentWidth - inset;
            int popupX = Math.max(inset, Math.min(viewLeft, rightEdge - palette.width()));
            int bottomLimit = target.height - inset - palette.height();
            if (bottomLimit - hintHeight >= inset + 28) bottomLimit -= hintHeight;
            int popupY = Math.max(inset, Math.min(Math.max(inset + 28, palette.y()), bottomLimit));
            moveColorPicker(colorPicker, popupX, popupY);
        }
        visibleColorPicker = colorPicker;
        if (colorPicker != null && overlapsVertically(colorPicker, viewTop - 4, viewHeight + 8)) {
            int afterPicker = NativeMapSkin.x(colorPicker) + colorPicker.getWidth() + gap;
            if (viewRight - afterPicker >= 40 * views.size()) viewLeft = Math.max(viewLeft, afterPicker);
            else viewTop = Math.max(inset + 28, NativeMapSkin.y(colorPicker) - gap - viewHeight);
        }
        int available = Math.max(views.size(), viewRight - viewLeft);
        int viewWidth = Math.max(1, Math.min(64, available / Math.max(1, views.size())));
        int totalWidth = views.size() * viewWidth;
        int viewX = Math.max(viewLeft, Math.min((contentWidth - totalWidth) / 2, viewRight - totalWidth));
        for (int i = 0; i < views.size(); i++) {
            AbstractWidget widget = views.get(i);
            NativeMapSkin.placeWidget(widget, viewWidth, viewHeight, viewX + i * viewWidth, viewTop);
            docks.put(widget, "views");
        }
    }

    private static WidgetBounds rememberBounds(AbstractWidget widget) {
        return originalBounds.computeIfAbsent(widget,
                ignored -> new WidgetBounds(NativeMapSkin.x(widget), NativeMapSkin.y(widget), widget.getWidth(), widget.getHeight()));
    }

    private static boolean overlapsVertically(AbstractWidget widget, int top, int height) {
        int widgetY = NativeMapSkin.y(widget);
        return top < widgetY + widget.getHeight() && top + height > widgetY;
    }

    private static void moveColorPicker(AbstractWidget widget, int x, int y) {
        int dx = x - NativeMapSkin.x(widget), dy = y - NativeMapSkin.y(widget);
        if (dx == 0 && dy == 0) return;
        NativeMapSkin.position(widget, x, y);
        if (widget instanceof XaeroPlusColorPickerAccess picker) {
            for (EditBox input : picker.earth$colorEntryBoxes()) {
                NativeMapSkin.position(input, NativeMapSkin.x(input) + dx, NativeMapSkin.y(input) + dy);
            }
        }
    }

    protected static void layoutDock(List<AbstractWidget> controls, Screen target, boolean right, int bottomInset) {
        if (controls.isEmpty()) return;
        controls.sort(java.util.Comparator.<AbstractWidget>comparingInt(widget -> originalBounds.get(widget).y())
                .thenComparingInt(widget -> originalBounds.get(widget).x()));
        int cellHeight = controls.stream().mapToInt(w -> originalBounds.get(w).height()).max().orElse(20) + 2;
        int cellWidth = controls.stream().mapToInt(w -> originalBounds.get(w).width()).max().orElse(20) + 2;
        // Reserve the compass and settings at the top. Use all remaining space before wrapping,
        // then distribute compact columns evenly so they do not leave a blank slab in the dock.
        int topInset = 32;
        if (!right) for (var dock : docks.entrySet()) {
            if ("settings".equals(dock.getValue())) {
                int settingsHeight = dock.getKey().getHeight();
                // The themed panels extend 15% beyond their widgets on each side.
                int settingsPadding = (int) Math.ceil(settingsHeight * 0.15);
                int dockPadding = (int) Math.ceil((cellHeight - 2) * 0.15);
                topInset = Math.max(topInset, NativeMapSkin.y(dock.getKey()) + settingsHeight
                        + settingsPadding + dockPadding + 2);
            }
        }
        int capacity = Math.max(1, (target.height - topInset - bottomInset + 2) / cellHeight);
        int columns = (controls.size() + capacity - 1) / capacity;
        int rows = (controls.size() + columns - 1) / columns;
        int top = Math.max(topInset, target.height - bottomInset - rows * cellHeight + 2);
        for (int i = 0; i < controls.size(); i++) {
            AbstractWidget widget = controls.get(i);
            int column = columns == 1 ? 0 : (right ? columns - 1 - i % columns : i % columns);
            var original = originalBounds.get(widget);
            int width = original.width();
            NativeMapSkin.placeWidget(widget, width, original.height(),
                    right ? target.width - 6 - width - column * cellWidth : 6 + column * cellWidth,
                    top + i / columns * cellHeight);
            docks.put(widget, right ? "tools-right" : "tools-left");
        }
    }

    protected static void restoreBounds() {
        originalBounds.forEach(NativeMapSkinState::restoreBounds);
        originalBounds.clear();
        docks.clear();
        lastChildren = List.of();
    }

    private static void restoreBounds(AbstractWidget widget, WidgetBounds bounds) {
        if (widget instanceof XaeroPlusColorPickerAccess) moveColorPicker(widget, bounds.x(), bounds.y());
        else NativeMapSkin.placeWidget(widget, bounds.width(), bounds.height(), bounds.x(), bounds.y());
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
                || dropdown == null || component == null || hidden() || mountingControls()) return false;
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
                || !EarthPreferences.ENABLED.get() || NativeMapSkin.currentScreen() != screen || mountingControls()) return false;
        for (WidgetView view : widgets) if (view.widget() == slider) return true;
        return false;
    }

    protected static Element queryCached(String selector) {
        if (document == null || document.isDisposed()) return null;
        long generation = document.getRefreshGeneration();
        if (generation != elementCacheGeneration) {
            cachedElements.clear();
            elementCacheGeneration = generation;
        }
        Element found = cachedElements.get(selector);
        if (found != null && found.isConnected()) return found;
        found = document.querySelector(selector);
        if (found != null) cachedElements.put(selector, found);
        return found;
    }

    protected static void clearElementCache() {
        cachedElements.clear();
        elementCacheGeneration = Long.MIN_VALUE;
    }

    public static Double sliderValueFromMouse(AbstractSliderButton slider, double mouseX) {
        if (!skinsSlider(slider)) return null;
        for (WidgetView view : widgets) {
            if (view.widget() != slider) continue;
            Element visual = queryCached("[data-native-key=\"widget-" + view.id() + "\"] .mc-slider__visual");
            if (visual == null) return null;
            Element.DOMRect bounds = visual.getBoundingClientRect();
            if (bounds.width <= 0) return null;
            // KUI's visual bounds include CSS transforms; viewport scale maps them to GUI coordinates.
            return (mouseX / document.getViewport().renderScale() - bounds.left) / bounds.width;
        }
        return null;
    }

    protected static void updateFeedback() {
        if (mountingControls()) return;
        updatePaletteLabelVisibility();
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
        double scale = document.getViewport().renderScale();
        for (String dockKey : DOCK_KEYS) {
            Element panel = queryCached("[data-dock-key=\"" + dockKey + "\"]");
            if (panel == null) continue;
            int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
            int firstHeight = 0;
            if (!hidden()) {
                for (WidgetView view : widgets) {
                    AbstractWidget widget = view.widget();
                    if (!widget.visible || !dockKey.equals(docks.get(widget))) continue;
                    int wx = NativeMapSkin.x(widget), wy = NativeMapSkin.y(widget);
                    if (firstHeight == 0) firstHeight = widget.getHeight();
                    left = Math.min(left, wx);
                    top = Math.min(top, wy);
                    right = Math.max(right, wx + widget.getWidth());
                    bottom = Math.max(bottom, wy + widget.getHeight());
                }
            }
            if (left == Integer.MAX_VALUE) {
                inlineProp(panel, "visibility", "hidden");
            } else {
                double padding = (firstHeight / scale) * 0.15;
                inlineSize(panel, "left", left / scale - padding);
                inlineSize(panel, "top", top / scale - padding);
                inlineSize(panel, "width", (right - left) / scale + padding * 2);
                inlineSize(panel, "height", (bottom - top) / scale + padding * 2);
                inlineProp(panel, "visibility", "visible");
            }
        }
        for (WidgetView view : widgets) {
            AbstractWidget widget = view.widget();
            String key = "widget-" + view.id();
            String baseSelector = "[data-native-key=\"" + key + "\"]";
            Element root = queryCached(baseSelector);
            if (root == null) continue;
            if (widget.visible && !hidden()) {
                inlineSize(root, "left", NativeMapSkin.x(widget) / scale);
                inlineSize(root, "top", NativeMapSkin.y(widget) / scale);
                inlineSize(root, "width", widget.getWidth() / scale);
                inlineSize(root, "height", widget.getHeight() / scale);
                inlineProp(root, "visibility", "visible");
            } else {
                inlineProp(root, "visibility", "hidden");
            }
            boolean enabled = widget.visible && widget.active && !hidden();
            boolean hovered = enabled && hoveredSlot == null && NativeMapSkin.hovered(widget);
            boolean pressed = enabled && (widget instanceof AbstractSliderButton
                    ? NativeMapSkin.sliderDragging(widget, down)
                    : hovered && key.equals(pressedKey));
            if (widget instanceof AbstractSliderButton) {
                Element slider = queryCached(baseSelector + " .mc-slider");
                Element input = queryCached(baseSelector + " .mc-slider__input");
                if (slider != null) {
                    slider.setHover(hovered);
                    Element label = queryCached(baseSelector + " .mc-form-field__label");
                    Element field = queryCached(baseSelector + " .mc-form-field");
                    if (label != null && field != null) fitCaption(field, label, label.getTextContent(),
                            Text.of(label).fontSize);
                }
                if (input != null) input.setActive(pressed);
            } else {
                Element button = queryCached(baseSelector + " .mc-button");
                if (button != null) {
                    boolean disabled = !widget.active;
                    if (button.isDisabled() != disabled) button.setDisabled(disabled);
                    button.setHover(hovered);
                    button.setActive(pressed);
                    double fullHeight = widget.getHeight() / scale;
                    double travel = pressed ? fullHeight / 10 : 0;
                    inlineSize(button, "height", fullHeight - travel);
                    inlineSize(button, "min-height", fullHeight - travel);
                    inlineSize(button, "max-height", fullHeight - travel);
                    inlineSize(button, "margin-top", travel);
                    inlineSize(button, "padding-bottom", pressed ? 0 : fullHeight / 10);
                    boolean focused = enabled && widget.isFocused();
                    double unit = fullHeight / 20.0;
                    inlineProp(button, "outline", focused ? unit + "px solid var(--mc-focus, #fff)" : "");
                    inlineProp(button, "outline-offset", focused ? (-2 * unit) + "px" : "");
                    if (!hasNativeForeground(widget)) {
                        double fontSize = widget.getHeight() * 9.0 / 20.0 / scale;
                        fitCaption(button, queryCached(baseSelector + " .mc-button__content"),
                                widget.getMessage().getString(), fontSize);
                    }
                }
            }
        }
        for (SlotView slot : slots) {
            String key = slotKey(slot);
            Element label = queryCached("[data-native-key=\"" + key + "\"]");
            Element button = label == null ? null : label.closest(".mc-list__item");
            if (button != null) {
                double height = 11.0 / scale;
                inlineSize(button, "height", height);
                inlineSize(button, "min-height", height);
                inlineSize(button, "max-height", height);
                inlineSize(label, "font-size", height * 8 / 11);
                inlineSize(label, "line-height", height);
                button.setHover(slot.visible() && slot.active() && slot.hovered() && !hidden());
                button.setActive(slot.visible() && slot.active() && key.equals(pressedKey) && !hidden());
            }
        }
    }

    private static void updatePaletteLabelVisibility() {
        var picker = visibleColorPicker;
        if (picker == null && !paletteCoveredLabels) return;
        paletteCoveredLabels = picker != null;
        double scale = document.getViewport().renderScale();
        double left = picker == null ? 0 : NativeMapSkin.x(picker) / scale;
        double top = picker == null ? 0 : NativeMapSkin.y(picker) / scale;
        double right = picker == null ? 0 : left + picker.getWidth() / scale;
        double bottom = picker == null ? 0 : top + picker.getHeight() / scale;
        for (Element label : document.querySelectorAll(".xaeroearth-ruler-label")) {
            var rect = label.getBoundingClientRect();
            boolean covered = picker != null && rect.left < right && rect.left + rect.width > left
                    && rect.top < bottom && rect.top + rect.height > top;
            String visibility = covered ? "hidden" : "visible";
            if (!visibility.equals(label.getInlineStylePropertyValue("visibility")))
                label.setInlineStyleProperty("visibility", visibility);
        }
    }

    protected static String slotKey(SlotView slot) {
        return "slot-" + slot.dropdownId() + "-" + slot.ordinal();
    }

    private static void inlineSize(Element element, String property, double size) {
        String value = Double.toString(size) + "px";
        if (!value.equals(element.getInlineStylePropertyValue(property))) element.setInlineStyleProperty(property, value);
    }

    private static void inlineProp(Element element, String property, String value) {
        String current = element.getInlineStylePropertyValue(property);
        if (!value.equals(current == null ? "" : current)) element.setInlineStyleProperty(property, value);
    }

    protected static void fitCaption(Element control, Element caption, String label, double fontSize) {
        if (caption == null || label.isEmpty()) return;
        Text actual = Text.of(caption);
        Text measuring = new Text();
        measuring.fontSize = fontSize;
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
        if (width <= 0) return;
        inlineSize(caption, "font-size", fontSize * (available > 0 ? Math.min(1.0, available / width) : 1.0));
    }

    public static void suspend() {
        if (screen == null) return;
        restoreBounds();
        clearElementCache();
        screen = null; widgets.clear(); slots.clear(); sentState = null; sentOverlay = null;
        widgetIds.clear(); dropdownIds.clear(); nextId = 0;
        pressedKey = null;
        tooltips.clear(); tooltipExtractor = null; renderedExtractor = null;
        publishSkinUpdate(SKIN_STATE_EVENT, null);
        publishSkinUpdate(SKIN_OVERLAY_EVENT, null);
    }

    protected static void closeState() {
        restoreBounds();
        visibleColorPicker = null;
        paletteCoveredLabels = false;
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
        sentOverlay = null;
        announcedVisibility.clear();
        nextId = 0;
        pointerDown = false;
        pressedKey = null;
        settingsSurface = null; settingsSurfaceGeneration = Long.MIN_VALUE;
        clearElementCache();
        tooltips.clear(); tooltipExtractor = null; renderedExtractor = null;
        widgetIds.clear();
        dropdownIds.clear();
        widgets.clear();
        slots.clear();
    }

    protected static void publishState() {
        JsonObject state = new JsonObject();
        JsonObject overlay = new JsonObject();
        state.addProperty("visible", !hidden());
        JsonArray controls = new JsonArray();
        double documentScale = document.getViewport().renderScale();
        if (screen instanceof GuiMap map) {
            if (NativeMapTerrain.showsNavigation(map)) {
                var remaining = NavigationTrace.distanceLabel(Minecraft.getInstance().level);
                if (remaining != null) overlay.addProperty("remainingDistance", remaining);
            }
            JsonObject ruler = MapRuler.presentation(map, documentScale);
            if (ruler != null) overlay.add("ruler", ruler);
            var labels = MapAnnotations.labels(map, documentScale);
            if (labels.size() != 0) overlay.add("marks", labels);
        }
        state.addProperty("settings", screen instanceof EditConfigScreen);
        overlay.addProperty("width", screen.width / documentScale);
        overlay.addProperty("height", screen.height / documentScale);
        for (WidgetView view : widgets) {
            AbstractWidget widget = view.widget();
            JsonObject control = new JsonObject();
            String controlKey = "widget-" + view.id();
            control.addProperty("key", controlKey);
            String translationKey = NativeMapSkin.translationKey(widget.getMessage());
            boolean viewButton = translationKey != null && translationKey.startsWith("xaeroearth.view.");
            boolean icon = hasNativeForeground(widget);
            control.addProperty("role", widget instanceof AbstractSliderButton ? "slider"
                    : icon ? "icon" : viewButton ? "view" : "button");
            if (widget instanceof AbstractSliderButton) {
                control.addProperty("value", ((AbstractSliderButtonAccess) widget).earth$getValue());
                control.addProperty("active", widget.active);
            }
            control.addProperty("label", icon ? "" : widget.getMessage().getString());
            control.addProperty("primary", "gui.done".equals(translationKey));
            control.addProperty("selected", viewButton && translationKey.equals(
                    "xaeroearth.view." + NativeMapInput.view().name().toLowerCase(Locale.ROOT)));
            control.addProperty("width", widget.getWidth() / documentScale);
            control.addProperty("height", widget.getHeight() / documentScale);
            control.addProperty("initiallyVisible", announcedVisibility.computeIfAbsent(controlKey, ignored -> widget.visible));
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

        long generation = document.getRefreshGeneration();
        if (generation != sentGeneration) {
            sentGeneration = generation;
            sentState = null;
            sentOverlay = null;
            announcedVisibility.clear();
        }
        if (!state.equals(sentState) && publishSkinUpdate(SKIN_STATE_EVENT, state.toString())) {
            sentState = state;
            clearElementCache();
        }
        if (!overlay.equals(sentOverlay) && publishSkinUpdate(SKIN_OVERLAY_EVENT, overlay.toString())) sentOverlay = overlay;
    }

    private static boolean publishSkinUpdate(String event, String state) {
        if (document == null || document.isDisposed() || document.body == null) return false;
        var update = new Event(document.body, event, false);
        update.detail = state;
        return Event.triggerSingle(update);
    }

    protected static boolean mountingControls() {
        var root = document.getElementById("xaeroearth-map-skin");
        return root != null && "true".equals(root.getAttribute("data-earth-mounting"));
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
