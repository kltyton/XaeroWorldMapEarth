package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The active world pipeline already installed the selected pack's global block material IDs. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class NativePackPipelineInitMixin {
    @Shadow private boolean initializedBlockIds;

    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void earth$useActivePackMaterials(CallbackInfo callback) {
        if (MapPackTarget.pipeline() == (Object) this) initializedBlockIds = true;
    }
}
