package io.github.kltyton.xaeroearth.client.chunkmap;

/** Distance to a real unmapped cell, computed in the background with the published map surface. */
public final class ExploredBoundary {
    private ExploredBoundary() {}

    public static byte[] distances(byte[] color, int width, int depth, int step) {
        int count = Math.multiplyExact(width, depth);
        var field = new byte[count];
        int scale = Math.max(1, step);
        for (int i = 0; i < count; i++) field[i] = color[4 * i + 3] == 0 ? 0 : (byte) 255;
        // Treat the padded viewport crop as open-ended, not as an exploration boundary.
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++)
            relax(field, width, depth, x, z, -1, scale);
        for (int z = depth - 1; z >= 0; z--) for (int x = width - 1; x >= 0; x--)
            relax(field, width, depth, x, z, 1, scale);
        return field;
    }

    private static void relax(byte[] field, int width, int depth, int x, int z, int side, int step) {
        int index = z * width + x, distance = Byte.toUnsignedInt(field[index]);
        if (distance == 0) return;
        if (x + side >= 0 && x + side < width)
            distance = Math.min(distance, Byte.toUnsignedInt(field[index + side]) + step);
        if (z + side >= 0 && z + side < depth) {
            int row = index + side * width;
            distance = Math.min(distance, Byte.toUnsignedInt(field[row]) + step);
            int corner = (step * 7 + 4) / 5;
            if (x > 0) distance = Math.min(distance, Byte.toUnsignedInt(field[row - 1]) + corner);
            if (x + 1 < width) distance = Math.min(distance, Byte.toUnsignedInt(field[row + 1]) + corner);
        }
        field[index] = (byte) distance;
    }
}
