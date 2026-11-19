package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses the pack's own attachment clear passes when previous map pixels have no valid camera. */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.targets.RenderTargets", "net.coderbot.iris.rendertarget.RenderTargets"}, remap = false)
abstract class MapPackTemporalTargetsMixin {
    @Inject(method = "isFullClearRequired()Z", at = @At("RETURN"), cancellable = true)
    private void earth$resetHistory(CallbackInfoReturnable<Boolean> result) {
        if (MapPackTarget.clearTemporalTargets()) result.setReturnValue(true);
    }
}
