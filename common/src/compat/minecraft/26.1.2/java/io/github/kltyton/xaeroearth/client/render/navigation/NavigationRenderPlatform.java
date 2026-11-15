package io.github.kltyton.xaeroearth.client.render.navigation;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

final class NavigationRenderPlatform {
    static final boolean REVERSE_DEPTH = false;
    static final RenderPipeline SCAN_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "terrain_scan"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/terrain_scan"))
            .withSampler("SceneColor").withSampler("SceneDepth")
            .withUniform("TerrainScanParameters", UniformType.UNIFORM_BUFFER)
            .withCull(false).withDepthStencilState(Optional.empty())
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("xaeroearth", "core/navigation_trace");
    static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withUniform("NavigationParameters", UniformType.UNIFORM_BUFFER)
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "navigation_trace"))
            .withVertexShader(SHADER).withFragmentShader(SHADER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();

    static RenderPass open(RenderTarget target, CommandEncoder encoder) {
        return encoder.createRenderPass(() -> "Earth navigation trace", target.getColorTextureView(),
                OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty());
    }

    static RenderTarget mainTarget(Minecraft minecraft) { return minecraft.getMainRenderTarget(); }

    static TextureTarget scanTarget(RenderTarget scene) {
        return new TextureTarget("Earth terrain scan", scene.width, scene.height, false);
    }

    static RenderPass scanPass(RenderTarget target, CommandEncoder encoder) {
        return encoder.createRenderPass(() -> "Earth terrain scan", target.getColorTextureView(), OptionalInt.empty());
    }

    static boolean zeroToOne() { return RenderSystem.getDevice().isZZeroToOne(); }

    static void screenTriangle(RenderPass pass) { pass.draw(0, 3); }

    static void draw(RenderPass pass, GpuBuffer vertices, int count) {
        pass.setVertexBuffer(0, vertices);
        pass.draw(0, count);
    }

    private NavigationRenderPlatform() {}
}
