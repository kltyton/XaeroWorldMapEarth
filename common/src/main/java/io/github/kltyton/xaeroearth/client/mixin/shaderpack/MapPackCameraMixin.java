package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies camera position to Iris's world-space uniforms while drawing the map. */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.uniforms.CameraUniforms", "net.coderbot.iris.uniforms.CameraUniforms"}, remap = false)
abstract class MapPackCameraMixin {
    @Unique private static MethodHandle earth$legacyCamera;

    @Inject(method = "getRenderDistanceInBlocks()I", at = @At("RETURN"), cancellable = true, require = 0)
    private static void earth$mapRenderDistance(CallbackInfoReturnable<Integer> result) {
        int mapDistance = MapPackTarget.renderDistance();
        if (mapDistance > result.getReturnValueI()) result.setReturnValue(mapDistance);
    }

    @Inject(method = "getUnshiftedCameraPosition()Lorg/joml/Vector3d;", at = @At("HEAD"), cancellable = true, require = 0)
    private static void earth$shaderCamera(CallbackInfoReturnable<Vector3d> result) {
        var camera = MapPackTarget.camera();
        if (camera != null) result.setReturnValue(camera);
    }

    @Inject(method = "getUnshiftedCameraPosition()Lnet/coderbot/iris/vendored/joml/Vector3d;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earth$legacyShaderCamera(CallbackInfoReturnable<Object> result) throws Throwable {
        var camera = MapPackTarget.camera();
        if (camera == null) return;
        if (earth$legacyCamera == null) {
            var type = Class.forName("net.coderbot.iris.vendored.joml.Vector3d");
            earth$legacyCamera = MethodHandles.publicLookup().unreflectConstructor(
                    type.getConstructor(double.class, double.class, double.class));
        }
        result.setReturnValue(earth$legacyCamera.invoke(camera.x, camera.y, camera.z));
    }
}
