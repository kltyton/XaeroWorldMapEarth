package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.resources.Identifier;

/** Preserves Xaero's background opacity and treats covered terrain pixels as opaque. */
final class MinimapAlpha implements AutoCloseable {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("xaeroearth", "core/minimap_alpha");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "minimap_alpha"))
            .withVertexShader(SHADER).withFragmentShader(SHADER).withCull(false)
            .withShaderDefine("EARTH_FAR_DEPTH", 1.0f)
            .withDepthStencilState(new DepthStencilState(CompareOp.NOT_EQUAL, false))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), 8)).build();

    void background(RenderTarget target, int percent) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
                target.getColorTexture(), Math.round(percent * 255f / 100f) << 24);
    }

    void resolve(RenderTarget target, NativeTerrainScene scene) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Earth minimap alpha", target.getColorTextureView(), OptionalInt.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            pass.draw(0, 3);
        }
    }

    @Override public void close() {
        // The native device owns the compiled pipeline.
    }
}
