package io.github.kltyton.xaeroearth.client.mixin.terrain;

import io.github.kltyton.xaeroearth.client.integration.create.ContraptionModelScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes model lookups on a detached KUI contraption view see the matching block entity. */
@Mixin(targets = "io.github.kltyton.kltytonui.chunkmap.KuiChunkMeshBaker$SnapshotView", remap = false)
abstract class ContraptionSnapshotEntitiesMixin {
    @Inject(method = {"getBlockEntity", "m_7702_", "method_8321"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void earth$contraptionEntity(BlockPos position, CallbackInfoReturnable<BlockEntity> result) {
        var blockEntity = ContraptionModelScope.entityAt(position);
        if (blockEntity != null) result.setReturnValue(blockEntity);
    }
}
