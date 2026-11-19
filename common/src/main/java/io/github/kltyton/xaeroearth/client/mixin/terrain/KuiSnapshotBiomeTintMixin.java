package io.github.kltyton.xaeroearth.client.mixin.terrain;

import io.github.kltyton.xaeroearth.client.chunkmap.biome.BiomeTintScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Use the detached world's height-aware biome colors while Earth bakes detailed tiles. */
@Mixin(targets = "io.github.kltyton.kltytonui.chunkmap.KuiChunkMeshBaker$SnapshotView", remap = false)
abstract class KuiSnapshotBiomeTintMixin {
    @Inject(method = {"getBlockTint", "m_6171_", "method_23752"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void earth$biomeTint(BlockPos pos, ColorResolver resolver, CallbackInfoReturnable<Integer> result) {
        var scope = BiomeTintScope.current();
        if (scope != null) result.setReturnValue(scope.color(pos, resolver));
    }
}
