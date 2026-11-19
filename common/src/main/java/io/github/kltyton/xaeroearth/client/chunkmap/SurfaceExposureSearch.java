package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.xaeroearth.client.chunkmap.NativeChunkSource.ChunkCopy;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Finds the lower capture boundaries reachable from the sky through non-occluding blocks. */
final class SurfaceExposureSearch {
    private static final int UNKNOWN = Integer.MAX_VALUE;
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private static final class Workspace {
        final BitSet visited = new BitSet();
        int[] skyFloor = new int[0];
        int[] queue = new int[1024];
    }

    /** Sky-reachable air mask for a completed surface window; safe to query on the same worker thread. */
    static final class Visibility {
        private final int width, plane, minY, height;
        private final int[] skyFloor;
        private final BitSet visited;

        private Visibility(int width, int plane, int minY, int height, int[] skyFloor, BitSet visited) {
            this.width = width; this.plane = plane; this.minY = minY; this.height = height;
            this.skyFloor = skyFloor; this.visited = visited;
        }

        boolean isSkyReachable(int x, int blockY, int z) {
            int y = blockY - minY;
            if (y < 0) return false;
            int column = z * width + x;
            int sky = skyFloor[column];
            if (sky == UNKNOWN || y >= sky) return true;
            if (y >= height) return true;
            return visited.get(y * plane + column);
        }
    }

    static Visibility visibility(ChunkCopy[] copies, int chunksWide, int chunksDeep,
                                 int minY, int maxY, BooleanSupplier cancelled) {
        int bottom = maxY, top = minY;
        for (var copy : copies) {
            if (copy == null) continue;
            bottom = Math.min(bottom, copy.lowestBlock());
            top = Math.max(top, copy.topExclusive());
        }
        if (top <= bottom) return null;
        int low = Math.max(minY, bottom - 1), high = Math.min(maxY, top);
        if (high <= low) return null;
        var engine = new SurfaceExposureSearch(copies, chunksWide, chunksDeep, low, high, cancelled);
        engine.search();
        return new Visibility(engine.width, engine.plane, engine.minY, engine.height,
                engine.workspace.skyFloor, engine.workspace.visited);
    }

    static Set<Integer> find(ChunkCopy[] copies, int chunksWide, int chunksDeep,
                             int minY, int maxY, BooleanSupplier cancelled) {
        int bottom = maxY, top = minY;
        for (var copy : copies) {
            if (copy == null) continue;
            bottom = Math.min(bottom, copy.lowestBlock());
            top = Math.max(top, copy.topExclusive());
        }
        if (top <= bottom) return Set.of();
        int low = Math.max(minY, bottom - 1), high = Math.min(maxY, top);
        if (high <= low) return Set.of();
        return new SurfaceExposureSearch(copies, chunksWide, chunksDeep, low, high, cancelled).search();
    }

    private final ChunkCopy[] copies;
    private final int chunksWide, width, depth, plane, minY, height;
    private final BooleanSupplier cancelled;
    private final Workspace workspace = WORKSPACE.get();
    private final boolean[] opened;
    private boolean anyOpened;
    private int tail;

    private SurfaceExposureSearch(ChunkCopy[] copies, int chunksWide, int chunksDeep,
                                  int minY, int maxY, BooleanSupplier cancelled) {
        this.copies = copies;
        this.chunksWide = chunksWide;
        this.width = chunksWide * 16;
        this.depth = chunksDeep * 16;
        this.plane = width * depth;
        this.minY = minY;
        this.height = maxY - minY;
        this.cancelled = cancelled;
        this.opened = new boolean[copies.length];
        int totalCells = Math.multiplyExact(plane, height);
        if (workspace.skyFloor.length < plane) workspace.skyFloor = new int[plane];
        workspace.visited.clear(0, Math.min(workspace.visited.length(), totalCells));
    }

    private Set<Integer> search() {
        // Air directly above the highest occluder in each column already reaches
        // the sky. Only the horizontal edges of these columns can open a path
        // into shaded space. Keep those paths in the flood queue.
        int top = minY + height - 1;
        for (int z = 0; z < depth; z++) {
            checkCancelled();
            for (int x = 0; x < width; x++) {
                int chunk = chunk(x, z), column = z * width + x;
                var copy = copies[chunk];
                if (copy == null) {
                    workspace.skyFloor[column] = UNKNOWN;
                    continue;
                }
                int bottom = Math.max(minY, copy.lowestBlock());
                int y = highestOccluder(copy, x, z, bottom, Math.min(top, copy.topExclusive() - 1));
                workspace.skyFloor[column] = y + 1 - minY;
                if (y < bottom && bottom > minY) {
                    opened[chunk] = true;
                    anyOpened = true;
                }
            }
        }
        for (int z = 0; z < depth; z++) {
            checkCancelled();
            for (int x = 0; x < width; x++) {
                if (x + 1 < width) seed(x, z, x + 1, z);
                if (z + 1 < depth) seed(x, z, x, z + 1);
            }
        }
        for (int head = 0; head < tail; head++) {
            if ((head & 4095) == 0) checkCancelled();
            int cell = workspace.queue[head], y = cell / plane;
            int horizontal = cell % plane, x = horizontal % width, z = horizontal / width;
            if (x > 0) visit(x - 1, y, z);
            if (x + 1 < width) visit(x + 1, y, z);
            if (z > 0) visit(x, y, z - 1);
            if (z + 1 < depth) visit(x, y, z + 1);
            if (y > 0) visit(x, y - 1, z);
            if (y + 1 < height) visit(x, y + 1, z);
        }
        if (!anyOpened) return Set.of();
        var openings = new HashSet<Integer>();
        for (int i = 0; i < opened.length; i++) if (opened[i]) openings.add(i);
        return openings;
    }

    private void seed(int x, int z, int adjacentX, int adjacentZ) {
        int floor = workspace.skyFloor[z * width + x], adjacent = workspace.skyFloor[adjacentZ * width + adjacentX];
        if (floor == UNKNOWN || adjacent == UNKNOWN) return;
        for (int y = floor; y < adjacent; y++) visit(adjacentX, y, adjacentZ);
        for (int y = adjacent; y < floor; y++) visit(x, y, z);
    }

    private void visit(int x, int y, int z) {
        int column = z * width + x, sky = workspace.skyFloor[column];
        if (sky == UNKNOWN || y >= sky) return;
        int cell = y * plane + column;
        if (workspace.visited.get(cell)) return;
        workspace.visited.set(cell);
        int chunk = chunk(x, z), blockY = minY + y;
        var copy = copies[chunk];
        if (blockY < copy.lowestBlock()) {
            opened[chunk] = true;
            anyOpened = true;
            return;
        }
        if (occludes(copy, x, blockY, z)) return;
        if (tail == workspace.queue.length) workspace.queue = Arrays.copyOf(workspace.queue, tail * 2);
        workspace.queue[tail++] = cell;
    }

    private static boolean occludes(ChunkCopy copy, int x, int y, int z) {
        var section = copy.section(y);
        return section != null && section.occludes(x & 15, y & 15, z & 15);
    }

    private static int highestOccluder(ChunkCopy copy, int x, int z, int bottom, int top) {
        for (int y = top; y >= bottom; y = (y & ~15) - 1) {
            int sectionBottom = y & ~15;
            var section = copy.section(y);
            if (section == null) continue;
            int local = section.highestOccluder(x & 15, z & 15, Math.max(0, bottom - sectionBottom), y & 15);
            if (local >= 0) return sectionBottom + local;
        }
        return bottom - 1;
    }

    private int chunk(int x, int z) { return (z >> 4) * chunksWide + (x >> 4); }

    private void checkCancelled() {
        if (cancelled.getAsBoolean()) throw new CancellationException();
    }
}
