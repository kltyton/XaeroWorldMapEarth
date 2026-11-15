package io.github.kltyton.xaeroearth.client.integration.companion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;

final class CompanionPortraitPlatform {
    static void draw(GuiGraphics graphics, LivingEntity entity, int x, int y, int size, int modelSize) {
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x - size, y - size, x + size, y + size, modelSize, 0.0625F, x, y, entity);
    }

    private CompanionPortraitPlatform() {}
}
