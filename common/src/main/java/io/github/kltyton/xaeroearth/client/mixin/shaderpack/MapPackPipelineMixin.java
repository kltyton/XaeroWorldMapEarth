package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps nested Iris pipeline lookups on the independent map instance during its pass. */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.pipeline.PipelineManager", "net.coderbot.iris.pipeline.PipelineManager"}, remap = false)
abstract class MapPackPipelineMixin {
    @Inject(method = "getPipelineNullable", at = @At("HEAD"), cancellable = true, require = 0)
    private void earth$pipeline(CallbackInfoReturnable<Object> result) {
        var pipeline = MapPackTarget.pipeline();
        if (pipeline != null) result.setReturnValue(pipeline);
    }

    @Inject(method = "getPipeline()Ljava/util/Optional;", at = @At("HEAD"), cancellable = true, require = 0)
    private void earth$optionalPipeline(CallbackInfoReturnable<Optional<?>> result) {
        var pipeline = MapPackTarget.pipeline();
        if (pipeline != null) result.setReturnValue(Optional.of(pipeline));
    }
}
