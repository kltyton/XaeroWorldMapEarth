package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.EarthClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class ScreenTrackingMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void earth$opening(Screen next, CallbackInfo callback) {
        EarthClient.screenOpening(((Minecraft) (Object) this).screen, next);
    }
}
