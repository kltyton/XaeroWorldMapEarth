package io.github.kltyton.xaeroearth.client.render.map;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;

/** Scale and palette of the cartographic backdrop, shared across graphics backends. */
public record MapFogView(float aspect, float cave, float far) {
    public static MapFogView of(MapCamera camera, boolean cave, float far) {
        return new MapFogView((float) camera.width() / Math.max(1, camera.height()), cave ? 1 : 0, far);
    }
}
