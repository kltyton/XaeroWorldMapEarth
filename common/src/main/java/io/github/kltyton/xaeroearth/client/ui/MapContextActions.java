package io.github.kltyton.xaeroearth.client.ui;

import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.mapmark.MapAnnotations;
import io.github.kltyton.xaeroearth.client.navigation.MapNavigationOption;
import io.github.kltyton.xaeroearth.client.ui.measurement.MapRuler;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/** Installs Earth actions alongside the unmodified native context options. */
public final class MapContextActions {
    public static void add(GuiMap map, List<RightClickOption> options) {
        if (!EarthClient.themed(map) || GuiMap.hiddenUI) return;
        MapNavigationOption.add(map, options);
        MapRuler.addOptions(map, options);
        MapAnnotations.addOptions(map, options);
        toggle(map, options, "quality", EarthPreferences.FAST_TERRAIN.get(), value -> EarthPreferences.FAST_TERRAIN.set(value));
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            toggle(map, options, "prefetch", EarthPreferences.BACKGROUND_WORLD_SAVE.get(),
                    value -> EarthPreferences.BACKGROUND_WORLD_SAVE.set(value));
        }
    }

    private static void toggle(GuiMap map, List<RightClickOption> options, String key, boolean current,
                               Consumer<Boolean> set) {
        options.add(new RightClickOption("xaeroearth.map." + key + (current ? ".on" : ".off"), options.size(), map) {
            @Override public void onAction(Screen screen) {
                if (!EarthClient.themed(map)) return;
                set.accept(!current);
                EarthPreferences.save();
            }
        });
    }

    private MapContextActions() { }
}
