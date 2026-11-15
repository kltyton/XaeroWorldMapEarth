package io.github.kltyton.xaeroearth.client.render.navigation;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.xaeroearth.client.navigation.NavigationTrace;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/** Uploads camera-relative vertices and GLSL parameters to Minecraft's GPU abstraction. */
public final class NavigationRenderer {
    private static DynamicUniformStorage<Parameters> parameters;
    private static ByteBuffer vertices;
    private static GpuBuffer vertexBuffer;
    private static int capacity;
    private static ProjectionMatrixBuffer mapProjection;

    public static void draw(Matrix4fc view, Matrix4fc projection, Vec3 camera) {
        Minecraft minecraft = Minecraft.getInstance();
        TerrainScanRenderer.draw(minecraft, view, projection, camera);
        drawTrace(NavigationRenderPlatform.mainTarget(minecraft), view, camera, 1);
    }

    public static void drawMap(RenderTarget target, MapCamera camera) {
        if (NavigationTrace.vertices(Minecraft.getInstance().level) == 0) return;
        if (mapProjection == null) mapProjection = new ProjectionMatrixBuffer("Earth navigation map projection");
        var previousProjection = RenderSystem.getProjectionMatrixBuffer();
        var previousType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(mapProjection.getBuffer(camera.nativeProjectionMatrix(NavigationRenderPlatform.zeroToOne())),
                camera.mode().equals("street") ? ProjectionType.PERSPECTIVE : ProjectionType.ORTHOGRAPHIC);
        try {
            drawTrace(target, camera.viewMatrix(), new Vec3(camera.x(), camera.y(), camera.z()),
                    NavigationTrace.mapWidth(camera, target.width));
        } finally {
            RenderSystem.setProjectionMatrix(previousProjection, previousType);
        }
    }

    private static void drawTrace(RenderTarget target, Matrix4fc view, Vec3 camera, double width) {
        Minecraft minecraft = Minecraft.getInstance();
        int count = NavigationTrace.vertices(minecraft.level);
        if (count == 0) return;
        int bytes = Math.multiplyExact(count, NavigationTrace.STRIDE);
        var device = RenderSystem.getDevice();
        if (capacity < bytes) {
            if (vertexBuffer != null) vertexBuffer.close();
            if (vertices != null) MemoryUtil.memFree(vertices);
            capacity = Integer.highestOneBit(bytes - 1) << 1;
            vertexBuffer = device.createBuffer(() -> "Earth navigation trace",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
            vertices = MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
        }
        vertices.clear();
        NavigationTrace.write(vertices, camera, width);
        var encoder = device.createCommandEncoder();
        encoder.writeToBuffer(vertexBuffer.slice(0, bytes), vertices);
        if (parameters == null) parameters = new DynamicUniformStorage<>("Earth navigation uniforms", 16, 2);
        parameters.endFrame();
        var uniforms = parameters.writeUniform(new Parameters((System.nanoTime() % 20_000_000_000L) / 1_000_000_000.0F));
        var transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(view),
                new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
        try (var pass = NavigationRenderPlatform.open(target, encoder)) {
            pass.setPipeline(NavigationRenderPlatform.PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.setUniform("NavigationParameters", uniforms);
            NavigationRenderPlatform.draw(pass, vertexBuffer, count);
        }
    }

    public static void close() {
        TerrainScanRenderer.close();
        if (vertexBuffer != null) { vertexBuffer.close(); vertexBuffer = null; }
        if (vertices != null) { MemoryUtil.memFree(vertices); vertices = null; }
        if (parameters != null) { parameters.close(); parameters = null; }
        if (mapProjection != null) { mapProjection.close(); mapProjection = null; }
        capacity = 0;
    }

    private record Parameters(float seconds)
            implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer buffer) {
            buffer.putFloat(seconds).putFloat(0).putFloat(0).putFloat(0);
        }
    }

    private NavigationRenderer() {}
}
