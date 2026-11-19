package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps older Oculus's projection and inverse projection aligned with the map shadow texture. */
@Pseudo
@Mixin(targets = "net.coderbot.iris.uniforms.MatrixUniforms", remap = false)
abstract class LegacyMapShadowUniformsMixin {
    @Inject(method = "lambda$addMatrixUniforms$1(Lnet/coderbot/iris/shaderpack/PackDirectives;)[F",
            at = @At("HEAD"), cancellable = true)
    private static void earth$shadowProjection(CallbackInfoReturnable<float[]> callback) {
        var matrix = MapPackTarget.shadowProjection();
        if (matrix != null) callback.setReturnValue(matrix.get(new float[16]));
    }
}
