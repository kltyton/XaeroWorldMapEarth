package io.github.kltyton.xaeroearth.client.chunkmap.geometry;

import com.mojang.blaze3d.platform.GlStateManager;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeMesh;
import io.github.kltyton.xaeroearth.client.mixin.terrain.GlTerrainMeshFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;

/** Expands exact KUI model faces into camera-independent, GPU-resident terrain triangles. */
public final class GlTerrainBake implements AutoCloseable {
    private static final int INPUT_SLOTS = 24;
    private final UploadInput[] inputs = createInputs();
    private int program, faceVerticesUniform, lookupBytes, nextInput;
    private IOException unavailable;

    private static UploadInput[] createInputs() {
        var result = new UploadInput[INPUT_SLOTS];
        for (int i = 0; i < result.length; i++) result[i] = new UploadInput();
        return result;
    }

    // A texture-buffer read remains in flight until transform feedback has consumed it.
    private static final class UploadInput {
        int buffer, texture, capacity;
        long fence;

        boolean reusable() {
            if (fence == 0) return true;
            int status = GL32.glClientWaitSync(fence, 0, 0);
            if (status == GL32.GL_TIMEOUT_EXPIRED) return false;
            if (status == GL32.GL_WAIT_FAILED) throw new IllegalStateException("Cannot poll terrain upload fence");
            GL32.glDeleteSync(fence);
            fence = 0;
            return true;
        }

        void close() {
            if (fence != 0) GL32.glDeleteSync(fence);
            if (texture != 0) GL11.glDeleteTextures(texture);
            if (buffer != 0) GL15.glDeleteBuffers(buffer);
            fence = 0;
            texture = buffer = capacity = 0;
        }
    }

    public GeometryUpload prepare(DetailedGeometry geometry) throws IOException {
        if (!geometry.compact()) return triangles(geometry.oldMesh(), false);
        if (unavailable != null) throw unavailable;
        if (program == 0) try { open(); }
        catch (IOException rejected) { unavailable = rejected; throw rejected; }
        return new Transfer(geometry.quads(), geometry.triangleVertices(), geometry.opaqueTrianglesSize(),
                geometry.minY(), geometry.maxY(), true, null);
    }

    public GeometryUpload triangles(KuiNativeMesh mesh, boolean ownsMesh) {
        return new Transfer(mesh.vertices(), mesh.vertexCount(), mesh.opaque().count(),
                mesh.minY(), mesh.maxY(), false, ownsMesh ? mesh : null);
    }

    private final class Transfer implements GeometryUpload {
        private static final int STRIDE = DetailedGeometry.STRIDE, FACE_BYTES = 4 * STRIDE;
        private final ByteBuffer source;
        private final int count, opaque;
        private final long outputBytes;
        private final float minY, maxY;
        private final boolean expand;
        private final KuiNativeMesh owned;
        private int cursor, array, output;

        Transfer(ByteBuffer source, int count, int opaque, float minY, float maxY, boolean expand, KuiNativeMesh owned) {
            this.source = source;
            this.count = count;
            this.opaque = opaque;
            this.minY = minY;
            this.maxY = maxY;
            this.expand = expand;
            this.owned = owned;
            outputBytes = (long) count * STRIDE;
        }

        @Override public Progress advance(int byteLimit) {
            if (count == 0)
                return new Progress(0, GlTerrainMeshFactory.earth$mesh(0, 0, 0, 0, 0, 0, minY, maxY));
            int alignment = expand ? FACE_BYTES : STRIDE;
            int size = Math.min(source.remaining() - cursor, expand ? Math.min(byteLimit, lookupBytes) : byteLimit);
            size -= size % alignment;
            if (size == 0) return new Progress(0, null);
            UploadInput input = expand ? nextAvailableInput() : null;
            if (expand && input == null) return new Progress(0, null);
            int oldArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int oldBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try {
                if (output == 0) {
                    array = GL30.glGenVertexArrays();
                    output = GL15.glGenBuffers();
                    GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, output);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, outputBytes, expand ? GL15.GL_STATIC_COPY : GL15.GL_STATIC_DRAW);
                }
                GlStateManager._glBindVertexArray(array);
                var chunk = source.slice(cursor, size);
                if (expand) expandFaces(input, chunk, output, (long) cursor / FACE_BYTES * 6 * STRIDE);
                else {
                    GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, output);
                    GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, cursor, chunk);
                }
                cursor += size;
                if (cursor < source.remaining()) return new Progress(size, null);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, output);
                layout(STRIDE);
                var mesh = GlTerrainMeshFactory.earth$mesh(array, output, opaque, opaque, count - opaque, outputBytes, minY, maxY);
                array = output = 0;
                return new Progress(size, mesh);
            } finally {
                GlStateManager._glBindVertexArray(oldArray);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, oldBuffer);
            }
        }

        @Override public void close() {
            if (output != 0) { GL15.glDeleteBuffers(output); output = 0; }
            if (array != 0) { GL30.glDeleteVertexArrays(array); array = 0; }
            if (owned != null) owned.close();
        }
    }

    private UploadInput nextAvailableInput() {
        for (int i = 0; i < inputs.length; i++) {
            UploadInput input = inputs[nextInput];
            nextInput = (nextInput + 1) % inputs.length;
            if (input.reusable()) return input;
        }
        return null;
    }

    private void expandFaces(UploadInput input, ByteBuffer chunk, int output, long offset) {
        int count = chunk.remaining() / (4 * DetailedGeometry.STRIDE) * 6;
        int oldFeedback = GL11.glGetInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);
        int oldIndexed = GL30.glGetIntegeri(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING, 0);
        long oldStart = GL32.glGetInteger64i(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_START, 0);
        long oldSize = GL32.glGetInteger64i(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_SIZE, 0);
        int oldActive = GlStateManager._getActiveTexture();
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean oldDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int oldLookup = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        try {
            if (input.buffer == 0) {
                input.buffer = GL15.glGenBuffers();
                input.texture = GL11.glGenTextures();
            }
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, input.buffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, input.texture);
            if (input.capacity < chunk.remaining()) {
                input.capacity = Math.min(lookupBytes, Integer.highestOneBit(chunk.remaining() - 1) << 1);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, input.capacity, GL15.GL_STREAM_DRAW);
                GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32UI, input.buffer);
            }
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, chunk);
            GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, output, offset,
                    (long) count * DetailedGeometry.STRIDE);
            GlStateManager._glUseProgram(program);
            GL20.glUniform1i(faceVerticesUniform, 0);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            GL30.glBeginTransformFeedback(GL11.GL_POINTS);
            try { GL11.glDrawArrays(GL11.GL_POINTS, 0, count); }
            finally { GL30.glEndTransformFeedback(); }
            input.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (input.fence == 0) {
                input.close();
                throw new IllegalStateException("Cannot fence terrain upload");
            }
        } finally {
            if (!oldDiscard) GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GlStateManager._glUseProgram(oldProgram);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, oldLookup);
            GlStateManager._activeTexture(oldActive);
            if (oldIndexed != 0 && oldSize > 0)
                GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, oldIndexed, oldStart, oldSize);
            else GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, oldIndexed);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, oldFeedback);
        }
    }

    private static void layout(int stride) {
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, stride, 12L);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 16L);
        GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, stride, 24L);
        GL20.glVertexAttribPointer(4, 4, GL11.GL_BYTE, true, stride, 28L);
        for (int attribute = 0; attribute < 5; attribute++) GL20.glEnableVertexAttribArray(attribute);
    }

    private void open() throws IOException {
        lookupBytes = (int) Math.min(Integer.MAX_VALUE,
                (long) GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE) * 4 * Integer.BYTES);
        if (lookupBytes < 4 * DetailedGeometry.STRIDE) throw new IOException("Terrain texture buffers cannot hold one quad");
        String source;
        try (var stream = getClass().getResourceAsStream("/assets/xaeroearth/shaders/bake/terrain_faces.vsh")) {
            if (stream == null) throw new IOException("Missing terrain bake vertex program");
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int candidate = GL20.glCreateProgram(), shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        try {
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IOException(GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(candidate, shader);
            GL30.glTransformFeedbackVaryings(candidate, new CharSequence[]{"VertexHead", "VertexTail"}, GL30.GL_INTERLEAVED_ATTRIBS);
            GL20.glLinkProgram(candidate);
            if (GL20.glGetProgrami(candidate, GL20.GL_LINK_STATUS) == 0) throw new IOException(GL20.glGetProgramInfoLog(candidate));
            faceVerticesUniform = GL20.glGetUniformLocation(candidate, "FaceVertices");
            program = candidate;
        } finally {
            GL20.glDeleteShader(shader);
            if (program != candidate) GL20.glDeleteProgram(candidate);
        }
    }

    @Override public void close() {
        if (program == 0) return;
        GL20.glDeleteProgram(program);
        for (UploadInput input : inputs) input.close();
        program = nextInput = 0;
    }
}
