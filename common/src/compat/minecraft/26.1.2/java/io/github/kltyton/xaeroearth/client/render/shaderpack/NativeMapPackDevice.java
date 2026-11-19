package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.OptionalInt;
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
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;

import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/** Minecraft 26.1.2 attachment and binding layout for the Iris map pass. */
final class NativeMapPackDevice {
    static RenderPipeline SOLID, OPAQUE, TRANSLUCENT, SHADOW_OPAQUE, SHADOW_TRANSLUCENT;
    private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "map_pack_composite"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/map_pack_composite"))
            .withShaderDefine("EARTH_REVERSE_DEPTH", 0)
            .withSampler("PackColor").withSampler("PackDepth")
            .withSampler("CoarseColor").withSampler("CoarseHeight").withSampler("DetailCoverage")
            .withUniform("PackRaster", UniformType.UNIFORM_BUFFER)
            .withCull(false).withDepthStencilState(DepthStencilState.DEFAULT)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();

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
        var names = List.of("Position", "Color", "UV0", "UV2", "Normal", "mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock");
        int[] offsets = {0, 12, 16, 24, 28, 32, 36, 44, 48};
        if (format.getVertexSize() != 52 || !format.getElementAttributeNames().equals(names)) return false;
        var elements = format.getElements();
        for (int i = 0; i < offsets.length; i++) if (format.getOffset(elements.get(i)) != offsets[i]) return false;
        if (elements.get(0) != VertexFormatElement.POSITION || elements.get(1) != VertexFormatElement.COLOR
                || elements.get(2) != VertexFormatElement.UV0 || elements.get(3) != VertexFormatElement.UV2
                || elements.get(4) != VertexFormatElement.NORMAL) return false;
        return element(elements.get(5), VertexFormatElement.Type.SHORT, false, 2)
                && element(elements.get(6), VertexFormatElement.Type.FLOAT, false, 2)
                && element(elements.get(7), VertexFormatElement.Type.BYTE, true, 4)
                && element(elements.get(8), VertexFormatElement.Type.BYTE, false, 4);
    }

    private static boolean element(VertexFormatElement value, VertexFormatElement.Type type, boolean normalized, int count) {
        return value.type() == type && value.normalized() == normalized && value.count() == count;
    }

    private static RenderPipeline terrain(VertexFormat format, String name, boolean transparent, boolean shadow) {
        var shader = Identifier.fromNamespaceAndPath("kltytonui", "core/native_map");
        return RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", name))
                .withVertexShader(shader).withFragmentShader(shader)
                .withUniform("MapParams", UniformType.UNIFORM_BUFFER)
                .withUniform("EarthMaskParams", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0").withSampler("Sampler2")
                .withVertexFormat(format, VertexFormat.Mode.TRIANGLES)
                .withCull(!shadow).withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(new ColorTargetState(transparent ? Optional.of(BlendFunction.TRANSLUCENT)
                        : Optional.empty(), 15)).build();
    }

    static TextureTarget target(RenderTarget destination, int width, int height) {
        return new TextureTarget("Earth Iris map", width, height, true);
    }

    static Camera camera(Minecraft minecraft) { return minecraft.gameRenderer.getMainCamera(); }
    static boolean zeroToOne() { return RenderSystem.getDevice().isZZeroToOne(); }

    static boolean reverseDepth() { return false; }

    static Matrix4f packUniformProjection(Matrix4f rasterProjection) {
        return zeroToOne() ? new Matrix4f().m22(2F).m32(-1F).mul(rasterProjection) : rasterProjection;
    }

    static Matrix4f materialProjection(Matrix4f raster) {
        return zeroToOne() ? new Matrix4f().m22(0.5F).m32(0.5F).mul(raster) : raster;
    }

    static Matrix4f shadowUniformProjection(Matrix4f raster) { return raster; }

    static int glHandle(GlBuffer buffer) {
        return ((io.github.kltyton.xaeroearth.client.mixin.shaderpack.NativePackGlBufferAccess) (Object) buffer).earth$glHandle();
    }

    static void clear(RenderTarget target) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Clear Earth Iris map", target.getColorTextureView(), OptionalInt.of(0),
                target.getDepthTextureView(), OptionalDouble.of(1))) { }
    }

    static void composite(RenderTarget input, RenderTarget destination, GpuBufferSlice mapping,
                          MapPackLodTransition<GpuTextureView> transition) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Composite Earth Iris map", destination.getColorTextureView(), OptionalInt.empty(),
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
            pass.draw(0, 3);
        }
    }

    private NativeMapPackDevice() {}
}
