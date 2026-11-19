package io.github.kltyton.xaeroearth.client.chunkmap.geometry;

import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.GpuMesh;

/** Transfers a decoded layer in bounded batches and yields ownership of its complete mesh. */
public interface GeometryUpload extends AutoCloseable {
    record Progress(int bytes, GpuMesh complete) { }

    Progress advance(int byteLimit);

    @Override void close();
}
