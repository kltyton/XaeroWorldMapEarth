package io.github.kltyton.xaeroearth.client.navigation;

import net.minecraft.world.entity.Entity;

final class NavigationMovementPlatform {
    static float stepHeight(Entity mover) { return mover.maxUpStep; }

    static boolean onGround(Entity mover) { return mover.isOnGround(); }

    private NavigationMovementPlatform() {}
}
