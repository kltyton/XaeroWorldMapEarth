package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.IndexedDetailedMesh;
import io.github.kltyton.xaeroearth.client.render.shaderpack.NativeMapShaderPackRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Uses native indexed assembly for Earth packets without replacing KUI's render passes. */
@Mixin(value = KuiNativeTerrainRenderer.class, remap = false)
abstract class IndexedTerrainDrawMixin {
    @WrapOperation(method = "drawMeshes", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setVertexBuffer(ILcom/mojang/blaze3d/buffers/GpuBuffer;)V"))
    private void earth$terrainLighting(RenderPass pass, int slot, GpuBuffer buffer,
                                       Operation<Void> original, @Local KuiNativeTerrainRenderer.GpuMesh mesh) {
        boolean indexed = ((IndexedDetailedMesh) (Object) mesh).earth$indexed();
        var converted = NativeMapShaderPackRenderer.terrainVertexBuffer(buffer, indexed);
        original.call(pass, slot, converted);
    }

    @WrapOperation(method = "drawMeshes", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;draw(II)V"))
    private void earth$draw(RenderPass pass, int first, int count,
                           Operation<Void> original, @Local KuiNativeTerrainRenderer.GpuMesh mesh,
                           @Local KuiNativeTerrainRenderer.Draw draw) {
        NativeMapShaderPackRenderer.bindChunkMask(pass, draw);
        if (!((IndexedDetailedMesh) (Object) mesh).earth$indexed()) {
            original.call(pass, first, count);
            return;
        }
        var indices = RenderSystem.getSequentialBuffer(Mode.QUADS);
        var buffer = indices.getBuffer(first + count);
        pass.setIndexBuffer(buffer, indices.type());
        pass.drawIndexed(0, first, count, 1);
    }
}
