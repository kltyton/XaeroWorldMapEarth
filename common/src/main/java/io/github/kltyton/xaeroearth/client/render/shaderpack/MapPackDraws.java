package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.Draw;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry.Bounds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Selects pack draws without applying a fragment mask to geometry already confined to its visible chunks. */
public record MapPackDraws(List<Draw> terrain, List<Draw> solid, List<Draw> cutout, List<Draw> remaining) {
    public static Set<Draw> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    public static boolean contained(Bounds bounds, int mask) {
        if (bounds == null) return false;
        // KUI assigns the exact boundary to the positive half. Keep it in the conservative bounds.
        int xs = (bounds.minX() < 16 ? 1 : 0) | (bounds.maxX() >= 16 ? 2 : 0);
        int zs = (bounds.minZ() < 16 ? 1 : 0) | (bounds.maxZ() >= 16 ? 2 : 0);
        int covered = ((zs & 1) != 0 ? xs : 0) | ((zs & 2) != 0 ? xs << 2 : 0);
        return covered != 0 && (covered & ~mask) == 0;
    }

    public static MapPackDraws select(List<Draw> draws, Set<Draw> solids, Set<Draw> contained, boolean masks) {
        var terrain = new ArrayList<Draw>(draws.size());
        var solid = new ArrayList<Draw>();
        var cutout = new ArrayList<Draw>();
        var remaining = new ArrayList<Draw>();
        for (Draw original : draws) {
            Draw draw = original;
            if (!masks && draw.chunkMask() != 15) {
                if (!contained.contains(original)) {
                    remaining.add(original);
                    continue;
                }
                draw = new Draw(original.mesh(), original.modelView(), 15, original.skyLightFactor());
            }
            terrain.add(draw);
            if (draw.mesh().opaqueCount() > 0)
                (solids.contains(original) ? solid : cutout).add(draw);
        }
        return new MapPackDraws(terrain, solid, cutout, remaining);
    }

    MapPackDraws withShadowCasters(List<Draw> casters, Set<Draw> solids, Set<Draw> contained, boolean masks) {
        if (casters.isEmpty()) return this;
        var extra = select(casters, solids, contained, masks);
        return new MapPackDraws(join(terrain, extra.terrain), join(solid, extra.solid), join(cutout, extra.cutout), remaining);
    }

    private static List<Draw> join(List<Draw> visible, List<Draw> casters) {
        if (casters.isEmpty()) return visible;
        var combined = new ArrayList<Draw>(visible.size() + casters.size());
        combined.addAll(visible);
        combined.addAll(casters);
        return combined;
    }
}
