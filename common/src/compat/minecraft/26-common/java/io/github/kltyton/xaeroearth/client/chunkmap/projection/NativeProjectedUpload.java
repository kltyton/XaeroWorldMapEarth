package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Owns device-ready RGBA8 endpoints and bit-exact encoded depth. */
public final class NativeProjectedUpload implements AutoCloseable {
    private static final int BYTES = ProjectedGrid.IMAGE_PIXELS * ProjectedGrid.IMAGE_PIXELS * 4;
    final ByteBuffer low, high, depth;
    private final ProjectedTileStore.Image imageOwner;
    private boolean closed;

    private NativeProjectedUpload(ByteBuffer low, ByteBuffer high, ByteBuffer depth, ProjectedTileStore.Image imageOwner) {
        this.low = low; this.high = high; this.depth = depth;
        this.imageOwner = imageOwner;
    }

    /** Takes ownership of the decoded image, including on failure. */
    public static NativeProjectedUpload from(ProjectedTileStore.Image image) {
        ByteBuffer low = null, high = null, depth = null;
        try {
            if (image.reverseDepth() != NativeProjectedDevice.reverseDepth())
                throw new IllegalArgumentException("Projected image depth direction does not match the device");
            if (image.colorFormat() == ProjectedTileStore.Image.ColorFormat.RGBA8)
                return new NativeProjectedUpload(image.mutableLow(), image.mutableHigh(), image.mutableDepth(), image);
            low = MemoryUtil.memAlloc(BYTES);
            high = MemoryUtil.memAlloc(BYTES);
            depth = MemoryUtil.memAlloc(BYTES);
            var a = image.low(); var b = image.high();
            while (a.hasRemaining()) {
                low.put(unorm(Float.float16ToFloat(a.getShort())));
                high.put(unorm(Float.float16ToFloat(b.getShort())));
            }
            low.flip(); high.flip(); depth.put(image.depth()).flip();
            image.close();
            return new NativeProjectedUpload(low, high, depth, null);
        } catch (RuntimeException | Error failure) {
            if (low != null) MemoryUtil.memFree(low);
            if (high != null) MemoryUtil.memFree(high);
            if (depth != null) MemoryUtil.memFree(depth);
            image.close();
            throw failure;
        }
    }

    private static byte unorm(float value) { return (byte) Math.round(Math.max(0, Math.min(1, value)) * 255); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (imageOwner != null) imageOwner.close();
        else { MemoryUtil.memFree(low); MemoryUtil.memFree(high); MemoryUtil.memFree(depth); }
    }
}
