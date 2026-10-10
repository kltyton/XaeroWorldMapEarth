package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.render.entity.MapEntityLayer;
import net.minecraft.client.renderer.entity.MobRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobRenderer.class)
abstract class MapEntityLeashMixin {
    @Inject(method = "renderLeash", at = @At("HEAD"), cancellable = true)
    private void earth$mapPass(CallbackInfo callback) {
        if (MapEntityLayer.isDrawing()) callback.cancel();
    }
}
