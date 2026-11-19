package io.github.kltyton.xaeroearth.client.chunkmap.geometry;

import io.github.kltyton.kltytonui.chunkmap.KuiChunkMeshBaker;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeMesh;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.AnimatedAtlas;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import org.lwjgl.system.MemoryUtil;

/** Camera-independent material faces. Also supports standard KUI triangle packets. */
public final class DetailedGeometry implements AutoCloseable {
    private static final int QUADS = 0x51445245, KUI = 0x4d495541, HEADER = 32;
    private static final int[] INDICES = {0, 1, 2, 2, 3, 0};
    public static final int STRIDE = KuiNativeMesh.STRIDE;
    private static final int SKY_AND_CUTOUT = 26, MATERIAL_BYTE = 31;
    private static final int MIPPED_CUTOUT = 128, LEAF_CUTOUT = 26;
    private final KuiNativeMesh old;
    private final ByteBuffer quads;
    private final ByteBuffer allocation;
    private final int vertices, opaque;
    private final float minY, maxY;
    private volatile Bounds bounds;
    private boolean closed;

    private DetailedGeometry(KuiNativeMesh old, ByteBuffer quads, int vertices, int opaque, float minY, float maxY) {
        this(old, quads, quads, vertices, opaque, minY, maxY);
    }

    private DetailedGeometry(KuiNativeMesh old, ByteBuffer quads, ByteBuffer allocation,
                             int vertices, int opaque, float minY, float maxY) {
        this.old = old; this.quads = quads; this.vertices = vertices; this.opaque = opaque;
        this.allocation = allocation;
        this.minY = minY; this.maxY = maxY;
    }

    public static byte[] encode(KuiChunkMeshBaker.Mesh faces) {
        ByteBuffer input = faces.vertices();
        ByteBuffer out = ByteBuffer.allocate(HEADER + input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        header(out, QUADS, faces.vertexCount(), faces.opaque().count(), faces.minY(), faces.maxY());
        return out.put(input).array();
    }

    public static DetailedGeometry decode(byte[] data) throws IOException {
        return decode(ByteBuffer.wrap(data));
    }

    /** Reads a packet without modifying its position or backing bytes. */
    public static DetailedGeometry decode(ByteBuffer data) throws IOException {
        var input = data.slice().order(ByteOrder.LITTLE_ENDIAN);
        if (input.remaining() < HEADER) throw new IOException("Truncated Earth terrain geometry");
        if (input.getInt() != QUADS) {
            input.position(0);
            byte[] encoded = new byte[input.remaining()];
            input.get(encoded);
            var old = KuiNativeMesh.decode(encoded);
            return new DetailedGeometry(old, null, old.vertexCount(), old.opaque().count(), old.minY(), old.maxY());
        }
        var header = readCompactHeader(input);
        var quads = MemoryUtil.memAlloc(Math.max(1, input.remaining())).order(ByteOrder.nativeOrder());
        try {
            quads.put(input).flip();
            preserveLeafCoverage(quads, header.opaque());
            return new DetailedGeometry(null, quads, header.vertices(), header.opaque(), header.minY(), header.maxY());
        } catch (RuntimeException | Error failure) {
            MemoryUtil.memFree(quads);
            throw failure;
        }
    }

    /** Takes ownership of a complete, position-zero memAlloc packet only after successful decoding. */
    public static DetailedGeometry decodeOwned(ByteBuffer data) throws IOException {
        if (!data.isDirect() || data.position() != 0)
            throw new IllegalArgumentException("Owned geometry requires a position-zero native allocation");
        var input = data.slice().order(ByteOrder.LITTLE_ENDIAN);
        if (input.remaining() < HEADER) throw new IOException("Truncated Earth terrain geometry");
        if (input.getInt() != QUADS) {
            var decoded = decode(data);
            MemoryUtil.memFree(data);
            return decoded;
        }
        var header = readCompactHeader(input);
        var quads = input.slice().order(ByteOrder.nativeOrder());
        preserveLeafCoverage(quads, header.opaque());
        return new DetailedGeometry(null, quads, data, header.vertices(), header.opaque(), header.minY(), header.maxY());
    }

    private record Header(int vertices, int opaque, float minY, float maxY) { }

    private static Header readCompactHeader(ByteBuffer input) throws IOException {
        if (input.getInt() != 1) throw new IOException("Unsupported Earth face format");
        int vertices = input.getInt(), opaque = input.getInt();
        float minY = input.getFloat(), maxY = input.getFloat();
        if (input.getInt() != STRIDE || input.getInt() != 0 || vertices < 0 || vertices % 4 != 0
                || opaque < 0 || opaque > vertices || opaque % 4 != 0 || input.remaining() != (long) vertices * STRIDE
                || !Float.isFinite(minY) || !Float.isFinite(maxY) || minY > maxY)
            throw new IOException("Invalid Earth face ranges");
        return new Header(vertices, opaque, minY, maxY);
    }

    private static void preserveLeafCoverage(ByteBuffer faces, int opaqueVertices) {
        for (int face = 0; face < opaqueVertices * STRIDE; face += 4 * STRIDE) {
            int first = Short.toUnsignedInt(faces.getShort(face + SKY_AND_CUTOUT));
            int cutoff = (first >>> 4) & 255;
            if (cutoff <= LEAF_CUTOUT) continue;
            int material = 0;
            for (int corner = 0; corner < 4; corner++) {
                int part = Byte.toUnsignedInt(faces.get(face + corner * STRIDE + MATERIAL_BYTE));
                material |= part << (corner * Byte.SIZE);
            }
            if (material == 0 || (material & 1) != 0) continue;
            var state = Block.stateById((material >>> 1) - 1);
            if (!(state.getBlock() instanceof LeavesBlock) && !state.is(BlockTags.LEAVES)) continue;
            // Sparse leaf masks can fall entirely below 50% alpha at map-scale mip levels.
            // Reuse KUI's cutout path and preserve both the live and original sky light.
            for (int corner = 0; corner < 4; corner++) {
                int offset = face + corner * STRIDE + SKY_AND_CUTOUT;
                int light = Short.toUnsignedInt(faces.getShort(offset));
                faces.putShort(offset, (short) ((light & 0xf00f) | (LEAF_CUTOUT << 4)));
            }
        }
    }

    public boolean compact() { return quads != null; }
    public long bytes() { return (long) vertices * STRIDE; }
    public int triangleVertices() { return compact() ? vertices / 4 * 6 : vertices; }
    public int opaqueTrianglesSize() { return compact() ? opaque / 4 * 6 : opaque; }
    public float minY() { return minY; }
    public float maxY() { return maxY; }
    public ByteBuffer quads() { return quads.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
    public KuiNativeMesh oldMesh() { return old; }

    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) { }

    /** Actual face bounds in tile-local X/Z and world Y, including translucent geometry. */
    public Bounds bounds() {
        var found = bounds;
        if (found != null) return found;
        if (vertices == 0) return bounds = new Bounds(0, 0, 0, 0, 0, 0);
        ByteBuffer data = compact() ? quads() : old.vertices();
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < vertices; vertex++) {
            int offset = vertex * STRIDE;
            float x = data.getFloat(offset), y = data.getFloat(offset + 4), z = data.getFloat(offset + 8);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                throw new IllegalArgumentException("Non-finite Earth terrain vertex");
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        return bounds = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Classifies opaque faces by the texture regions they sample; translucent faces remain live. */
    public BitSet animatedOpaqueFaces(AnimatedAtlas atlas) {
        return animatedOpaqueFaces(atlas, false);
    }

    public boolean hasAnimatedOpaqueFaces(AnimatedAtlas atlas) {
        return !animatedOpaqueFaces(atlas, true).isEmpty();
    }

    /** Bounds of animated opaque faces in tile-local X/Z and world Y, or null when there are none. */
    public Bounds animatedOpaqueBounds(AnimatedAtlas atlas) {
        return animatedOpaqueBounds(animatedOpaqueFaces(atlas));
    }

    public Bounds animatedOpaqueBounds(BitSet animated) {
        if (animated.isEmpty()) return null;
        int corners = compact() ? 4 : 3;
        ByteBuffer data = compact() ? quads() : old.vertices();
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int face = animated.nextSetBit(0); face >= 0; face = animated.nextSetBit(face + 1)) {
            for (int corner = 0; corner < corners; corner++) {
                int offset = (face * corners + corner) * STRIDE;
                float x = data.getFloat(offset), y = data.getFloat(offset + 4), z = data.getFloat(offset + 8);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                    throw new IllegalArgumentException("Non-finite Earth terrain vertex");
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private BitSet animatedOpaqueFaces(AnimatedAtlas atlas, boolean stopAfterFirst) {
        int corners = compact() ? 4 : 3;
        var animated = new BitSet(opaque / corners);
        if (atlas.isEmpty()) return animated;
        ByteBuffer data = compact() ? quads() : old.vertices();
        for (int first = 0; first < opaque; first += corners) {
            float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < corners; corner++) {
                int offset = (first + corner) * STRIDE;
                float u = data.getFloat(offset + 16), v = data.getFloat(offset + 20);
                minU = Math.min(minU, u); minV = Math.min(minV, v);
                maxU = Math.max(maxU, u); maxV = Math.max(maxV, v);
            }
            if (atlas.overlaps(minU, minV, maxU, maxV)) {
                animated.set(first / corners);
                if (stopAfterFirst) return animated;
            }
        }
        return animated;
    }

    public record Layers(DetailedGeometry stationary, DetailedGeometry live) implements AutoCloseable {
        @Override public void close() {
            if (stationary != null) stationary.close();
            if (live != null) live.close();
        }
    }

    /** Transfers ownership into static opaque and live animated/translucent geometry. */
    public Layers partition(AnimatedAtlas atlas) throws IOException {
        return partition(animatedOpaqueFaces(atlas));
    }

    /** Keeps cutout and translucent faces together while exposing a solid-only shader pass. */
    public Layers partitionSolid() throws IOException {
        int corners = compact() ? 4 : 3;
        var cutouts = new BitSet(opaque / corners);
        ByteBuffer data = compact() ? quads() : old.vertices();
        for (int first = 0; first < opaque; first += corners) {
            int skyAndCutoff = Short.toUnsignedInt(data.getShort(first * STRIDE + SKY_AND_CUTOUT));
            if (((skyAndCutoff >>> 4) & 255) != 0) cutouts.set(first / corners);
        }
        return partition(cutouts);
    }

    /** Material state IDs as the native pack converter reads them from a compact terrain face. */
    public BitSet solidMaterialStates() {
        // A legacy triangle packet does not always carry the fourth corner of the original quad.
        if (!compact()) return null;
        var result = new BitSet();
        var data = quads();
        int states = Block.BLOCK_STATE_REGISTRY.size();
        for (int face = 0; face < opaque * STRIDE; face += 4 * STRIDE) {
            int material = 0;
            for (int corner = 0; corner < 4; corner++)
                material |= Byte.toUnsignedInt(data.get(face + corner * STRIDE + MATERIAL_BYTE)) << (corner * Byte.SIZE);
            int state = (material >>> 1) - 1;
            if (state >= 0 && state < states) result.set(state);
        }
        return result;
    }

    public Layers partition(BitSet animated) throws IOException {
        int corners = compact() ? 4 : 3;
        int liveOpaque = animated.cardinality() * corners;
        int stationaryVertices = opaque - liveOpaque;
        int liveVertices = liveOpaque + vertices - opaque;
        if (stationaryVertices == 0) return new Layers(null, this);
        if (liveVertices == 0) return new Layers(this, null);
        ByteBuffer source = compact() ? quads() : old.vertices();
        DetailedGeometry stationary = null, live = null;
        try {
            stationary = partitionPart(source, animated, corners, false, stationaryVertices, stationaryVertices);
            live = partitionPart(source, animated, corners, true, liveVertices, liveOpaque);
        } catch (IOException | RuntimeException | Error failure) {
            if (stationary != null) stationary.close();
            if (live != null) live.close();
            throw failure;
        }
        close();
        return new Layers(stationary, live);
    }

    private DetailedGeometry partitionPart(ByteBuffer source, BitSet animated, int corners, boolean live,
                                            int count, int opaqueCount) throws IOException {
        int length = Math.multiplyExact(count, STRIDE);
        ByteBuffer output = compact() ? MemoryUtil.memAlloc(Math.max(1, length)).order(ByteOrder.nativeOrder())
                : ByteBuffer.allocate(Math.addExact(HEADER, length)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (!compact()) header(output, KUI, count, opaqueCount, minY, maxY);
            int faces = opaque / corners;
            int faceBytes = corners * STRIDE;
            for (int first = live ? animated.nextSetBit(0) : animated.nextClearBit(0);
                 first >= 0 && first < faces; ) {
                int next = live ? animated.nextClearBit(first) : animated.nextSetBit(first);
                int end = next < 0 ? faces : Math.min(faces, next);
                output.put(output.position(), source, first * faceBytes, (end - first) * faceBytes);
                output.position(output.position() + (end - first) * faceBytes);
                first = live ? animated.nextSetBit(end) : animated.nextClearBit(end);
            }
            if (live && vertices > opaque) output.put(source.slice(opaque * STRIDE, (vertices - opaque) * STRIDE));
            output.flip();
            if (!compact()) {
                var mesh = KuiNativeMesh.decode(output.array());
                return new DetailedGeometry(mesh, null, count, opaqueCount, minY, maxY);
            }
            return new DetailedGeometry(null, output, count, opaqueCount, minY, maxY);
        } catch (IOException | RuntimeException | Error failure) {
            if (compact()) MemoryUtil.memFree(output);
            throw failure;
        }
    }

    /** Used only when the GPU does not expose the geometry backend. */
    public KuiNativeMesh assembleOnCpu() throws IOException {
        int count = triangleVertices();
        var out = ByteBuffer.allocate(HEADER + count * STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        header(out, KUI, count, opaqueTrianglesSize(), minY, maxY);
        for (int base = 0; base < vertices; base += 4) for (int corner : INDICES) {
            int source = (base + corner) * STRIDE;
            out.put(sourceSlice(source));
        }
        return KuiNativeMesh.decode(out.array());
    }

    private ByteBuffer sourceSlice(int offset) {
        return quads.slice(offset, STRIDE);
    }

    private static void header(ByteBuffer output, int kind, int count, int opaque, float min, float max) {
        output.putInt(kind).putInt(1).putInt(count).putInt(opaque).putFloat(min).putFloat(max).putInt(STRIDE).putInt(0);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (old != null) old.close(); else MemoryUtil.memFree(allocation);
    }
}
