package io.github.kltyton.xaeroearth.client.navigation;

import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/** Adds client-driven navigation alongside Xaero's native teleport action. */
public final class MapNavigationOption extends RightClickOption {
    private final GuiMap map;
    private final BlockPos destination;
    private final boolean automatic;

    private MapNavigationOption(GuiMap map, int index, BlockPos destination, boolean automatic) {
        super(automatic ? "xaeroearth.navigation.start" : "xaeroearth.navigation.guide", index, map);
        this.map = map;
        this.destination = destination;
        this.automatic = automatic;
    }

    public static void add(GuiMap map, List<RightClickOption> options) {
        if (!EarthClient.themed(map) || !currentWorld(map)) return;
        var coordinates = (GuiMapAccess) map;
        int x = coordinates.earth$rightClickX(), z = coordinates.earth$rightClickZ();
        int height = coordinates.earth$rightClickY();
        if (height == Short.MAX_VALUE || height == Integer.MAX_VALUE || height == Integer.MIN_VALUE) return;
        int index = options.size();
        for (int i = 0; i < options.size(); i++) {
            String key = NavigationPlatform.translationKey(options.get(i).getDisplayName());
            if (key.equals("gui.xaero_right_click_map_teleport")
                    || key.equals("gui.xaero_wm_right_click_map_teleport_not_allowed")
                    || key.equals("gui.xaero_right_click_map_cant_teleport")) {
                index = i + 1;
                break;
            }
        }
        var destination = new BlockPos(x, height + 1, z);
        options.add(index, new MapNavigationOption(map, index, destination, false));
        options.add(index + 1, new MapNavigationOption(map, index + 1, destination, true));
    }

    private static boolean currentWorld(GuiMap map) {
        var mc = Minecraft.getInstance();
        var access = (GuiMapAccess) map;
        var processor = map.getMapProcessor();
        return mc.level != null && processor.getWorld() == mc.level && processor.isMapWorldUsable()
                && processor.isCurrentMultiworldWritable() && !processor.isCurrentMapLocked()
                && mc.level.dimension().equals(access.earth$rightClickDimension())
                && mc.level.dimension().equals(processor.getMapWorld().getCurrentDimensionId());
    }

    @Override public void onAction(Screen screen) {
        if (EarthClient.themed(map) && currentWorld(map) && PlayerNavigation.start(destination, automatic)) {
            map.onClose();
        } else {
            PlayerNavigation.playerMessage("xaeroearth.navigation.unreachable");
        }
    }
}
