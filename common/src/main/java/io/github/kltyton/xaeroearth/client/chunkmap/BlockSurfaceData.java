package io.github.kltyton.xaeroearth.client.chunkmap;

import java.util.List;

/** Immutable CPU data and texture layout shared by block surface backends. */
public interface BlockSurfaceData {
    record Tile(int x, int z, int width, int depth, float[] heights, int[] ranges, int[] runs,
                float minY, float maxY) { }
    record Surface(long revision, int x, int z, int width, int depth, List<Tile> tiles, float[] palette) { }
    record Statistics(long revision, int uploaded, int total, long columns, int drawn, int geometryStep) { }
    int PALETTE_WIDTH = 30, RUN_WIDTH = 1024, HALO = 4;
}
