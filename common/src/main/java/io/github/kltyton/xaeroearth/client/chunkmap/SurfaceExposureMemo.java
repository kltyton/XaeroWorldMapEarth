package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.xaeroearth.client.chunkmap.NativeChunkSource.ChunkCopy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Reuses the first exterior-air search while the same detached chunk snapshots remain available. */
final class SurfaceExposureMemo {
    private static final int CAPACITY = 256;
    private final LinkedHashMap<ChunkTiles.Tile, Result> recent = new LinkedHashMap<>(32, 0.75F, true);

    private record Result(List<WeakReference<ChunkCopy>> copies, Set<Integer> openings) {
        boolean matches(ChunkCopy[] current) {
            if (copies.size() != current.length) return false;
            for (int i = 0; i < current.length; i++) {
                var previous = copies.get(i);
                if (previous == null ? current[i] != null : current[i] == null || previous.get() != current[i])
                    return false;
            }
            return true;
        }
    }

    Set<Integer> openings(ChunkTiles.Tile tile, ChunkCopy[] copies, Supplier<Set<Integer>> search) {
        Result cached;
        synchronized (recent) { cached = recent.get(tile); }
        if (cached != null && cached.matches(copies)) return cached.openings();

        // The search is independent for every tile. Do not hold the LRU lock while it runs.
        Set<Integer> openings = Set.copyOf(search.get());
        var references = new ArrayList<WeakReference<ChunkCopy>>(copies.length);
        for (var copy : copies) references.add(copy == null ? null : new WeakReference<>(copy));
        synchronized (recent) {
            recent.put(tile, new Result(references, openings));
            if (recent.size() > CAPACITY) {
                var entries = recent.keySet().iterator();
                entries.next();
                entries.remove();
            }
        }
        return openings;
    }
}
