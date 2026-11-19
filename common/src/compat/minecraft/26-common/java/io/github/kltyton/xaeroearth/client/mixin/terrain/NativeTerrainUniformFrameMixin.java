package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeUniformFrameScope;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedDrawCoverage;
import net.minecraft.client.renderer.DynamicUniformStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps KUI's uniform frame and framebuffer clip scoped to the Earth terrain submission. */
@Mixin(value = KuiNativeTerrainRenderer.class, remap = false)
abstract class NativeTerrainUniformFrameMixin {
    @WrapOperation(method = "drawMeshes", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DynamicUniformStorage;endFrame()V"))
    private void earth$uniformFrame(DynamicUniformStorage<?> storage, Operation<Void> original) {
        if (NativeUniformFrameScope.rotate(storage)) original.call(storage);
    }

    @WrapOperation(method = "drawMeshes", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void earth$clipToMissingImage(RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
        original.call(pass, pipeline);
        var clip = ProjectedDrawCoverage.currentClip();
        if (clip != null) pass.enableScissor(clip.x(), clip.y(), clip.width(), clip.height());
    }
}
