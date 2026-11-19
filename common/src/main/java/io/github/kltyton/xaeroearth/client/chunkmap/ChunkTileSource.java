package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.AnimatedAtlas;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** Read-only model source; tile data may be loaded on a worker while metadata stays nonblocking. */
public interface ChunkTileSource {
    record Geometry(DetailedGeometry mesh, int mask) { }
    record ProjectionMetadata(DetailedGeometry.Bounds bounds, boolean animated, boolean translucent,
                              DetailedGeometry.Bounds animatedBounds) {
        public ProjectionMetadata(DetailedGeometry.Bounds bounds, boolean animated, boolean translucent) {
            this(bounds, animated, translucent, null);
        }
    }
    record Fragment(String contentKey, int mask) { }
    int minY();
    int maxY();
    String contentKey(ChunkTiles.Tile tile);
    /** Quadrants validated against the current observed inputs. */
    default int validatedMask(ChunkTiles.Tile tile) { return 15; }
    /** Additional non-overlapping versions used where the newest capture lacks a chunk. */
    default List<Fragment> fragments(ChunkTiles.Tile tile) { return List.of(); }
    default boolean contains(ChunkTiles.Tile tile, String key) {
        if (key.equals(contentKey(tile))) return true;
        for (var fragment : fragments(tile)) if (key.equals(fragment.contentKey())) return true;
        return false;
    }
    ChunkTiles.NativeTile tile(ChunkTiles.Tile tile);
    default Geometry geometry(ChunkTiles.Tile tile, String expectedKey) throws IOException {
        var data = tile(tile);
        if (data == null || !expectedKey.equals(contentKey(tile))) return null;
        return new Geometry(DetailedGeometry.decode(data.model()), NativeTileSession.availableMask(data.heights()));
    }
    boolean persistentCache();
    /** Null means texture animation cannot be classified for this source. */
    default AnimatedAtlas animatedAtlas() { return null; }
    /** Persistent projected images share the native model cache's world boundary. */
    default Path projectedCacheDirectory() { return null; }
    default String projectedResourceKey() { return null; }
    default ProjectionMetadata projectionMetadata(ChunkTiles.Tile tile, String key) { return null; }
    default int projectionMask(ChunkTiles.Tile tile, String key) { return 0; }
    /** Returns a biome from already detached source terrain without loading a chunk. */
    default Holder<Biome> cachedBiome(BlockPos position) { return null; }
    default void rememberProjectionMetadata(String key, ChunkTiles.Tile tile, ProjectionMetadata metadata) { }
}
