package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/** Converts resident KUI vertices to Iris terrain attributes without a GPU readback. */
final class NativePackLightGeometry implements AutoCloseable {
    private static final int INPUT_STRIDE = 32, OUTPUT_STRIDE = 52;
    private static final int KEEP_FRAMES = 300;
    private static final long MEMORY_LIMIT = 128L << 20;
    private static final class Entry {
        final GpuBuffer buffer;
        final long bytes;
        int frame;
        Entry(GpuBuffer buffer, long bytes, int frame) { this.buffer = buffer; this.bytes = bytes; this.frame = frame; }
    }
    private final Map<GpuBuffer, Entry> converted = new IdentityHashMap<>();
    private long residentBytes;
    private int program, array, texture, materialTexture, materialBuffer, materialCount, verticesPerFace, frame;
    private int terrainVerticesUniform, materialIdsUniform, materialCountUniform, vertexCountUniform;

    void materials(IntBuffer ids) {
        if (program == 0) open();
        materialCount = ids.remaining();
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, materialBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ids, GL15.GL_STATIC_DRAW);
        } finally { GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous); }
    }

    void beginFrame() {
        frame++;
        if (frame % 30 != 0 && residentBytes <= MEMORY_LIMIT) return;
        var entries = converted.entrySet().iterator();
        while (entries.hasNext()) {
            var item = entries.next();
            var entry = item.getValue();
            int age = frame - entry.frame;
            if (!item.getKey().isClosed() && age <= KEEP_FRAMES && (residentBytes <= MEMORY_LIMIT || age <= 1)) continue;
            entry.buffer.close();
            residentBytes -= entry.bytes;
            entries.remove();
        }
    }

    GpuBuffer buffer(GpuBuffer source, boolean indexed) {
        var entry = converted.get(source);
        if (entry != null) {
            entry.frame = frame;
            return entry.buffer;
        }
        if (!(source instanceof GlBuffer glSource)) throw new IllegalStateException("Iris map terrain requires OpenGL buffers");
        if (program == 0 || materialBuffer == 0) throw new IllegalStateException("Iris terrain materials are not initialized");
        int count = Math.toIntExact(source.size() / INPUT_STRIDE);
        long bytes = (long) count * OUTPUT_STRIDE;
        var output = RenderSystem.getDevice().createBuffer(() -> "Earth Iris light and opacity",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes);
        int oldArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int oldTexture = boundTexture(GL13.GL_TEXTURE0), oldMaterials = boundTexture(GL13.GL_TEXTURE1);
        int oldFeedback = GL11.glGetInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);
        int oldIndexed = GL30.glGetIntegeri(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING, 0);
        boolean oldDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
        boolean done = false;
        try {
            GL30.glBindVertexArray(array);
            bindTexture(GL13.GL_TEXTURE0, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32UI, NativeMapPackDevice.glHandle(glSource));
            bindTexture(GL13.GL_TEXTURE1, materialTexture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_R32I, materialBuffer);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, NativeMapPackDevice.glHandle((GlBuffer) output));
            GlStateManager._glUseProgram(program);
            GL20.glUniform1i(verticesPerFace, indexed ? 4 : 6);
            GL20.glUniform1i(terrainVerticesUniform, 0);
            GL20.glUniform1i(materialIdsUniform, 1);
            GL20.glUniform1i(materialCountUniform, materialCount);
            GL20.glUniform1i(vertexCountUniform, count);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            GL30.glBeginTransformFeedback(GL11.GL_POINTS);
            try { GL11.glDrawArrays(GL11.GL_POINTS, 0, count); }
            finally { GL30.glEndTransformFeedback(); }
            converted.put(source, new Entry(output, bytes, frame));
            residentBytes += bytes;
            done = true;
            return output;
        } finally {
            if (!oldDiscard) GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GlStateManager._glUseProgram(oldProgram);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, oldIndexed);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, oldFeedback);
            bindTexture(GL13.GL_TEXTURE1, oldMaterials);
            bindTexture(GL13.GL_TEXTURE0, oldTexture);
            GlStateManager._activeTexture(oldActive);
            GL30.glBindVertexArray(oldArray);
            if (!done) output.close();
        }
    }

    private void open() {
        String source;
        try (var stream = getClass().getResourceAsStream("/assets/xaeroearth/shaders/bake/native_pack_light.vsh")) {
            if (stream == null) throw new IOException("Missing Iris light conversion program");
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        int candidate = GL20.glCreateProgram(), shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        try {
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(candidate, shader);
            GL30.glTransformFeedbackVaryings(candidate, new CharSequence[]{"VertexHead", "VertexTail", "Material",
                    "SpriteCenter", "Tangent", "MidBlock"}, GL30.GL_INTERLEAVED_ATTRIBS);
            GL20.glLinkProgram(candidate);
            if (GL20.glGetProgrami(candidate, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(candidate));
            array = GL30.glGenVertexArrays();
            texture = GL11.glGenTextures();
            materialTexture = GL11.glGenTextures();
            materialBuffer = GL15.glGenBuffers();
            verticesPerFace = GL20.glGetUniformLocation(candidate, "VerticesPerFace");
            terrainVerticesUniform = GL20.glGetUniformLocation(candidate, "TerrainVertices");
            materialIdsUniform = GL20.glGetUniformLocation(candidate, "MaterialIds");
            materialCountUniform = GL20.glGetUniformLocation(candidate, "MaterialCount");
            vertexCountUniform = GL20.glGetUniformLocation(candidate, "VertexCount");
            program = candidate;
        } finally {
            GL20.glDeleteShader(shader);
            if (program != candidate) GL20.glDeleteProgram(candidate);
        }
    }

    private static int boundTexture(int unit) {
        GlStateManager._activeTexture(unit);
        return GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
    }

    private static void bindTexture(int unit, int texture) {
        GlStateManager._activeTexture(unit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
    }

    @Override public void close() {
        converted.values().forEach(entry -> entry.buffer.close());
        converted.clear();
        residentBytes = 0;
        if (program == 0) return;
        GL20.glDeleteProgram(program);
        GL30.glDeleteVertexArrays(array);
        GL11.glDeleteTextures(texture);
        GL11.glDeleteTextures(materialTexture);
        GL15.glDeleteBuffers(materialBuffer);
        materialBuffer = materialTexture = materialCount = 0;
        program = 0;
    }
}
