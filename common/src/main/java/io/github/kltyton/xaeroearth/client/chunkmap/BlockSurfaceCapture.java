package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

/** Encodes detached columns while retaining material identity, tint and transparency. */
abstract class BlockSurfaceCapture<P> implements BlockSurfaceData {
    protected static final Direction[] FACES = {Direction.UP, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
    private final Map<MaterialKey<P>, Integer> materialIds = new HashMap<>();
    private final List<Material> materials = new ArrayList<>();
    BlockSurfaceCapture() {
        materials.add(new Material(new float[PALETTE_WIDTH * 4], 1, false));
    }
    public synchronized float[] palette() {
        float[] result = new float[materials.size() * PALETTE_WIDTH * 4];
        for (int i = 0; i < materials.size(); i++) System.arraycopy(materials.get(i).data, 0, result, i * PALETTE_WIDTH * 4, PALETTE_WIDTH * 4);
        return result;
    }
    public Tile encode(ChunkMapSnapshot snapshot, int x, int z, int width, int depth, BooleanSupplier cancelled) {
        if (snapshot.step() != 1 || snapshot.verticalStep() != 1) throw new IllegalArgumentException("Block surface input must resolve individual blocks");
        int cells = (width + HALO * 2) * (depth + HALO * 2);
        float[] heights = new float[cells * 4]; int[] ranges = new int[cells * 2];
        var runs = new Ints(); float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int dz = -HALO; dz < depth + HALO; dz++) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            for (int dx = -HALO; dx < width + HALO; dx++) {
                int sx = x + dx - snapshot.originX(), sz = z + dz - snapshot.originZ();
                if (sx < 0 || sz < 0 || sx >= snapshot.width() || sz >= snapshot.depth()) throw new IllegalArgumentException("Missing native column halo");
                int index = (dz + HALO) * (width + HALO * 2) + dx + HALO;
                var column = snapshot.column(sx, sz); var tint = snapshot.biome(sx, sz);
                int visibleBottom = snapshot.minY();
                if (sx > 0 && sz > 0 && sx + 1 < snapshot.width() && sz + 1 < snapshot.depth()) {
                    visibleBottom = Math.min(solidTop(snapshot, sx, sz), Math.min(Math.min(solidTop(snapshot, sx - 1, sz), solidTop(snapshot, sx + 1, sz)),
                            Math.min(solidTop(snapshot, sx, sz - 1), solidTop(snapshot, sx, sz + 1)))) - 1;
                }
                heights[index * 4] = snapshot.minY();
                heights[index * 4 + 1] = heights[index * 4 + 2] = heights[index * 4 + 3] = -8192;
                ranges[index * 2] = runs.size / 4;
                for (var run : column) {
                    if (run.toY() <= visibleBottom) continue;
                    for (int y = Math.max(run.fromY(), visibleBottom); y < run.toY(); y++) {
                        boolean fluid = !run.state().getFluidState().isEmpty();
                        List<P> parts = parts(run.state(), x + dx, y, z + dz);
                        int color = tint == null ? 0xffffff : fluid ? tint.water() : tint.grass();
                        MaterialKey<P> key = new MaterialKey<>(run.state(), parts, color);
                        int id; Material material;
                        synchronized (this) {
                            Integer known = materialIds.get(key);
                            if (known == null) { material = material(key.state(), key.parts(), key.color()); id = materials.size(); materials.add(material); materialIds.put(key, id); }
                            else { id = known; material = materials.get(id); }
                        }
                        int flags = material.transparent ? 1 : 0;
                        boolean merged = ranges[index * 2 + 1] > 0 && runs.data[runs.size - 3] == y + 32768
                                && runs.data[runs.size - 2] == id && runs.data[runs.size - 1] == flags;
                        if (merged) runs.data[runs.size - 3] = y + 1 + 32768;
                        else { runs.add(y + 32768, y + 1 + 32768, id, flags); ranges[index * 2 + 1]++; }
                        minY = Math.min(minY, y);
                    }
                }
                int first = ranges[index * 2], count = ranges[index * 2 + 1];
                for (int r = 0; r < count; r++) {
                    int at = (first + r) * 4; Material material;
                    synchronized (this) { material = materials.get(runs.data[at + 2]); }
                    float top = runs.data[at + 1] - 32769 + material.height;
                    boolean exposed = r + 1 == count || runs.data[at + 1] != runs.data[at + 4]
                            || runs.data[at + 3] != runs.data[at + 7]
                            || runs.data[at + 3] == 1 && runs.data[at + 2] != runs.data[at + 6];
                    if (runs.data[at + 3] == 0) {
                        if (exposed) { heights[index * 4 + 3] = heights[index * 4]; heights[index * 4] = top; }
                    } else if (exposed) { heights[index * 4 + 2] = heights[index * 4 + 1]; heights[index * 4 + 1] = top; }
                    maxY = Math.max(maxY, top);
                }
            }
        }
        return new Tile(x, z, width, depth, heights, ranges, runs.array(), minY, maxY);
    }
    private int solidTop(ChunkMapSnapshot snapshot, int x, int z) {
        var column = snapshot.column(x, z);
        for (int i = column.size() - 1; i >= 0; i--) {
            var run = column.get(i);
            if (run.state().getFluidState().isEmpty() && !translucent(run.state())) return run.toY();
        }
        return snapshot.minY();
    }
    protected abstract List<P> parts(BlockState state, int x, int y, int z);
    protected abstract boolean translucent(BlockState state);
    protected abstract Material material(BlockState state, List<P> parts, int color);
    protected record Material(float[] data, float height, boolean transparent) { }
    private record MaterialKey<P>(BlockState state, List<P> parts, int color) { }
    private static final class Ints {
        int[] data = new int[16384]; int size;
        void add(int a, int b, int c, int d) { if (size + 4 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2); data[size++] = a; data[size++] = b; data[size++] = c; data[size++] = d; }
        int[] array() { return java.util.Arrays.copyOf(data, size); }
    }
}
