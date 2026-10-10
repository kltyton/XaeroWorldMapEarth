package io.github.kltyton.xaeroearth.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps chat filtering and lazy font uploads on the client thread during server shutdown. */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerChatRefreshMixin {
    @WrapOperation(method = "updatePermissionAndChatAbilities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;refreshChatAbilities()V"))
    private void earth$refreshChat(LocalPlayer player, Operation<Void> original) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            original.call(player);
        } else {
            minecraft.execute(() -> {
                if (minecraft.player == player) original.call(player);
            });
        }
    }
}
