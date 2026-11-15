package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.navigation.PlayerNavigation;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/** Uses interpolated steering yaw for the camera while leaving the entity's tick rotation authoritative. */
@Mixin(Camera.class)
public abstract class NavigationCameraMixin {

    @ModifyExpressionValue(method = "setup",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float earth$interpolateRouteYaw(float yaw, BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick) {
        return PlayerNavigation.cameraYaw(entity, partialTick, yaw);
    }

    @ModifyExpressionValue(method = "setup",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float earth$interpolateRoutePitch(float pitch, BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick) {
        return PlayerNavigation.cameraPitch(entity, partialTick, pitch);
    }
}
