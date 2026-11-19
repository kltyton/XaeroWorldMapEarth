package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"net.coderbot.iris.gl.IrisRenderSystem", "net.irisshaders.iris.gl.IrisRenderSystem"}, remap = false)
abstract class MapPackImageBindingMixin {
    @Inject(method = "bindImageTexture(IIIZIII)V", at = @At("HEAD"))
    private static void earth$rememberImageUnit(int unit, int texture, int level, boolean layered,
                                                int layer, int access, int format, CallbackInfo callback) {
        MapPackTarget.beforeImageBinding(unit);
    }
}
