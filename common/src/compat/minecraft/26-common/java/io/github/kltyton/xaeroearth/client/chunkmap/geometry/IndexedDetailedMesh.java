package io.github.kltyton.xaeroearth.client.chunkmap.geometry;

/** Distinguishes a compact Earth buffer from KUI's regular triangle buffers. */
public interface IndexedDetailedMesh {
    boolean earth$indexed();
    void earth$enableIndices();
}
