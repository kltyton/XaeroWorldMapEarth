package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies Iris's shadow and inverse-shadow uniforms from the map's actual raster pass. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.MatrixUniforms", remap = false)
abstract class MapPackShadowUniformsMixin {
    @Inject(method = "lambda$addMatrixUniforms$0(Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;)Lorg/joml/Matrix4f;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earth$shadowViewLegacyGL(CallbackInfoReturnable<Matrix4f> callback) {
        var matrix = MapPackTarget.shadowView();
        if (matrix != null) callback.setReturnValue(matrix);
    }

    @Inject(method = "lambda$addMatrixUniforms$1(Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;)Lorg/joml/Matrix4f;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earth$shadowProjectionLegacyGL(CallbackInfoReturnable<Matrix4f> callback) {
        var matrix = MapPackTarget.shadowProjection();
        if (matrix != null) callback.setReturnValue(matrix);
    }

    @Inject(method = "lambda$addMatrixUniforms$0(Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;)Lorg/joml/Matrix4fc;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earth$shadowView(CallbackInfoReturnable<Matrix4fc> callback) {
        var matrix = MapPackTarget.shadowView();
        if (matrix != null) callback.setReturnValue(matrix);
    }

    @Inject(method = "lambda$addMatrixUniforms$1(Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;)Lorg/joml/Matrix4fc;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earth$shadowProjection(CallbackInfoReturnable<Matrix4fc> callback) {
        var matrix = MapPackTarget.shadowProjection();
        if (matrix != null) callback.setReturnValue(matrix);
    }
}
