package io.github.kltyton.xaeroearth.client.render.map;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

final class MapFogPlatform {
    private static final Identifier EDGE_SHADER = Identifier.fromNamespaceAndPath("xaeroearth", "core/map_boundary");
    static final RenderPipeline HIGHLIGHT = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "map_highlight"))
            .withVertexShader(EDGE_SHADER)
            .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/map_highlight")).withCull(false)
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("TerrainDepth").withSampler("HighlightField")
                    .withUniform("MapHighlightParameters", UniformType.UNIFORM_BUFFER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
    static final RenderPipeline BOUNDARY = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "map_boundary"))
            .withVertexShader(EDGE_SHADER).withFragmentShader(EDGE_SHADER).withCull(false)
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("TerrainDepth").withSampler("BoundaryField")
                    .withUniform("MapBoundaryParameters", UniformType.UNIFORM_BUFFER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();

    static boolean zeroToOne() { return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(); }
    static float clearDepth() { return 0; }
    static boolean packedHeight() { return false; }

    static RenderPass openBoundary(RenderTarget target) {
        return openColor(target, "Earth explored boundary");
    }

    static RenderPass openColor(RenderTarget target, String name) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> name,
                target.getColorTextureView(), Optional.empty());
    }

    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("xaeroearth", "core/map_fog");
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "map_fog"))
            .withVertexShader(SHADER).withFragmentShader(SHADER)
            .withDepthStencilState(new DepthStencilState(CompareOp.EQUAL, false))
            .withCull(false)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("MapFogParameters", UniformType.UNIFORM_BUFFER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();

    static float farPlane() { return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 0 : -1; }

    static RenderPass open(RenderTarget target) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Earth unexplored map fog",
                target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty());
    }

    static void draw(RenderPass pass) { pass.draw(3, 1, 0, 0); }
    private MapFogPlatform() { }
}
