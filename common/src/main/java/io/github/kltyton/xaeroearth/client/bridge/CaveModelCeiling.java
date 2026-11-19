package io.github.kltyton.xaeroearth.client.bridge;

import xaero.common.HudMod;
import xaero.common.XaeroMinimapSession;
import xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions;

/** Keeps the expensive model cut stable across height changes inside an automatic Xaero cave layer. */
public final class CaveModelCeiling {
    private int automaticLayer = Integer.MAX_VALUE;
    private int automaticCeiling = Integer.MAX_VALUE;

    public int resolve(MapProcessor processor) {
        int layer = processor.getCurrentCaveLayer();
        if (layer == Integer.MAX_VALUE || layer == Integer.MIN_VALUE) {
            reset();
            return layer;
        }

        int start = processor.getMapWorld().getCurrentDimension().getLayeredMapRegions().getLayer(layer).getCaveStart();
        int configured = WorldMap.INSTANCE.getConfigs().getClientConfigManager().getPrimaryConfigManager()
                .getEffective(WorldMapPrimaryClientConfigOptions.CAVE_MODE_START);
        // Xaero treats MAX_VALUE as automatic and converts it to the MIN_VALUE request internally.
        boolean automatic = (configured == Integer.MAX_VALUE || configured == Integer.MIN_VALUE)
                && !fixedMinimapCut();
        if (!automatic || start == Integer.MAX_VALUE || start == Integer.MIN_VALUE) {
            reset();
            return start == Integer.MAX_VALUE || start == Integer.MIN_VALUE ? start : start + 1;
        }
        if (automaticLayer != layer) {
            automaticLayer = layer;
            automaticCeiling = start + 1;
        }
        return automaticCeiling;
    }

    public void reset() {
        automaticLayer = Integer.MAX_VALUE;
        automaticCeiling = Integer.MAX_VALUE;
    }

    private static boolean fixedMinimapCut() {
        var minimap = XaeroMinimapSession.getCurrentSession();
        return minimap != null && HudMod.INSTANCE.getSettings().getMinimap()
                && minimap.getMinimapProcessor().isManualCaveMode()
                && HudMod.INSTANCE.getHudConfigs().getClientConfigManager()
                        .getEffective(MinimapProfiledConfigOptions.MANUAL_CAVE_MODE_START) != Integer.MAX_VALUE;
    }
}
