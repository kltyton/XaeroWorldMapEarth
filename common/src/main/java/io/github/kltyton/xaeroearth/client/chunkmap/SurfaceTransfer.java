package io.github.kltyton.xaeroearth.client.chunkmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/** Uploads coarse terrain in strips and publishes color and height as one complete GPU surface. */
abstract class SurfaceTransfer<T> implements AutoCloseable {
    private static final int UPLOAD_BYTES = 2 << 20;
    private final ByteOrder order;
    private NativeTerrainScene.Surface visible, pending;
    private T front, back;
    private ByteBuffer buffer;
    private int colorRow, heightRow;

    SurfaceTransfer(ByteOrder order) { this.order = order; }

    final boolean hasWork(NativeTerrainScene.Surface next) { return pending != null || visible != next; }

    final void update(NativeTerrainScene.Surface next) {
        if (next == null) { reset(); return; }
        if (pending != null && pending != next && disjoint(pending, next)) pending = null;
        if (pending == null) {
            if (visible == next) return;
            if (next.width() < 1 || next.depth() < 1 || next.step() < 1
                    || next.rgba().length != Math.multiplyExact(Math.multiplyExact(next.width(), next.depth()), 4))
                throw new IllegalArgumentException("Invalid surface dimensions");
            if (back != null && !matches(back, next)) { release(back); back = null; }
            if (back == null) back = allocate(next);
            pending = next;
            colorRow = heightRow = 0;
        }
        int rowBytes = Math.multiplyExact(pending.width(), 4);
        int available = Math.max(1, (visible == null ? 2 * UPLOAD_BYTES : UPLOAD_BYTES) / rowBytes);
        int colors = Math.min(available, pending.depth() - colorRow);
        if (colors > 0) {
            var data = buffer(colors * rowBytes);
            data.put(pending.rgba(), colorRow * rowBytes, colors * rowBytes).flip();
            color(back, data, pending.width(), colorRow, colors);
            colorRow += colors;
        }
        int heights = Math.min(available - colors, pending.heightTextureRows() - heightRow);
        if (heights > 0) {
            var data = buffer(heights * rowBytes);
            pending.writeHeightRows(data, heightRow, heights);
            data.flip();
            height(back, data, pending.width(), heightRow, heights);
            heightRow += heights;
        }
        if (colorRow == pending.depth() && heightRow == pending.heightTextureRows()) {
            T previous = front;
            front = back;
            back = previous;
            visible = pending;
            pending = null;
        }
    }

    final T textures() { return front; }
    final NativeTerrainScene.Surface surface() { return visible; }

    private static boolean disjoint(NativeTerrainScene.Surface a, NativeTerrainScene.Surface b) {
        return a.x() + (long) a.width() * a.step() <= b.x() || b.x() + (long) b.width() * b.step() <= a.x()
                || a.z() + (long) a.depth() * a.step() <= b.z() || b.z() + (long) b.depth() * b.step() <= a.z();
    }

    private ByteBuffer buffer(int bytes) {
        if (buffer == null || buffer.capacity() < bytes) {
            var next = MemoryUtil.memAlloc(bytes).order(order);
            if (buffer != null) MemoryUtil.memFree(buffer);
            buffer = next;
        }
        return buffer.clear();
    }

    abstract boolean matches(T textures, NativeTerrainScene.Surface data);
    abstract T allocate(NativeTerrainScene.Surface data);
    abstract void color(T textures, ByteBuffer data, int width, int row, int rows);
    abstract void height(T textures, ByteBuffer data, int width, int row, int rows);
    abstract void release(T textures);

    final void reset() {
        if (front != null) release(front);
        if (back != null) release(back);
        front = back = null;
        visible = pending = null;
    }

    @Override public final void close() {
        reset();
        if (buffer != null) { MemoryUtil.memFree(buffer); buffer = null; }
    }
}
