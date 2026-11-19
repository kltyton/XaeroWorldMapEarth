package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Retains independent compiled passes, render targets and temporal history for both map views. */
public final class MapShaderPasses<T> {
    private final Supplier<T> create;
    private final Consumer<T> release;
    private T fullscreen, hud;
    private int owners;

    public MapShaderPasses(Supplier<T> create, Consumer<T> release) {
        this.create = create;
        this.release = release;
    }

    public void retain() { owners++; }

    public T forMap(MapCamera camera) {
        if (camera.minimap()) {
            if (hud == null) hud = create.get();
            return hud;
        }
        if (fullscreen == null) fullscreen = create.get();
        return fullscreen;
    }

    public void release() {
        if (--owners != 0) return;
        if (fullscreen != null) release.accept(fullscreen);
        if (hud != null) release.accept(hud);
        fullscreen = hud = null;
    }
}
