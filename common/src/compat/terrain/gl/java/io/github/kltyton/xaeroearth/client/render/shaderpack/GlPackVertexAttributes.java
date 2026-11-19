package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.platform.GlStateManager;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.GpuMesh;
import io.github.kltyton.xaeroearth.client.mixin.shaderpack.MapPackTerrainMesh;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/** Translates the KUI light and material layout once per resident mesh, with no GPU readback. */
final class GlPackVertexAttributes implements AutoCloseable {
    static final int STRIDE = 32;
    private static final int KEEP_FRAMES = 300;
    private static final long MEMORY_LIMIT = 128L << 20;
    private static final class Buffer {
        final int name;
        final long bytes;
        int tick;
        Buffer(int name, long bytes, int tick) { this.name = name; this.bytes = bytes; this.tick = tick; }
    }
    private final Map<GpuMesh, Buffer> buffers = new IdentityHashMap<>();
    private long residentBytes;
    private int program, vertices, materials, materialBuffer, materialCount, frame;
    private int countUniform, materialCountUniform;

    void materials(IntBuffer ids) throws IOException {
        if (program == 0) open();
        materialCount = ids.remaining() / 2;
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, materialBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ids, GL15.GL_STATIC_DRAW);
        } finally { GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous); }
    }

    void beginFrame() {
        frame++;
        if (frame % 30 != 0 && residentBytes <= MEMORY_LIMIT) return;
        var entries = buffers.entrySet().iterator();
        while (entries.hasNext()) {
            var item = entries.next();
            var entry = item.getValue();
            int age = frame - entry.tick;
            boolean deleted = ((MapPackTerrainMesh) (Object) item.getKey()).earth$deleted();
            if (!deleted && age <= KEEP_FRAMES && (residentBytes <= MEMORY_LIMIT || age <= 1)) continue;
            GL15.glDeleteBuffers(entry.name);
            residentBytes -= entry.bytes;
            entries.remove();
        }
    }

    int get(GpuMesh mesh, int vertexBuffer) {
        var previous = buffers.get(mesh);
        if (previous != null) {
            previous.tick = frame;
            return previous.name;
        }
        if (program == 0) throw new IllegalStateException("Shader terrain attributes are not initialized");
        int count = mesh.opaqueCount() + mesh.translucentCount();
        int result = GL15.glGenBuffers();
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int oldFeedback = GL11.glGetInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);
        int oldIndexed = GL30.glGetIntegeri(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING, 0);
        int oldActive = GlStateManager._getActiveTexture();
        int oldVertices = boundTexture(GL13.GL_TEXTURE0);
        int oldMaterials = boundTexture(GL13.GL_TEXTURE1);
        boolean oldDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
        boolean complete = false;
        try {
            bindTexture(GL13.GL_TEXTURE0, vertices);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32UI, vertexBuffer);
            bindTexture(GL13.GL_TEXTURE1, materials);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RG32I, materialBuffer);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, result);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) count * STRIDE, GL15.GL_STATIC_COPY);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, result);
            GlStateManager._glUseProgram(program);
            GL20.glUniform1i(countUniform, count);
            GL20.glUniform1i(materialCountUniform, materialCount);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            GL30.glBeginTransformFeedback(GL11.GL_POINTS);
            try { GL11.glDrawArrays(GL11.GL_POINTS, 0, count); }
            finally { GL30.glEndTransformFeedback(); }
            long bytes = (long) count * STRIDE;
            buffers.put(mesh, new Buffer(result, bytes, frame));
            residentBytes += bytes;
            complete = true;
            return result;
        } finally {
            if (!oldDiscard) GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GlStateManager._glUseProgram(oldProgram);
            bindTexture(GL13.GL_TEXTURE1, oldMaterials);
            bindTexture(GL13.GL_TEXTURE0, oldVertices);
            GlStateManager._activeTexture(oldActive);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, oldIndexed);
            GlStateManager._glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, oldFeedback);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, oldBuffer);
            if (!complete) GL15.glDeleteBuffers(result);
        }
    }

    private void open() throws IOException {
        String source;
        try (var stream = getClass().getResourceAsStream("/assets/xaeroearth/shaders/bake/shader_pack_attributes.vsh")) {
            if (stream == null) throw new IOException("Missing Earth shader pack vertex converter");
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int candidate = GL20.glCreateProgram(), shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IOException(GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(candidate, shader);
            GL30.glTransformFeedbackVaryings(candidate,
                    new CharSequence[]{"LightAndColor", "SpriteCenter", "TangentAndMidBlock", "Material"},
                    GL30.GL_INTERLEAVED_ATTRIBS);
            GL20.glLinkProgram(candidate);
            if (GL20.glGetProgrami(candidate, GL20.GL_LINK_STATUS) == 0) throw new IOException(GL20.glGetProgramInfoLog(candidate));
            GlStateManager._glUseProgram(candidate);
            GL20.glUniform1i(GL20.glGetUniformLocation(candidate, "TerrainVertices"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(candidate, "MaterialIds"), 1);
            countUniform = GL20.glGetUniformLocation(candidate, "VertexCount");
            materialCountUniform = GL20.glGetUniformLocation(candidate, "MaterialCount");
            vertices = GL11.glGenTextures();
            materials = GL11.glGenTextures();
            materialBuffer = GL15.glGenBuffers();
            program = candidate;
        } finally {
            GlStateManager._glUseProgram(oldProgram);
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

    void clearMeshes() {
        buffers.values().forEach(entry -> GL15.glDeleteBuffers(entry.name));
        buffers.clear();
        residentBytes = 0;
    }

    @Override public void close() {
        clearMeshes();
        if (program == 0) return;
        GL20.glDeleteProgram(program);
        GL11.glDeleteTextures(vertices);
        GL11.glDeleteTextures(materials);
        GL15.glDeleteBuffers(materialBuffer);
        program = 0;
    }
}
