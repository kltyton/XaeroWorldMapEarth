package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Lets Iris compute its existing biome uniforms at the map focus while Earth draws. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.BiomeUniforms", remap = false)
abstract class MapPackBiomeUniformsMixin {
    @ModifyExpressionValue(method = {"lambda$addBiomeUniforms$0", "lambda$addBiomeUniforms$1",
            "lambda$addBiomeUniforms$2", "lambda$addBiomeUniforms$3", "lambda$addBiomeUniforms$4"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;blockPosition()Lnet/minecraft/core/BlockPos;",
                    remap = true), require = 0)
    private static BlockPos earth$biomePosition(BlockPos original) {
        var eye = MapPackTarget.eye();
        return eye == null || eye.biomePosition() == null ? original : eye.biomePosition();
    }

    @WrapOperation(method = {"lambda$addBiomeUniforms$0", "lambda$addBiomeUniforms$1", "lambda$addBiomeUniforms$2",
            "lambda$addBiomeUniforms$3", "lambda$addBiomeUniforms$4"}, at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;",
                    remap = true), require = 0)
    private static Holder<Biome> earth$useCachedBiome(Level level, BlockPos position, Operation<Holder<Biome>> original) {
        var eye = MapPackTarget.eye();
        return eye == null || eye.cachedBiome() == null ? original.call(level, position) : eye.cachedBiome();
    }
}
