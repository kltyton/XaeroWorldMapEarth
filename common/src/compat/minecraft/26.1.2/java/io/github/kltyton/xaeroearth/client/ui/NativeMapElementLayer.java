package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.resources.Identifier;

/** Composites Xaero's original two-dimensional elements after translucent terrain. */
public final class NativeMapElementLayer {
    private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "pipeline/map_elements"))
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withSampler("InSampler")
            .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), 15))
            .withDepthStencilState(Optional.empty())
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withCull(false)
            .build();
    private static TextureTarget layer;
    private static GpuTextureView previousColor, previousDepth;
    private static boolean pending;

    private NativeMapElementLayer() { }

    public static void begin(RenderTarget target) {
        if (layer == null || layer.width != target.width || layer.height != target.height) {
            close();
            layer = new TextureTarget("Earth map elements", target.width, target.height, true);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                layer.getColorTexture(), 0, layer.getDepthTexture(), 1);
        pending = true;
        resume();
    }

    public static boolean pending() { return pending; }

    public static void resume() {
        previousColor = RenderSystem.outputColorTextureOverride;
        previousDepth = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = layer.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = layer.getDepthTextureView();
    }

    public static void end() {
        RenderSystem.outputColorTextureOverride = previousColor;
        RenderSystem.outputDepthTextureOverride = previousDepth;
        previousColor = previousDepth = null;
    }

    public static void composite(RenderTarget target) {
        if (!pending) return;
        pending = false;
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Earth map elements", target.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(COMPOSITE);
            pass.bindTexture("InSampler", layer.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        }
    }

    public static void close() {
        pending = false;
        if (layer != null) { layer.destroyBuffers(); layer = null; }
    }
}
