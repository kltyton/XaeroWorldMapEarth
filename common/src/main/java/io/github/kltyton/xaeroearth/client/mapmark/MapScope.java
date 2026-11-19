package io.github.kltyton.xaeroearth.client.mapmark;

import xaero.map.MapProcessor;

/** Identifies a Xaero map, including separately selected server subworlds and cave layers. */
public record MapScope(String world, String dimension, String subworld, int layer) {
    public static MapScope selected(MapProcessor processor) {
        if (processor == null) return null;
        synchronized (processor.renderThreadPauseSync) {
            if (!processor.isMapWorldUsable() || processor.isCurrentMapLocked()
                    || processor.isRenderingPaused() || processor.isWaitingForWorldUpdate()) return null;
            String world = processor.getCurrentWorldId(), dimension = processor.getCurrentDimId();
            String subworld = processor.getCurrentMWId();
            return world == null || dimension == null || subworld == null ? null
                    : new MapScope(world, dimension, subworld, processor.getCurrentCaveLayer());
        }
    }
}
