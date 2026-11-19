package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** A continuous heightfield whose grid vertices are derived on the GPU. */
public final class SurfaceRenderer implements AutoCloseable {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withUniform("SurfaceParams", UniformType.UNIFORM_BUFFER)
            .withSampler("SurfaceColor").withSampler("SurfaceHeight").withSampler("Coverage")
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "native_surface"))
            .withVertexShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/native_surface"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/native_surface"))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).withCull(true)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withColorTargetState(new ColorTargetState(Optional.empty(), 15)).build();
    private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("KUI surface projection");
    private final DynamicUniformStorage<Parameters> parameters = new DynamicUniformStorage<>("KUI surface parameters", 64, 2);
    private boolean closed;
    private final SurfaceTransfer<SurfaceTexturePair> transfer = new SurfaceTransfer<>(ByteOrder.LITTLE_ENDIAN) {
        @Override boolean matches(SurfaceTexturePair textures, NativeTerrainScene.Surface data) {
            return textures.matches(data);
        }

        @Override SurfaceTexturePair allocate(NativeTerrainScene.Surface data) {
            var device = RenderSystem.getDevice();
            int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
            return SurfaceTexturePair.allocate(
                    () -> device.createTexture(() -> "KUI surface color", usage, TextureFormat.RGBA8, data.width(), data.depth(), 1, 1),
                    () -> device.createTexture(() -> "KUI surface height", usage, TextureFormat.RGBA8, data.width(), data.heightTextureRows(), 1, 1));
        }

        @Override void color(SurfaceTexturePair textures, ByteBuffer data, int width, int row, int rows) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(textures.color(), data,
                    NativeImage.Format.RGBA, 0, 0, 0, row, width, rows);
        }

        @Override void height(SurfaceTexturePair textures, ByteBuffer data, int width, int row, int rows) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(textures.height(), data,
                    NativeImage.Format.RGBA, 0, 0, 0, row, width, rows);
        }

        @Override void release(SurfaceTexturePair textures) { textures.close(); }
    };

    NativeTerrainScene.Surface surface() { return transfer.surface(); }
    GpuTextureView colorTexture() { return transfer.textures().colorView(); }
    GpuTextureView heightTexture() { return transfer.textures().heightView(); }

    public void update(NativeTerrainScene.Surface next) {
        requireRenderThread();
        if (closed) throw new IllegalStateException("Surface renderer is closed");
        transfer.update(next);
    }

    /** Call outside another render pass, before precise opaque geometry on the same attachments. */
    public boolean draw(MapCamera camera, RenderTarget target, GpuTextureView coverage,
                        int coverageChunkX, int coverageChunkZ, float brightness, boolean clear, boolean underground) {
        requireRenderThread();
        var surface = surface();
        if (closed || surface == null) return false;
        var textures = transfer.textures();
        parameters.endFrame();
        var values = parameters.writeUniform(new Parameters(surface.width(), surface.depth(), surface.step(), brightness,
                (float) (camera.x() - coverageChunkX * 16L), (float) (camera.z() - coverageChunkZ * 16L), surface.patchCount(), surface.patchCount() + surface.outlineCount(),
                (float) (surface.x() - camera.x()), (float) -camera.y(), (float) (surface.z() - camera.z()), surface.floor()));
        var transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(camera.viewMatrix()),
                new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
        var previousProjection = RenderSystem.getProjectionMatrixBuffer();
        var previousType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projection.getBuffer(camera.nativeForwardProjectionMatrix(RenderSystem.getDevice().isZZeroToOne())),
                camera.mode().equals("street") ? ProjectionType.PERSPECTIVE : ProjectionType.ORTHOGRAPHIC);
        OptionalInt clearColor = clear ? OptionalInt.of(underground ? 0xFF000000 : 0xFF75ABD1) : OptionalInt.empty();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "KUI continuous terrain surface",
                target.getColorTextureView(), clearColor, target.getDepthTextureView(), OptionalDouble.of(1))) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.setUniform("SurfaceParams", values);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("SurfaceColor", textures.colorView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("SurfaceHeight", textures.heightView(), sampler);
            pass.bindTexture("Coverage", coverage, sampler);
            pass.draw(0, surface.vertexCount());
        } finally {
            RenderSystem.setProjectionMatrix(previousProjection, previousType);
        }
        return true;
    }

    public void reset() {
        requireRenderThread();
        transfer.reset();
    }

    private static void requireRenderThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Surface rendering requires the client thread");
    }

    @Override public void close() {
        requireRenderThread();
        if (closed) return;
        closed = true;
        transfer.close();
        projection.close();
        parameters.close();
    }

    private record Parameters(float width, float depth, float step, float brightness, float coverageX, float coverageZ, float patchCount, float outlineEnd,
                              float originX, float originY, float originZ, float floor)
            implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer buffer) {
            buffer.putFloat(width).putFloat(depth).putFloat(step).putFloat(brightness);
            buffer.putFloat(coverageX).putFloat(coverageZ).putFloat(patchCount).putFloat(outlineEnd);
            buffer.putFloat(originX).putFloat(originY).putFloat(originZ).putFloat(floor);
            buffer.putFloat(1).putFloat(1).putFloat(0).putFloat(0);
        }
    }
}
