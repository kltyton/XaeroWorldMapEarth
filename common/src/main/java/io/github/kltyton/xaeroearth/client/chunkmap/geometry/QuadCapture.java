package io.github.kltyton.xaeroearth.client.chunkmap.geometry;

import java.nio.ByteBuffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Limits compact output to Earth tile workers; other users of the KUI baker still receive triangles. */
public final class QuadCapture implements AutoCloseable {
    private static final int[] FACE = {0, 1, 2, 3};
    private static final ThreadLocal<QuadCapture> REQUEST = new ThreadLocal<>();
    private boolean compact;
    private int material;

    public static QuadCapture open() {
        if (REQUEST.get() != null) throw new IllegalStateException("Nested map face capture");
        var capture = new QuadCapture();
        REQUEST.set(capture);
        return capture;
    }

    public static int[] vertices(int[] triangles) {
        var capture = REQUEST.get();
        if (capture == null) return triangles;
        capture.compact = true;
        return FACE;
    }

    public static void material(BlockState state, boolean fluid) {
        var capture = REQUEST.get();
        if (capture == null) return;
        int id = Block.getId(fluid ? state.getFluidState().createLegacyBlock() : state);
        capture.material = ((id + 1) << 1) | (fluid ? 1 : 0);
    }

    public static void writeMaterial(ByteBuffer quad) {
        var capture = REQUEST.get();
        if (capture == null) return;
        // KUI reads three normal bytes. Its fourth byte is padding on each corner.
        for (int i = 0; i < 4; i++)
            quad.put(i * DetailedGeometry.STRIDE + 31, (byte) (capture.material >>> (i * 8)));
    }

    public boolean compact() { return compact; }
    @Override public void close() { REQUEST.remove(); }
    private QuadCapture() {}
}
