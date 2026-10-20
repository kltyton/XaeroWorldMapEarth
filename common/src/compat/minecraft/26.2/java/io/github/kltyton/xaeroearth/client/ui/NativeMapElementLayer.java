package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Keeps Xaero's two-dimensional elements above the reverse-depth terrain scene. */
public final class NativeMapElementLayer {
    private static final RenderPipeline COMPOSITE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", "pipeline/map_elements"))
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    private static TextureTarget layer;
    private static GpuTextureView previousColor, previousDepth;
    private static boolean pending;

    private NativeMapElementLayer() { }

    public static void begin(RenderTarget target) {
        if (layer == null || layer.width != target.width || layer.height != target.height) {
            close();
            layer = new TextureTarget("Earth map elements", target.width, target.height, true, GpuFormat.RGBA8_UNORM);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                layer.getColorTexture(), new Vector4f(0, 0, 0, 0), layer.getDepthTexture(), 0);
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
                () -> "Earth map elements", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(COMPOSITE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", layer.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    public static void close() {
        pending = false;
        if (layer != null) { layer.destroyBuffers(); layer = null; }
    }
}
