package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** A continuous heightfield whose grid vertices are derived on the GPU. */
public final class SurfaceRenderer implements AutoCloseable {
    private static final BindGroupLayout PARAMETERS = BindGroupLayout.builder()
            .withUniform("SurfaceParams", UniformType.UNIFORM_BUFFER)
            .withSampler("SurfaceColor").withSampler("SurfaceHeight").withSampler("Coverage").build();
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).withBindGroupLayout(PARAMETERS)
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "native_surface"))
            .withVertexShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/native_surface"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/native_surface"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(true)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15)).build();
    private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("KUI surface projection");
    private final DynamicUniformStorage<Parameters> parameters = new DynamicUniformStorage<>("KUI surface parameters", 64, 2);
    private boolean closed;
    private GpuBuffer heightUpload;
    private final SurfaceTransfer<SurfaceTexturePair> transfer = new SurfaceTransfer<>(ByteOrder.nativeOrder()) {
        @Override boolean matches(SurfaceTexturePair textures, NativeTerrainScene.Surface data) {
            return textures.matches(data);
        }

        @Override SurfaceTexturePair allocate(NativeTerrainScene.Surface data) {
            var device = RenderSystem.getDevice();
            int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
            return SurfaceTexturePair.allocate(
                    () -> device.createTexture(() -> "KUI surface color", usage, GpuFormat.RGBA8_UNORM, data.width(), data.depth(), 1, 1),
                    () -> device.createTexture(() -> "KUI surface height", usage, GpuFormat.R32_FLOAT, data.width(), data.heightTextureRows(), 1, 1));
        }

        @Override void color(SurfaceTexturePair textures, ByteBuffer data, int width, int row, int rows) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(textures.color(), data,
                    0, 0, 0, row, width, rows);
        }

        @Override void height(SurfaceTexturePair textures, ByteBuffer data, int width, int row, int rows) {
            int bytes = data.remaining();
            if (heightUpload == null || heightUpload.size() < bytes) {
                if (heightUpload != null) heightUpload.close();
                heightUpload = RenderSystem.getDevice().createBuffer(() -> "KUI surface height transfer",
                        GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, bytes);
            }
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            var source = heightUpload.slice(0, bytes);
            encoder.writeToBuffer(source, data);
            encoder.copyBufferToTexture(source, 0, 0, width, rows, textures.height(), 0, row, width, rows, 0, 0);
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
        RenderSystem.setProjectionMatrix(projection.getBuffer(camera.nativeProjectionMatrix(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne())),
                camera.mode().equals("street") ? ProjectionType.PERSPECTIVE : ProjectionType.ORTHOGRAPHIC);
        Optional<Vector4fc> clearColor = clear ? Optional.of(new Vector4f(underground ? 0 : 0.46f,
                underground ? 0 : 0.67f, underground ? 0 : 0.82f, 1)) : Optional.empty();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "KUI continuous terrain surface",
                target.getColorTextureView(), clearColor, target.getDepthTextureView(), OptionalDouble.of(0))) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.setUniform("SurfaceParams", values);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("SurfaceColor", textures.colorView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("SurfaceHeight", textures.heightView(), sampler);
            pass.bindTexture("Coverage", coverage, sampler);
            pass.draw(surface.vertexCount(), 1, 0, 0);
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
        if (heightUpload != null) { heightUpload.close(); heightUpload = null; }
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
            buffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        }
    }
}
