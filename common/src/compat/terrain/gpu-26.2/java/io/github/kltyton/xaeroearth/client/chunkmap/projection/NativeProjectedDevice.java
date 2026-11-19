package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Minecraft 26.2 image attachments, reverse depth and device bindings. */
final class NativeProjectedDevice {
    static boolean reverseDepth() { return true; }
    static String cacheVariant() { return "|device-26.2-rgba8"; }
    static void copyMapped(GpuBuffer buffer, ByteBuffer destination, int bytes) {
        try (var mapped = buffer.map(true, false)) {
            destination.duplicate().clear().put(mapped.data().duplicate().limit(bytes));
        }
    }
    static TextureTarget target(String label, boolean depth) {
        return new TextureTarget(label, ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS, depth, GpuFormat.RGBA8_UNORM);
    }
    static Matrix4f projection(Matrix4f matrix) {
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        return new Matrix4f().m22(zeroToOne ? -0.5F : -1F).m32(zeroToOne ? 0.5F : 0).mul(matrix);
    }
    static void clear(TextureTarget target) {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                target.getColorTexture(), new Vector4f(), target.getDepthTexture(), 0);
    }
    static void upload(TextureTarget target, ByteBuffer pixels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(target.getColorTexture(), pixels.duplicate(),
                0, 0, 0, 0, ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS);
    }
    static RenderPipeline coveragePipeline() { return encoding("projected_native_coverage_reverse", 8); }
    static RenderPipeline depthPipeline() { return encoding("projected_native_depth", 15); }
    private static RenderPipeline encoding(String name, int mask) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", name))
                .withVertexShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/projected_native"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/" + name))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("SourceDepth").build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, mask)).build();
    }
    static void encode(RenderPipeline pipeline, TextureTarget output, TextureTarget input) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Earth projected depth encoding", output.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("SourceDepth", input.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            draw(pass);
        }
    }
    static RenderPipeline compositePipeline() {
        var shader = Identifier.fromNamespaceAndPath("xaeroearth", "core/projected_native_tile");
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "projected_native_tile"))
                .withVertexShader(shader).withFragmentShader(shader)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("ProjectedTileParams", UniformType.UNIFORM_BUFFER)
                        .withSampler("LowColor").withSampler("HighColor").withSampler("EncodedDepth").build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false).withDepthStencilState(DepthStencilState.DEFAULT).build();
    }
    static RenderPass openComposite(RenderTarget target) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Earth projected map tiles",
                target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty());
    }
    static void draw(RenderPass pass) { pass.draw(3, 1, 0, 0); }
    private NativeProjectedDevice() { }
}
