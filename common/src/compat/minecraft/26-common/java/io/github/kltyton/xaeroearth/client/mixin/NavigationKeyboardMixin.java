package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.navigation.PlayerNavigation;
import io.github.kltyton.xaeroearth.client.ClientScreen;
import net.minecraft.client.KeyboardHandler;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes navigation cancellation gestures before Minecraft updates movement keys. */
@Mixin(KeyboardHandler.class)
public abstract class NavigationKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void earth$navigationInput(long window, int action, KeyEvent event, CallbackInfo callback) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS && PlayerNavigation.cancelWithEscape()) {
            callback.cancel();
            return;
        }
        if (ClientScreen.screen(minecraft) != null || !PlayerNavigation.active()) return;
        var keys = minecraft.options;
        if (action == GLFW.GLFW_PRESS && keys.keySprint.matches(event)
                && PlayerNavigation.requestSprint()) {
            callback.cancel();
            return;
        }
        int movement = keys.keyUp.matches(event) ? PlayerNavigation.FORWARD_KEY
                : keys.keyLeft.matches(event) ? PlayerNavigation.LEFT_KEY
                : keys.keyDown.matches(event) ? PlayerNavigation.BACK_KEY
                : keys.keyRight.matches(event) ? PlayerNavigation.RIGHT_KEY : -1;
        if (movement >= 0 && PlayerNavigation.manualMovement(movement, action)) callback.cancel();
    }
}
