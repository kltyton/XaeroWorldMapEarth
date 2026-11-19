package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.render.shaderpack.NativeMapShaderPackRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Selects the Iris-registered pipeline only in the independent map pass. */
@Mixin(value = KuiNativeTerrainRenderer.class, remap = false)
abstract class NativePackTerrainMixin {
    @WrapOperation(method = "drawMeshes", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void earth$packPipeline(RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
        var mapped = NativeMapShaderPackRenderer.terrainPipeline();
        original.call(pass, mapped == null ? pipeline : mapped);
        if (mapped != null) NativeMapShaderPackRenderer.bindLightmap(pass);
    }
}
