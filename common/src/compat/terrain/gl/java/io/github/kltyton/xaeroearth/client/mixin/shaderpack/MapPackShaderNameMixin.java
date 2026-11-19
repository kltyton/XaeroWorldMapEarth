package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Gives map programs their own vanilla Program.Type stage-cache keys and lifetime. */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.pipeline.programs.ShaderCreator",
        "net.coderbot.iris.pipeline.newshader.NewShaderTests"}, remap = false)
abstract class MapPackShaderNameMixin {
    @ModifyVariable(method = {"create", "createFallback"}, at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0, remap = false)
    private static String earth$isolatedStageNames(String name) {
        return MapPackTarget.legacyShaderName(name);
    }
}
