package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/** Minecraft 26.1 image attachments, forward depth and device bindings. */
final class NativeProjectedDevice {
    static boolean reverseDepth() { return false; }
    static String cacheVariant() { return "|device-26.1-rgba8"; }
    static void copyMapped(GpuBuffer buffer, ByteBuffer destination, int bytes) {
        try (var mapped = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false)) {
            destination.duplicate().clear().put(mapped.data().duplicate().limit(bytes));
        }
    }
    static TextureTarget target(String label, boolean depth) {
        return new TextureTarget(label, ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS, depth);
    }
    static Matrix4f projection(Matrix4f matrix) {
        return RenderSystem.getDevice().isZZeroToOne()
                ? new Matrix4f().m22(0.5F).m32(0.5F).mul(matrix) : matrix;
    }
    static void clear(TextureTarget target) {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                target.getColorTexture(), 0, target.getDepthTexture(), 1);
    }
    static void upload(TextureTarget target, ByteBuffer pixels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(target.getColorTexture(), pixels.duplicate(),
                NativeImage.Format.RGBA, 0, 0, 0, 0, ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS);
    }
    static RenderPipeline coveragePipeline() { return encoding("projected_native_coverage", 8); }
    static RenderPipeline depthPipeline() { return encoding("projected_native_depth", 15); }
    private static RenderPipeline encoding(String name, int mask) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", name))
                .withVertexShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/projected_native"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/" + name))
                .withSampler("SourceDepth").withCull(false)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(), mask)).build();
    }
    static void encode(RenderPipeline pipeline, TextureTarget output, TextureTarget input) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Earth projected depth encoding", output.getColorTextureView(), OptionalInt.empty())) {
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
                .withUniform("ProjectedTileParams", UniformType.UNIFORM_BUFFER)
                .withSampler("LowColor").withSampler("HighColor").withSampler("EncodedDepth")
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withCull(false).withDepthStencilState(DepthStencilState.DEFAULT).build();
    }
    static RenderPass openComposite(RenderTarget target) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Earth projected map tiles",
                target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty());
    }
    static void draw(RenderPass pass) { pass.draw(0, 3); }
    private NativeProjectedDevice() { }
}
