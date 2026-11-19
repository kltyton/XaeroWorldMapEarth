package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.Draw;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Draws resident opaque meshes only where their projected images have no coverage. */
public record ProjectedDrawCoverage(Map<Draw, List<Clip>> clipped) {
    public static final ProjectedDrawCoverage NONE = new ProjectedDrawCoverage(Map.of());
    private static final ThreadLocal<Clip> ACTIVE = new ThreadLocal<>();

    /** Framebuffer coordinates in the render device's lower-left origin. */
    public record Clip(int x, int y, int width, int height) { }

    public static Clip currentClip() { return ACTIVE.get(); }

    public void draw(List<Draw> visible, Consumer<List<Draw>> submit) {
        if (clipped.isEmpty()) {
            if (!visible.isEmpty()) submit.accept(visible);
            return;
        }
        var full = new ArrayList<Draw>();
        var cells = new LinkedHashMap<Clip, List<Draw>>();
        for (Draw draw : visible) {
            var clips = clipped.get(draw);
            if (clips == null) full.add(draw);
            else for (Clip clip : clips) cells.computeIfAbsent(clip, ignored -> new ArrayList<>()).add(draw);
        }
        if (!full.isEmpty()) submit.accept(full);
        var old = ACTIVE.get();
        try {
            for (var cell : cells.entrySet()) {
                ACTIVE.set(cell.getKey());
                submit.accept(cell.getValue());
            }
        } finally {
            if (old == null) ACTIVE.remove();
            else ACTIVE.set(old);
        }
    }
}
