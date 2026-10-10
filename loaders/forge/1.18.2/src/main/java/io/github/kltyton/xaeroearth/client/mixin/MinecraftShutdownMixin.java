package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.EarthClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftShutdownMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void earth$closeMapResources(CallbackInfo callback) {
        EarthClient.onStopping();
    }
}
