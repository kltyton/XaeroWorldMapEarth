package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Limits the replacement main target to the independent Iris map render. */
@Mixin(GameRenderer.class)
abstract class MapPackMainTargetMixin {
    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void earth$mapTarget(CallbackInfoReturnable<RenderTarget> result) {
        var target = MapPackTarget.current();
        if (target != null) result.setReturnValue(target);
    }
}
