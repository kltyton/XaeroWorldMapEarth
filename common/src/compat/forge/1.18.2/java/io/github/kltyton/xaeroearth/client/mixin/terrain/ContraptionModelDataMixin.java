package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.kltyton.kltytonui.chunkmap.KuiChunkMeshBaker;
import io.github.kltyton.xaeroearth.client.integration.create.ContraptionModelScope;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.model.data.IModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Supplies the Forge 1.18.2 model input of the detached block entity. */
@Mixin(value = KuiChunkMeshBaker.class, remap = false)
abstract class ContraptionModelDataMixin {
    @ModifyArg(method = "bake", index = 10, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock("
                    + "Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                    + "ZLjava/util/Random;JILnet/minecraftforge/client/model/data/IModelData;)Z"))
    private IModelData earth$modelData(IModelData original, @Local BlockPos.MutableBlockPos position) {
        var block = ContraptionModelScope.entityAt(position);
        return block == null ? original : block.getModelData();
    }
}
