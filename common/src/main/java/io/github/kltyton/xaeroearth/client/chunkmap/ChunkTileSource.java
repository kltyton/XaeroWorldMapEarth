package io.github.kltyton.xaeroearth.client.chunkmap;

/** Read-only model source; tile data may be loaded on a worker while metadata stays nonblocking. */
public interface ChunkTileSource {
    int minY();
    int maxY();
    String contentKey(ChunkTiles.Tile tile);
    ChunkTiles.NativeTile tile(ChunkTiles.Tile tile);
    boolean persistentCache();
}
