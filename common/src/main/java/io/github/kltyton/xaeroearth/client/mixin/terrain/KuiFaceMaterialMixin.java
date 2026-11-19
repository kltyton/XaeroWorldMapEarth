package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.kltyton.kltytonui.chunkmap.KuiChunkMeshBaker;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.QuadCapture;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Carries the snapshot's block material into the quad stream without modifying KUI model rendering. */
@Mixin(value = KuiChunkMeshBaker.class, remap = false)
abstract class KuiFaceMaterialMixin {
    @Inject(method = "bake", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lio/github/kltyton/kltytonui/chunkmap/KuiChunkMeshBaker$QuadConsumer;offset(FFF)V"))
    private void earth$blockMaterial(CallbackInfoReturnable<?> result, @Local BlockState state) {
        QuadCapture.material(state, false);
    }

    @Inject(method = "bake", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lio/github/kltyton/kltytonui/chunkmap/KuiChunkMeshBaker$QuadConsumer;offset(FFF)V"))
    private void earth$fluidMaterial(CallbackInfoReturnable<?> result, @Local BlockState state) {
        QuadCapture.material(state, true);
    }
}
