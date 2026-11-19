package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.BindGroupLayouts;
import org.joml.Vector4f;
import org.joml.Matrix4f;
import net.minecraft.resources.Identifier;

/** Minecraft 26.2 attachment and binding layout for the Iris map pass. */
final class NativeMapPackDevice {
    static RenderPipeline SOLID, OPAQUE, TRANSLUCENT, SHADOW_OPAQUE, SHADOW_TRANSLUCENT;
    private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "map_pack_composite"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/map_pack_composite"))
            .withShaderDefine("EARTH_REVERSE_DEPTH", 1)
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("PackColor").withSampler("PackDepth")
                    .withSampler("CoarseColor").withSampler("CoarseHeight").withSampler("DetailCoverage")
                    .withUniform("PackRaster", UniformType.UNIFORM_BUFFER).build())
            .withCull(false).withDepthStencilState(DepthStencilState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();

    static void initializeTerrain(VertexFormat format) {
        if (OPAQUE != null) return;
        var solid = terrain(format, "iris_map_solid", false, false);
        var opaque = terrain(format, "iris_map_terrain", false, false);
        var translucent = terrain(format, "iris_map_water", true, false);
        var shadowOpaque = terrain(format, "iris_map_shadow_terrain", false, true);
        var shadowTranslucent = terrain(format, "iris_map_shadow_water", true, true);
        SOLID = solid;
        OPAQUE = opaque;
        TRANSLUCENT = translucent;
        SHADOW_OPAQUE = shadowOpaque;
        SHADOW_TRANSLUCENT = shadowTranslucent;
    }

    static boolean compatibleTerrain(VertexFormat format) {
        String[] names = {"Position", "Color", "UV0", "UV2", "Normal", "mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock"};
        int[] offsets = {0, 12, 16, 24, 28, 32, 36, 44, 48};
        GpuFormat[] types = {GpuFormat.RGB32_FLOAT, GpuFormat.RGBA8_UNORM, GpuFormat.RG32_FLOAT, GpuFormat.RG16_SINT,
                GpuFormat.RGBA8_SNORM, GpuFormat.RG16_SINT, GpuFormat.RG32_FLOAT, GpuFormat.RGBA8_SNORM, GpuFormat.RGBA8_SNORM};
        if (format.getVertexSize() != 52 || format.getElements().size() != names.length) return false;
        for (int i = 0; i < names.length; i++) {
            var element = format.getElement(names[i]);
            if (element == null || element.offset() != offsets[i] || element.format() != types[i]) return false;
        }
        return true;
    }

    private static RenderPipeline terrain(VertexFormat format, String name, boolean transparent, boolean shadow) {
        var shader = Identifier.fromNamespaceAndPath("kltytonui", "core/native_map");
        return RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", name))
                .withVertexShader(shader).withFragmentShader(shader)
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("Sampler0").withSampler("Sampler2")
                        .withUniform("MapParams", UniformType.UNIFORM_BUFFER)
                        .withUniform("EarthMaskParams", UniformType.UNIFORM_BUFFER).build())
                .withVertexBinding(0, format)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(!shadow).withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(new ColorTargetState(transparent ? Optional.of(BlendFunction.TRANSLUCENT)
                        : Optional.empty(), GpuFormat.RGBA8_UNORM, 15)).build();
    }

    static TextureTarget target(RenderTarget destination, int width, int height) {
        return new TextureTarget("Earth Iris map", width, height, true, destination.getColorTexture().getFormat());
    }

    static Camera camera(Minecraft minecraft) { return minecraft.gameRenderer.mainCamera(); }
    static boolean zeroToOne() { return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(); }

    static boolean reverseDepth() { return true; }

    static Matrix4f packUniformProjection(Matrix4f rasterProjection) {
        // Iris rewrites depth texture samples to 1 - depth before pack code runs.
        // Its G-buffer matrix uniforms therefore describe the pack's forward GL clip space.
        return new Matrix4f().m22(zeroToOne() ? -2F : -1F).m32(zeroToOne() ? 1F : 0F).mul(rasterProjection);
    }

    static Matrix4f materialProjection(Matrix4f raster) {
        return new Matrix4f().m22(zeroToOne() ? -0.5F : -1F).m32(zeroToOne() ? 0.5F : 0F).mul(raster);
    }

    static Matrix4f shadowUniformProjection(Matrix4f raster) {
        return zeroToOne() ? new Matrix4f().m22(2).m32(-1).mul(raster) : raster;
    }

    static int glHandle(GlBuffer buffer) {
        return buffer.handle();
    }

    static void clear(RenderTarget target) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Clear Earth Iris map", target.getColorTextureView(), Optional.of(new Vector4f()),
                target.getDepthTextureView(), OptionalDouble.of(0))) { }
    }

    static void composite(RenderTarget input, RenderTarget destination, GpuBufferSlice mapping,
                          MapPackLodTransition<GpuTextureView> transition) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Composite Earth Iris map", destination.getColorTextureView(), Optional.empty(),
                destination.getDepthTextureView(), OptionalDouble.empty())) {
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setPipeline(COMPOSITE);
            pass.setUniform("PackRaster", mapping);
            pass.bindTexture("PackColor", input.getColorTextureView(), nearest);
            pass.bindTexture("PackDepth", input.getDepthTextureView(), nearest);
            var fallback = input.getColorTextureView();
            pass.bindTexture("CoarseColor", transition == null ? fallback : transition.color(), nearest);
            pass.bindTexture("CoarseHeight", transition == null ? fallback : transition.height(), nearest);
            pass.bindTexture("DetailCoverage", transition == null ? fallback : transition.coverage(), nearest);
            pass.draw(3, 1, 0, 0);
        }
    }

    private NativeMapPackDevice() {}
}
