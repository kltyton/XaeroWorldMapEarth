package io.github.kltyton.xaeroearth.client.render.navigation;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiMapGlProgram;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import io.github.kltyton.xaeroearth.client.navigation.NavigationTrace;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.mapmark.MapMarkMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Submits the world-space trace on Minecraft's OpenGL render targets. */
public final class NavigationRenderer {
    private static KuiMapGlProgram shader;
    private static ByteBuffer vertices;
    private static int vao, vbo;

    public static void draw(Matrix4f view, Matrix4f projection, Vec3 camera) {
        Minecraft minecraft = Minecraft.getInstance();
        TerrainScanRenderer.draw(minecraft, view, projection, camera);
        drawTrace(minecraft.getMainRenderTarget(), null, view, projection, camera, 1);
    }

    public static void drawMap(RenderTarget target, MapCamera camera, KuiMapDepthTarget depth) {
        if (NavigationTrace.vertices(Minecraft.getInstance().level) == 0) return;
        drawTrace(target, depth, camera.viewMatrix(), camera.projectionMatrix(), new Vec3(camera.x(), camera.y(), camera.z()),
                NavigationTrace.mapWidth(camera, target.width));
    }

    public static void drawMarks(RenderTarget target, MapCamera camera, KuiMapDepthTarget depth, MapMarkMesh marks) {
        drawVertices(target, depth, camera.viewMatrix(), camera.projectionMatrix(), marks.count(),
                buffer -> marks.write(buffer, camera));
    }

    private static void drawTrace(RenderTarget target, KuiMapDepthTarget depth,
                                  Matrix4f view, Matrix4f projection, Vec3 camera, double width) {
        Minecraft minecraft = Minecraft.getInstance();
        int count = NavigationTrace.vertices(minecraft.level);
        drawVertices(target, depth, view, projection, count, buffer -> NavigationTrace.write(buffer, camera, width));
    }

    private static void drawVertices(RenderTarget target, KuiMapDepthTarget depth, Matrix4f view, Matrix4f projection,
                                     int count, java.util.function.Consumer<ByteBuffer> write) {
        if (count == 0) return;
        int bytes = Math.multiplyExact(count, NavigationTrace.STRIDE);
        if (vertices == null || vertices.capacity() < bytes) {
            if (vertices != null) MemoryUtil.memFree(vertices);
            vertices = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
        }
        vertices.clear();
        write.accept(vertices);
        try (var state = new KuiMapRenderState(target, depth)) {
            if (shader == null) {
                shader = new KuiMapGlProgram("xaeroearth", "navigation_trace");
                vao = GL30.glGenVertexArrays();
                vbo = GL15.glGenBuffers();
            }
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            shader.bind();
            shader.matrix("ModelViewMat", view);
            shader.matrix("ProjMat", projection);
            shader.vector("RouteAnimation", (System.nanoTime() % 20_000_000_000L) / 1_000_000_000.0F, 0, 0, 0);
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, NavigationTrace.STRIDE, 0L);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, NavigationTrace.STRIDE, 12L);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(2);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
        }
    }

    public static void close() {
        TerrainScanRenderer.close();
        if (shader != null) {
            shader.close();
            shader = null;
            GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(vbo);
        }
        if (vertices != null) {
            MemoryUtil.memFree(vertices);
            vertices = null;
        }
    }
    private NavigationRenderer() {}
}
