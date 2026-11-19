package io.github.kltyton.xaeroearth.client.navigation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.entity.player.Inventory;

final class HotbarSelection {
    static int selected(Inventory inventory) { return inventory.selected; }
    static void select(Inventory inventory, int slot) { inventory.selected = slot; }

    static void swapWithInventory(Minecraft minecraft, LocalPlayer player, int inventorySlot, int hotbarSlot) {
        minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, inventorySlot, hotbarSlot,
                ClickType.SWAP, player);
    }

    private HotbarSelection() { }
}
