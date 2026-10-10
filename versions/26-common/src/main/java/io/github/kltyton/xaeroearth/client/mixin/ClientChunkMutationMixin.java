package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.EarthClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class ClientChunkMutationMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void earth$changed(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() == null) return;
        LevelChunk chunk = (LevelChunk) (Object) this;
        EarthClient.changedChunk(chunk.getLevel(), Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
    }
}
