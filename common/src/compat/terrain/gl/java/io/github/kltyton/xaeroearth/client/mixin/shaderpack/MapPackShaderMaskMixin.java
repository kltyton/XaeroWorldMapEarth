package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackFragmentMask;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackProjectionCompat;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies chunk clipping only to shaders built for the isolated map pipeline. */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.pipeline.transform.TransformPatcher",
        "net.coderbot.iris.pipeline.transform.TransformPatcher"}, remap = false)
abstract class MapPackShaderMaskMixin {
    @Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void earth$maskMapFragments(CallbackInfoReturnable<Map<?, String>> result) {
        result.setReturnValue(MapPackFragmentMask.install(MapPackProjectionCompat.install(result.getReturnValue())));
    }

    @Inject(method = "patchComposite", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void earth$mapCompositeProjection(CallbackInfoReturnable<Map<?, String>> result) {
        result.setReturnValue(MapPackProjectionCompat.install(result.getReturnValue()));
    }

    @Inject(method = "patchCompute", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void earth$mapComputeProjection(CallbackInfoReturnable<String> result) {
        result.setReturnValue(MapPackProjectionCompat.install(result.getReturnValue()));
    }
}
