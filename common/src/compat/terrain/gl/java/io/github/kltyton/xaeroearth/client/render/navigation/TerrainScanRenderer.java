package io.github.kltyton.xaeroearth.client.render.navigation;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Fullscreen scan before the hand renders on OpenGL targets. */
final class TerrainScanRenderer {
    private static TextureTarget output;
    private static int program, vao, inverse, origin, direction, scan;

    static void draw(Minecraft minecraft, Matrix4f view, Matrix4f projection, Vec3 camera) {
        TerrainScan.Frame frame = TerrainScan.frame(minecraft.level, camera);
        if (frame == null || frame.intensity() <= 0) return;
        RenderTarget target = minecraft.getMainRenderTarget();
        try (var state = new KuiMapRenderState(target); var stack = MemoryStack.stackPush()) {
            if (program == 0) initialize(minecraft);
            prepareOutput(target);
            output.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            int oldUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int oldColor = bindTexture(GL13.GL_TEXTURE0, target.getColorTextureId());
            int oldDepth = bindTexture(GL13.GL_TEXTURE1, target.getDepthTextureId());
            try {
                GlStateManager._glUseProgram(program);
                GL20.glUniformMatrix4fv(inverse, false, new Matrix4f(projection).mul(view).invert().get(stack.mallocFloat(16)));
                Vec3 pos = frame.origin();
                GL20.glUniform4f(origin, (float) pos.x, (float) pos.y, (float) pos.z, 0);
                GL20.glUniform4f(direction, frame.forwardX(), frame.forwardZ(), frame.sectorCos(), 0);
                GL20.glUniform4f(scan, frame.radius(), frame.intensity(), frame.spacing(), 0);
                GlStateManager._glBindVertexArray(vao);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, output.frameBufferId);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
                GL30.glBlitFramebuffer(0, 0, target.width, target.height, 0, 0, target.width, target.height,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            } finally {
                bindTexture(GL13.GL_TEXTURE1, oldDepth);
                bindTexture(GL13.GL_TEXTURE0, oldColor);
                GlStateManager._activeTexture(oldUnit);
            }
        }
    }

    private static int bindTexture(int unit, int texture) {
        GlStateManager._activeTexture(unit);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(texture);
        return previous;
    }

    private static void prepareOutput(RenderTarget target) {
        if (output == null || output.width != target.width || output.height != target.height) {
            if (output != null) output.destroyBuffers();
            output = new TextureTarget(target.width, target.height, false, Minecraft.ON_OSX);
        }
    }

    private static void initialize(Minecraft minecraft) {
        String include = TerrainScanShaderSource.read(minecraft, "include/terrain_scan.glsl");
        String fragment = TerrainScanShaderSource.read(minecraft, "core/terrain_scan.fsh")
                .replace("#moj_import <xaeroearth:terrain_scan.glsl>", include);
        int vertex = compile(GL20.GL_VERTEX_SHADER, TerrainScanShaderSource.read(minecraft, "core/terrain_scan.vsh"));
        int frag = 0, linked = 0;
        try {
            frag = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vertex);
            GL20.glAttachShader(linked, frag);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("Cannot link terrain scan: " + GL20.glGetProgramInfoLog(linked));
            }
            program = linked;
            vao = GL30.glGenVertexArrays();
            GlStateManager._glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "SceneColor"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "SceneDepth"), 1);
            inverse = GL20.glGetUniformLocation(program, "WorldFromClip");
            origin = GL20.glGetUniformLocation(program, "Origin");
            direction = GL20.glGetUniformLocation(program, "Direction");
            scan = GL20.glGetUniformLocation(program, "Scan");
        } finally {
            if (program == 0 && linked != 0) GL20.glDeleteProgram(linked);
            GL20.glDeleteShader(vertex);
            if (frag != 0) GL20.glDeleteShader(frag);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0) return shader;
        String log = GL20.glGetShaderInfoLog(shader);
        GL20.glDeleteShader(shader);
        throw new IllegalStateException("Cannot compile terrain scan shader: " + log);
    }

    static void close() {
        if (output != null) { output.destroyBuffers(); output = null; }
        if (program == 0) return;
        GL20.glDeleteProgram(program);
        GL30.glDeleteVertexArrays(vao);
        program = 0;
    }

    private TerrainScanRenderer() {}
}
