package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"net.coderbot.iris.uniforms.CommonUniforms", "net.irisshaders.iris.uniforms.CommonUniforms"}, remap = false)
abstract class MapPackEyeMixin {
    @Unique private static MethodHandle earth$legacyLight;

    @Inject(method = "isEyeInWater()I", at = @At("HEAD"), cancellable = true)
    private static void earth$medium(CallbackInfoReturnable<Integer> result) {
        var eye = MapPackTarget.eye();
        if (eye != null) result.setReturnValue(eye.medium());
    }

    @Inject(method = "getEyeBrightness()Lorg/joml/Vector2i;", at = @At("HEAD"), cancellable = true, require = 0)
    private static void earth$light(CallbackInfoReturnable<Vector2i> result) {
        var eye = MapPackTarget.eye();
        if (eye != null) result.setReturnValue(new Vector2i(eye.blockLight(), eye.skyLight()));
    }

    @Inject(method = "getEyeBrightness()Lnet/coderbot/iris/vendored/joml/Vector2i;", at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void earth$legacyLight(CallbackInfoReturnable<Object> result) throws Throwable {
        var eye = MapPackTarget.eye();
        if (eye == null) return;
        if (earth$legacyLight == null) {
            var type = Class.forName("net.coderbot.iris.vendored.joml.Vector2i");
            earth$legacyLight = MethodHandles.publicLookup().unreflectConstructor(type.getConstructor(int.class, int.class));
        }
        result.setReturnValue(earth$legacyLight.invoke(eye.blockLight(), eye.skyLight()));
    }
}
