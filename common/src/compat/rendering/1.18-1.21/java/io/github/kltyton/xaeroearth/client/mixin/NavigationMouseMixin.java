package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.navigation.PlayerNavigation;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps mouse input from changing the camera while automatic navigation is active. */
@Mixin(MouseHandler.class)
public abstract class NavigationMouseMixin {
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void earth$cancelOnInput(long window, double x, double y, CallbackInfo callback) {
        if (!PlayerNavigation.active()) return;
        xpos = x;
        ypos = y;
        callback.cancel();
    }
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void earth$cancelOnScroll(CallbackInfo callback) {
        if (PlayerNavigation.active()) callback.cancel();
    }
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void earth$cancelOnClick(long window, int button, int action, int modifiers, CallbackInfo callback) {
        if (PlayerNavigation.active()) callback.cancel();
    }
}
