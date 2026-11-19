package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedGrid;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.system.MemoryStack;

/** Device resources for the pack's unlit material samples, with world-aligned texel depth. */
final class MapPackMaterialTileTextures implements AutoCloseable {
    private static final int SIZE = ProjectedGrid.IMAGE_PIXELS;
    static final int PLANES = 6;
    private static final int[] OUTPUTS = {0, 1, 2, 4, 5, 6};
    static final long TILE_BYTES = (long) SIZE * SIZE * (PLANES * 8 + 4);
    private int framebuffer;

    static boolean available() {
        return GL.getCapabilities().OpenGL33 && GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS) >= 7
                && GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS) >= PLANES;
    }

    record Tile(int planes, int depth, double x, double y, double z) implements AutoCloseable {
        @Override public void close() {
            GL11.glDeleteTextures(depth);
            GL11.glDeleteTextures(planes);
        }
    }

    Tile allocate(double x, double y, double z) {
        int planes = GL11.glGenTextures();
        int depth = GL11.glGenTextures();
        int previousArray = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int previousDepth = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        var capabilities = GL.getCapabilities();
        boolean immutable = capabilities.OpenGL42 || capabilities.GL_ARB_texture_storage;
        int previousUpload = immutable ? 0 : GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        boolean good = false;
        try {
            if (previousUpload != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, planes);
            if (immutable) GL42.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, 1, GL30.GL_RGBA16F, SIZE, SIZE, PLANES);
            else GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA16F, SIZE, SIZE, PLANES, 0,
                    GL11.GL_RGBA, GL30.GL_HALF_FLOAT, (ByteBuffer) null);
            nearest(GL30.GL_TEXTURE_2D_ARRAY);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
            if (immutable) GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_DEPTH_COMPONENT32F, SIZE, SIZE);
            else GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, SIZE, SIZE, 0,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            nearest(GL11.GL_TEXTURE_2D);
            good = true;
            return new Tile(planes, depth, x, y, z);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousDepth);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousArray);
            if (previousUpload != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previousUpload);
            if (!good) { GL11.glDeleteTextures(depth); GL11.glDeleteTextures(planes); }
        }
    }

    private static void nearest(int type) {
        GL11.glTexParameteri(type, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(type, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(type, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(type, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    void record(Tile image, boolean reverseDepth, java.util.function.IntConsumer record) {
        int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try (var stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            try {
                for (int i = 0; i < PLANES; i++) GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER,
                        GL30.GL_COLOR_ATTACHMENT0 + i, image.planes(), 0, i);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL11.GL_TEXTURE_2D, image.depth(), 0);
                GL20.glDrawBuffers(stack.ints(GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1,
                        GL30.GL_COLOR_ATTACHMENT2, GL11.GL_NONE, GL30.GL_COLOR_ATTACHMENT3,
                        GL30.GL_COLOR_ATTACHMENT4, GL30.GL_COLOR_ATTACHMENT5));
                if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Incomplete shader pack material tile framebuffer");
                GL11.glViewport(0, 0, SIZE, SIZE);
                if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
                materialWrites(() -> {
                    var clear = stack.callocFloat(4);
                    for (int i : OUTPUTS) GL30.glClearBufferfv(GL11.GL_COLOR, i, clear);
                    clear.put(0, reverseDepth ? 0 : 1);
                    GL30.glClearBufferfv(GL11.GL_DEPTH, 0, clear);
                });
                record.accept(framebuffer);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
                GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    static void materialWrites(Runnable raster) {
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try (var stack = MemoryStack.stackPush()) {
            var channels = stack.malloc(4);
            int[] previous = new int[OUTPUTS.length];
            for (int i = 0; i < OUTPUTS.length; i++) {
                int output = OUTPUTS[i];
                GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, output, channels);
                for (int channel = 0; channel < 4; channel++)
                    if (channels.get(channel) != 0) previous[i] |= 1 << channel;
                if (GL30.glIsEnabledi(GL11.GL_BLEND, output)) {
                    previous[i] |= 16;
                    GL30.glDisablei(GL11.GL_BLEND, output);
                }
                if ((previous[i] & 15) != 15) GL30.glColorMaski(output, true, true, true, true);
            }
            if (!depthMask) GL11.glDepthMask(true);
            try { raster.run(); }
            finally {
                if (!depthMask) GL11.glDepthMask(false);
                for (int i = 0; i < OUTPUTS.length; i++) {
                    int output = OUTPUTS[i], mask = previous[i];
                    if ((mask & 15) != 15) GL30.glColorMaski(output, (mask & 1) != 0, (mask & 2) != 0,
                            (mask & 4) != 0, (mask & 8) != 0);
                    if ((mask & 16) != 0) GL30.glEnablei(GL11.GL_BLEND, output);
                }
            }
        }
    }

    static void sample(Tile image, int first, int second, Runnable draw) {
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + first);
        int previousPlanes = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int previousPlaneSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(first, 0);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, image.planes());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + second);
        int previousDepth = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousDepthSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(second, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, image.depth());
        try {
            draw.run();
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + second);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousDepth);
            GL33.glBindSampler(second, previousDepthSampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + first);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousPlanes);
            GL33.glBindSampler(first, previousPlaneSampler);
            GL13.glActiveTexture(previousUnit);
        }
    }

    @Override public void close() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        framebuffer = 0;
    }
}
