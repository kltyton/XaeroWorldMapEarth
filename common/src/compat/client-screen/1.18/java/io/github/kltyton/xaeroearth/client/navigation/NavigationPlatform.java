package io.github.kltyton.xaeroearth.client.navigation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.TranslatableComponent;

final class NavigationPlatform {
    static Component text(String key) {
        return new TranslatableComponent(key);
    }

    static String translationKey(Component component) {
        return component instanceof TranslatableComponent translation ? translation.getKey() : "";
    }

    static void message(Minecraft minecraft, String key) {
        minecraft.gui.setOverlayMessage(text(key), false);
    }

    static boolean withinBorder(ClientLevel level, AABB bounds) {
        return level.getWorldBorder().isWithinBounds(bounds);
    }

    static void distance(Minecraft minecraft, boolean automatic, int blocks) {
        String key = automatic ? "xaeroearth.navigation.auto_distance" : "xaeroearth.navigation.guide_distance";
        overlay(minecraft, new TranslatableComponent(key, blocks));
    }

    static void clearDistance(Minecraft minecraft) {
        overlay(minecraft, net.minecraft.network.chat.TextComponent.EMPTY);
    }

    private static void overlay(Minecraft minecraft, Component message) {
        minecraft.gui.setOverlayMessage(message, false);
    }

    static void routeSprint(Minecraft minecraft, boolean sprinting) {
        if (minecraft.player != null) minecraft.player.setSprinting(sprinting);
    }

    static float stepHeight(net.minecraft.world.entity.Entity mover) { return mover.maxUpStep; }

    static boolean onGround(net.minecraft.world.entity.Entity mover) { return mover.isOnGround(); }

    static boolean useRocket(Minecraft minecraft, LocalPlayer player, InteractionHand hand) {
        return minecraft.gameMode.useItem(player, minecraft.level, hand).consumesAction();
    }

    private NavigationPlatform() {}
}
