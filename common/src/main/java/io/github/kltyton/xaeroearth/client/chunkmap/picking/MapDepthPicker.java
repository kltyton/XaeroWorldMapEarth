package io.github.kltyton.xaeroearth.client.chunkmap.picking;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** Reads a small cursor window from the rendered terrain depth without waiting on the render thread. */
public final class MapDepthPicker implements AutoCloseable {
    private static final int DIAMETER = 7;
    private static final int BYTES = DIAMETER * DIAMETER * Float.BYTES;
    private static final long POINTER_REFRESH_NANOS = 100_000_000L;

    public record View(MapCamera camera, long scope, long terrain, long detail) { }
    public record Sample(Vec3 hit) { }
    private record Patch(View view, int width, int height, int x, int y, int columns, int rows,
                         boolean reversed) {
        int index(double mouseX, double mouseY) {
            int column = pixelX(view.camera(), width, mouseX) - x;
            int row = pixelY(view.camera(), height, mouseY) - y;
            return column < 0 || column >= columns || row < 0 || row >= rows ? -1 : row * columns + column;
        }
    }

    private final float[] values = new float[DIAMETER * DIAMETER];
    private int pixelBuffer, depthFramebuffer;
    private long fence, lastCapture;
    private Patch pending, ready;

    public Sample pick(View view, double x, double y) {
        if (ready == null || !ready.view().equals(view)) return null;
        int i = ready.index(x, y);
        if (i < 0) return null;
        float depth = ready.reversed() ? 1 - values[i] : values[i];
        if (!Float.isFinite(depth) || depth < 0 || depth >= 1) return new Sample(null);
        float px = (ready.x() + i % ready.columns() + 0.5f) / ready.width();
        float py = (ready.y() + i / ready.columns() + 0.5f) / ready.height();
        MapCamera camera = view.camera();
        var p = camera.viewProjectionMatrix().invert()
                .transform(new Vector4f(px * 2 - 1, py * 2 - 1, depth * 2 - 1, 1));
        if (!Float.isFinite(p.w) || Math.abs(p.w) < 1e-8) return null;
        return new Sample(new Vec3(camera.x() + (double) p.x / p.w, camera.y() + (double) p.y / p.w,
                camera.z() + (double) p.z / p.w));
    }

    public void captureTexture(int texture, View view, int width, int height, boolean reversed, double x, double y) {
        if (texture == 0) return;
        if (depthFramebuffer == 0) {
            depthFramebuffer = GL30.glGenFramebuffers();
            int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFramebuffer);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
        int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, depthFramebuffer);
        try {
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glReadBuffer(GL11.GL_NONE);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE)
                captureBound(view, width, height, reversed, x, y);
        } finally {
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
        }
    }

    public void captureFramebuffer(int framebuffer, View view, int width, int height, boolean reversed, double x, double y) {
        int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
        try { captureBound(view, width, height, reversed, x, y); }
        finally { GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous); }
    }

    private void captureBound(View view, int width, int height, boolean reversed, double x, double y) {
        poll();
        if (ready != null && (ready.width() != width || ready.height() != height)) ready = null;
        long now = System.nanoTime();
        if (fence != 0 || !Double.isFinite(x) || !Double.isFinite(y)
                || x < 0 || y < 0 || x >= view.camera().width() || y >= view.camera().height()
                || ready != null && ready.view().equals(view) && ready.width() == width && ready.height() == height
                && ready.index(x, y) >= 0 && now - lastCapture < POINTER_REFRESH_NANOS) return;
        int left = Math.max(0, pixelX(view.camera(), width, x) - DIAMETER / 2);
        int bottom = Math.max(0, pixelY(view.camera(), height, y) - DIAMETER / 2);
        int columns = Math.min(DIAMETER, width - left), rows = Math.min(DIAMETER, height - bottom);
        if (columns <= 0 || rows <= 0) return;
        int buffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int row = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH), skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS), alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int swapBytes = GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES);
        try {
            if (pixelBuffer == 0) {
                pixelBuffer = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelBuffer);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, BYTES, GL15.GL_STREAM_READ);
            } else GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, 0);
            GL11.glReadPixels(left, bottom, columns, rows, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
            fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence != 0) {
                pending = new Patch(view, width, height, left, bottom, columns, rows, reversed);
                lastCapture = now;
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, row);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, swapBytes);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
        }
    }

    private void poll() {
        if (fence == 0) return;
        int status = GL32.glClientWaitSync(fence, 0, 0);
        if (status == GL32.GL_TIMEOUT_EXPIRED) return;
        GL32.glDeleteSync(fence);
        fence = 0;
        var completed = pending;
        pending = null;
        if (completed == null || status == GL32.GL_WAIT_FAILED) return;
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelBuffer);
            int count = completed.columns() * completed.rows();
            var data = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, (long) count * Float.BYTES, GL30.GL_MAP_READ_BIT);
            if (data == null) return;
            data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().get(values, 0, count);
            ready = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER) ? completed : null;
        } finally { GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous); }
    }

    public void invalidate() { pending = ready = null; }

    @Override public void close() {
        invalidate();
        if (fence != 0) GL32.glDeleteSync(fence);
        if (pixelBuffer != 0) GL15.glDeleteBuffers(pixelBuffer);
        if (depthFramebuffer != 0) GL30.glDeleteFramebuffers(depthFramebuffer);
        fence = 0;
        pixelBuffer = depthFramebuffer = 0;
    }

    private static int pixelX(MapCamera camera, int width, double x) { return (int) Math.floor(x * width / camera.width()); }
    private static int pixelY(MapCamera camera, int height, double y) {
        return height - 1 - (int) Math.floor(y * height / camera.height());
    }
}
