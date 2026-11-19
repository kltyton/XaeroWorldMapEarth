package io.github.kltyton.xaeroearth.client.render.gl;

import java.nio.ByteOrder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/** Applies tightly packed pixel rows for one OpenGL transfer and restores the caller's layout. */
public final class GlPixelTransferLayout implements AutoCloseable {
    private static final int[] PACK = { GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS,
            GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_SWAP_BYTES, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES };
    private static final int[] UNPACK = { GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS,
            GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_SWAP_BYTES, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES };
    private final int[] keys;
    private final int[] previous;

    public static GlPixelTransferLayout pack(ByteOrder order) { return new GlPixelTransferLayout(PACK, order); }

    public static GlPixelTransferLayout unpack(ByteOrder order) { return new GlPixelTransferLayout(UNPACK, order); }

    private GlPixelTransferLayout(int[] keys, ByteOrder order) {
        this.keys = keys;
        previous = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            previous[i] = GL11.glGetInteger(keys[i]);
            GL11.glPixelStorei(keys[i], i == 3 ? 4 : i == 4 && order != ByteOrder.nativeOrder() ? 1 : 0);
        }
    }

    @Override public void close() {
        for (int i = 0; i < keys.length; i++) GL11.glPixelStorei(keys[i], previous[i]);
    }
}
