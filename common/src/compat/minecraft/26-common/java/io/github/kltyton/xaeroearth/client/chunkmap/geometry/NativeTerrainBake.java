package io.github.kltyton.xaeroearth.client.chunkmap.geometry;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.xaeroearth.client.mixin.terrain.NativeTerrainMeshFactory;
import java.nio.ByteBuffer;

/** Uploads four vertices per face; the device assembles triangles via its reusable quad indices. */
public final class NativeTerrainBake {
    public static GeometryUpload prepare(DetailedGeometry geometry) { return new Transfer(geometry); }

    private static final class Transfer implements GeometryUpload {
        private final DetailedGeometry geometry;
        private final ByteBuffer source;
        private GpuBuffer buffer;
        private int cursor;

        Transfer(DetailedGeometry geometry) {
            this.geometry = geometry;
            source = geometry.compact() ? geometry.quads() : geometry.oldMesh().vertices();
        }

        @Override public Progress advance(int byteLimit) {
            int dataSize = source.remaining();
            int bytes = Math.min(byteLimit, dataSize - cursor);
            bytes -= bytes % DetailedGeometry.STRIDE;
            if (bytes != 0) {
                var device = RenderSystem.getDevice();
                if (buffer == null) buffer = device.createBuffer(() -> "Earth terrain faces",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, (long) dataSize);
                device.createCommandEncoder().writeToBuffer(buffer.slice(cursor, bytes), source.slice(cursor, bytes));
                cursor += bytes;
            }
            if (cursor < dataSize) return new Progress(bytes, null);
            int opaque = geometry.opaqueTrianglesSize();
            var mesh = NativeTerrainMeshFactory.earth$mesh(buffer, opaque, opaque, geometry.triangleVertices() - opaque,
                    dataSize, geometry.minY(), geometry.maxY());
            if (geometry.compact()) ((IndexedDetailedMesh) (Object) mesh).earth$enableIndices();
            buffer = null;
            return new Progress(bytes, mesh);
        }

        @Override public void close() {
            if (buffer != null) { buffer.close(); buffer = null; }
        }
    }

    private NativeTerrainBake() {}
}
