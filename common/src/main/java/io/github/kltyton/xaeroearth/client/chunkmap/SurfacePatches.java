package io.github.kltyton.xaeroearth.client.chunkmap;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/** Combines planar cells in coarse terrain without changing the heightfield at their borders. */
public final class SurfacePatches {
    public static int[] build(int width, int depth, int step, int[] heights, byte[] sourceSteps) {
        var patches = new IntArrayList();
        var occupied = new boolean[width * depth];
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            int root = z * width + x;
            if (occupied[root] || sourceSteps[root] == 0) continue;
            int span = 1;
            if (sourceSteps[root] > 1) {
                for (int size = 2; size <= 16 && x % size == 0 && z % size == 0; size *= 2) {
                    if (!planar(x, z, size, width, depth, step, heights, sourceSteps)) break;
                    span = size;
                }
            }
            for (int dz = 0; dz < span; dz++)
                java.util.Arrays.fill(occupied, root + dz * width, root + dz * width + span, true);
            // The highest texel index is 1024 * 1024 - 1. Four bits still fit exactly in an R32F texel.
            patches.add((root << 4) | Integer.numberOfTrailingZeros(span));
        }
        return patches.toIntArray();
    }

    private static boolean planar(int x, int z, int size, int width, int depth, int step,
                                  int[] heights, byte[] sourceSteps) {
        if (x == 0 || z == 0 || x + size >= width || z + size >= depth) return false;
        int start = z * width + x;
        int height = heights[start], dx = heights[start + 1] - height, dz = heights[start + width] - height;
        // The shader averages adjacent cells at the corners. Include that one-cell ring
        // so a large patch shares the same edge as every smaller neighbour.
        for (int row = -1; row <= size; row++) for (int col = -1; col <= size; col++) {
            int sample = start + row * width + col;
            if (sourceSteps[sample] == 0 || heights[sample] != height + dx * col + dz * row) return false;
            if (col >= 0 && row >= 0 && col < size && row < size && sourceSteps[sample] < Math.max(2, step))
                return false;
        }
        return true;
    }

    private SurfacePatches() {}
}
