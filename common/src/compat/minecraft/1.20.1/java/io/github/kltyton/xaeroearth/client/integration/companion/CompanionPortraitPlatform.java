package io.github.kltyton.xaeroearth.client.integration.companion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;

final class CompanionPortraitPlatform {
    static void draw(GuiGraphics graphics, LivingEntity entity, int x, int y, int size, int modelSize) {
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x, y + size + modelSize / 2, modelSize, 0, 0, entity);
    }

    private CompanionPortraitPlatform() {}
}
