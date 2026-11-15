package io.github.kltyton.xaeroearth.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.kltyton.xaeroearth.client.render.navigation.NavigationRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws after all world passes, with the exact projection used for terrain depth. */
@Mixin(GameRenderer.class)
public abstract class NavigationWorldRenderMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
    private void earth$drawNavigation(CallbackInfo ci, @Local(index = 8) CameraRenderState camera,
                                     @Local(index = 10) Matrix4f projection) {
        NavigationRenderer.draw(camera.viewRotationMatrix, projection, camera.pos);
    }
}
