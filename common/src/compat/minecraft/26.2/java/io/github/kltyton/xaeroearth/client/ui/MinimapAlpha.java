package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Preserves Xaero's background opacity and treats covered terrain pixels as opaque. */
final class MinimapAlpha implements AutoCloseable {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("xaeroearth", "core/minimap_alpha");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "minimap_alpha"))
            .withVertexShader(SHADER).withFragmentShader(SHADER).withCull(false)
            .withShaderDefine("EARTH_FAR_DEPTH", 0.0f)
            .withDepthStencilState(new DepthStencilState(CompareOp.NOT_EQUAL, false))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 8)).build();

    void background(RenderTarget target, int percent) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
                target.getColorTexture(), new Vector4f(0, 0, 0, percent / 100f));
    }

    void resolve(RenderTarget target, NativeTerrainScene scene) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Earth minimap alpha", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            pass.draw(3, 1, 0, 0);
        }
    }

    @Override public void close() {
        // The native device owns the compiled pipeline.
    }
}
