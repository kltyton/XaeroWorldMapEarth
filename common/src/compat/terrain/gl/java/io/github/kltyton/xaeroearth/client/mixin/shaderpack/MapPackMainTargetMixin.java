package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MapPackMainTargetMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void earth$mapTarget(CallbackInfoReturnable<RenderTarget> result) {
        var target = MapPackTarget.current();
        if (target != null) result.setReturnValue(target);
    }
}
