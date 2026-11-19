package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources.SpriteLayout;
import java.util.List;

/** Conservative atlas-space lookup for separating animated faces from reusable terrain. */
public final class AnimatedAtlas {
    private static final int CELLS = 512, STRIDE = CELLS + 1;
    private final int[] summed = new int[STRIDE * STRIDE];

    public AnimatedAtlas(List<SpriteLayout> sprites) {
        boolean[] animated = new boolean[CELLS * CELLS];
        for (var sprite : sprites) {
            if (!sprite.animated()) continue;
            int x0 = low(sprite.u0()), y0 = low(sprite.v0());
            int x1 = high(sprite.u1()), y1 = high(sprite.v1());
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) animated[y * CELLS + x] = true;
        }
        for (int y = 0; y < CELLS; y++) for (int x = 0; x < CELLS; x++) {
            int position = (y + 1) * STRIDE + x + 1;
            summed[position] = (animated[y * CELLS + x] ? 1 : 0) + summed[position - 1]
                    + summed[position - STRIDE] - summed[position - STRIDE - 1];
        }
    }

    public boolean overlaps(float minU, float minV, float maxU, float maxV) {
        if (!Float.isFinite(minU) || !Float.isFinite(minV) || !Float.isFinite(maxU) || !Float.isFinite(maxV)
                || minU < 0 || minV < 0 || maxU > 1 || maxV > 1 || minU > maxU || minV > maxV) return true;
        int x0 = low(minU), y0 = low(minV);
        int x1 = Math.max(x0, high(maxU)) + 1, y1 = Math.max(y0, high(maxV)) + 1;
        return summed[y1 * STRIDE + x1] - summed[y0 * STRIDE + x1]
                - summed[y1 * STRIDE + x0] + summed[y0 * STRIDE + x0] != 0;
    }

    public boolean isEmpty() { return summed[summed.length - 1] == 0; }

    private static int low(float coordinate) { return Math.max(0, Math.min(CELLS - 1, (int) Math.floor(coordinate * CELLS))); }
    private static int high(float coordinate) { return Math.max(0, Math.min(CELLS - 1, (int) Math.floor(coordinate * CELLS))); }
}
