package io.github.kltyton.xaeroearth.client.integration.xaero.highlight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Copies native highlight pixels during Xaero's own texture publication. */
public final class PublishedHighlights implements AutoCloseable {
    public interface Texture {
        List<Chunk> earth$publishedHighlights();
    }

    public record Chunk(int x, int z, int[] argb) { }

    private static final ThreadLocal<PublishedHighlights> CURRENT = new ThreadLocal<>();
    private final PublishedHighlights previous = CURRENT.get();
    private Map<Long, Chunk> chunks;

    public PublishedHighlights() { CURRENT.set(this); }

    /** Xaero stores alpha in the low byte of each RGB color. Its scratch array remains upstream-owned. */
    public static void accept(int x, int z, int[] rgba) {
        var publication = CURRENT.get();
        if (publication == null || rgba == null || rgba.length != 256) return;
        if (publication.chunks == null) publication.chunks = new HashMap<>();
        long key = (long) x << 32 | (z & 0xffffffffL);
        var pixels = publication.chunks.computeIfAbsent(key, ignored -> new Chunk(x, z, new int[256])).argb();
        for (int i = 0; i < pixels.length; i++) {
            int color = rgba[i];
            if ((color & 255) != 0) pixels[i] = over(Integer.rotateRight(color, 8), pixels[i]);
        }
    }

    public List<Chunk> snapshot() {
        if (chunks == null) return List.of();
        var result = new ArrayList<Chunk>(chunks.size());
        for (var chunk : chunks.values()) {
            for (int pixel : chunk.argb()) if (pixel >>> 24 != 0) {
                result.add(chunk);
                break;
            }
        }
        return List.copyOf(result);
    }

    private static int over(int top, int below) {
        int topAlpha = top >>> 24, belowAlpha = below >>> 24;
        if (topAlpha == 255 || belowAlpha == 0) return top;
        float base = belowAlpha * (255 - topAlpha) / 255.0F;
        float mixed = topAlpha + base;
        int red = channel(top, below, 16, topAlpha, base, mixed);
        int green = channel(top, below, 8, topAlpha, base, mixed);
        int blue = channel(top, below, 0, topAlpha, base, mixed);
        return Math.round(mixed) << 24 | red << 16 | green << 8 | blue;
    }

    private static int channel(int top, int below, int shift, int topAlpha, float base, float mixed) {
        return Math.round(((top >>> shift & 255) * topAlpha + (below >>> shift & 255) * base) / mixed);
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
