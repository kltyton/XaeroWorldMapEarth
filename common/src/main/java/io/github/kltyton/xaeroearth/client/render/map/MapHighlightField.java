package io.github.kltyton.xaeroearth.client.render.map;

import io.github.kltyton.xaeroearth.client.integration.xaero.highlight.PublishedHighlights;
import java.util.ArrayList;
import java.util.List;

/** A sparse chunk-to-color texture, independent of how far apart the highlighted chunks are. */
public record MapHighlightField(int width, int height, int mask, int probes, int[] pixels) {
    public record Area(int chunkX, int chunkZ, int argb) { }
    private record Samples(int x, int z, int[] argb) { }

    public static MapHighlightField build(List<Area> areas) {
        var samples = new ArrayList<Samples>(areas.size());
        for (Area area : areas) if (area.argb() >>> 24 != 0)
            samples.add(new Samples(area.chunkX(), area.chunkZ(), new int[]{area.argb()}));
        return texture(samples);
    }

    public static MapHighlightField buildPublished(List<PublishedHighlights.Chunk> chunks) {
        var samples = new ArrayList<Samples>(chunks.size());
        for (var chunk : chunks) samples.add(new Samples(chunk.x(), chunk.z(), chunk.argb()));
        return texture(samples);
    }

    private static MapHighlightField texture(List<Samples> chunks) {
        if (chunks.isEmpty()) return null;
        int slots = Integer.highestOneBit(chunks.size()) * 4;
        int columns = Math.min(256, slots), mask = slots - 1, width = columns * 3;
        int offset = slots * 3;
        int length = offset;
        for (var chunk : chunks) length += chunk.argb().length;
        int rows = (length + width - 1) / width;
        var pixels = new int[width * rows];
        int probes = 0;
        for (var chunk : chunks) {
            int slot = hash(chunk.x(), chunk.z()) & mask;
            int steps = 1;
            while (pixels[slot * 3 + 2] != 0 && (pixels[slot * 3] != chunk.x() || pixels[slot * 3 + 1] != chunk.z())) {
                slot = (slot + 1) & mask;
                steps++;
            }
            probes = Math.max(probes, steps);
            pixels[slot * 3] = chunk.x();
            pixels[slot * 3 + 1] = chunk.z();
            pixels[slot * 3 + 2] = chunk.argb().length == 256 ? -offset : offset;
            for (int tint : chunk.argb()) pixels[offset++] = swapRedBlue(tint);
        }
        return new MapHighlightField(width, rows, mask, probes, pixels);
    }

    public static int swapRedBlue(int color) {
        return (color & 0xff00ff00) | (color & 0xff) << 16 | (color >>> 16) & 0xff;
    }

    private static int hash(int x, int z) {
        int value = x * 0x9e3779b9 ^ z * 0x85ebca6b;
        value = (value ^ (value >>> 16)) * 0x7feb352d;
        return value ^ (value >>> 15);
    }
}
