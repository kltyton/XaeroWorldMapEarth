package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.navigation.PlayerNavigation;
import io.github.kltyton.xaeroearth.client.ClientScreen;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes navigation cancellation gestures before Minecraft updates movement keys. */
@Mixin(KeyboardHandler.class)
public abstract class NavigationKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void earth$navigationInput(long window, int key, int scanCode, int action, int modifiers, CallbackInfo callback) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS && ClientScreen.screen(minecraft) == null
                && minecraft.options.keySprint.matches(key, scanCode) && PlayerNavigation.requestSprint()) {
            callback.cancel();
            return;
        }
        if (PlayerNavigation.manualKey(key, action)) callback.cancel();
    }
}
