package io.github.kltyton.xaeroearth.client.chunkmap.biome;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;

/** Applies the world's biome zoom and client blend radius during detached KUI mesh baking. */
public final class BiomeTintScope implements AutoCloseable {
    private static final ThreadLocal<BiomeTintScope> ACTIVE = new ThreadLocal<>();
    private static final int MIN_PLANE_QUERIES = 8;
    private final BiomeTintSnapshot snapshot;
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private final Map<ColorResolver, Map<Integer, Plane>> planes = new IdentityHashMap<>();

    private BiomeTintScope(BiomeTintSnapshot snapshot) { this.snapshot = snapshot; }

    public static BiomeTintScope open(BiomeTintSnapshot snapshot) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested map biome tint scope");
        var scope = new BiomeTintScope(snapshot);
        ACTIVE.set(scope);
        return scope;
    }

    public static BiomeTintScope current() { return ACTIVE.get(); }

    public int color(BlockPos pos, ColorResolver resolver) {
        int radius = snapshot.radius();
        if (radius == 0) return sample(resolver, pos.getX(), pos.getY(), pos.getZ());
        int x = pos.getX(), z = pos.getZ(), y = pos.getY();
        int left = x - radius - snapshot.originX(), top = z - radius - snapshot.originZ();
        int right = x + radius - snapshot.originX(), bottom = z + radius - snapshot.originZ();
        boolean standard = resolver == BiomeColors.GRASS_COLOR_RESOLVER
                || resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER || resolver == BiomeColors.WATER_COLOR_RESOLVER;
        if (standard && left >= 0 && top >= 0 && right < snapshot.width() && bottom < snapshot.depth()) {
            var plane = planes.computeIfAbsent(resolver, ignored -> new HashMap<>())
                    .computeIfAbsent(y, ignored -> new Plane());
            int diameter = 2 * radius + 1;
            int threshold = Math.max(MIN_PLANE_QUERIES,
                    snapshot.width() * snapshot.depth() / (diameter * diameter));
            if (++plane.queries == threshold) plane.build(resolver, y);
            if (plane.red != null) return plane.color(left, top, right, bottom);
        }
        int red = 0, green = 0, blue = 0;
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            int color = sample(resolver, x + dx, y, z + dz);
            red += color >> 16 & 255; green += color >> 8 & 255; blue += color & 255;
        }
        int count = (2 * radius + 1) * (2 * radius + 1);
        return (red / count << 16) | (green / count << 8) | blue / count;
    }

    private int sample(ColorResolver resolver, int x, int y, int z) {
        return resolver.getColor(snapshot.biomes().getBiome(position.set(x, y, z)).value(), x, z);
    }

    private final class Plane {
        int queries;
        int[] red, green, blue;

        void build(ColorResolver resolver, int y) {
            int stride = snapshot.width() + 1, rows = snapshot.depth() + 1;
            red = new int[stride * rows]; green = new int[stride * rows]; blue = new int[stride * rows];
            for (int z = 0; z < snapshot.depth(); z++) {
                int rowR = 0, rowG = 0, rowB = 0;
                for (int x = 0; x < snapshot.width(); x++) {
                    int color = sample(resolver, snapshot.originX() + x, y, snapshot.originZ() + z);
                    rowR += color >> 16 & 255; rowG += color >> 8 & 255; rowB += color & 255;
                    int i = (z + 1) * stride + x + 1, previous = i - stride;
                    red[i] = red[previous] + rowR; green[i] = green[previous] + rowG; blue[i] = blue[previous] + rowB;
                }
            }
        }

        int color(int left, int top, int right, int bottom) {
            int stride = snapshot.width() + 1;
            int a = top * stride + left, b = top * stride + right + 1;
            int c = (bottom + 1) * stride + left, d = (bottom + 1) * stride + right + 1;
            int count = (right - left + 1) * (bottom - top + 1);
            return ((red[d] - red[b] - red[c] + red[a]) / count << 16)
                    | ((green[d] - green[b] - green[c] + green[a]) / count << 8)
                    | (blue[d] - blue[b] - blue[c] + blue[a]) / count;
        }
    }

    @Override public void close() {
        if (ACTIVE.get() != this) throw new IllegalStateException("Map biome tint scope ownership changed");
        ACTIVE.remove();
    }
}
