package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.PrimitiveTopology;
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
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setVertexBuffer(ILcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void earth$terrainLighting(RenderPass pass, int slot, GpuBufferSlice buffer,
                                       Operation<Void> original, @Local KuiNativeTerrainRenderer.GpuMesh mesh) {
        boolean indexed = ((IndexedDetailedMesh) (Object) mesh).earth$indexed();
        var converted = NativeMapShaderPackRenderer.terrainVertexBuffer(buffer.buffer(), indexed);
        if (converted == buffer.buffer()) {
            original.call(pass, slot, buffer);
            return;
        }
        if (buffer.offset() % 32 != 0 || buffer.length() % 32 != 0)
            throw new IllegalStateException("Iris terrain vertex slice is not aligned to the KUI format");
        original.call(pass, slot, converted.slice(buffer.offset() / 32 * 52, buffer.length() / 32 * 52));
    }

    @WrapOperation(method = "drawMeshes", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;draw(IIII)V"))
    private void earth$draw(RenderPass pass, int count, int instances, int first, int firstInstance,
                           Operation<Void> original, @Local KuiNativeTerrainRenderer.GpuMesh mesh,
                           @Local KuiNativeTerrainRenderer.Draw draw) {
        NativeMapShaderPackRenderer.bindChunkMask(pass, draw);
        if (!((IndexedDetailedMesh) (Object) mesh).earth$indexed()) {
            original.call(pass, count, instances, first, firstInstance);
            return;
        }
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        var buffer = indices.getBuffer(first + count);
        pass.setIndexBuffer(buffer, indices.type());
        pass.drawIndexed(count, instances, first, 0, firstInstance);
    }
}
