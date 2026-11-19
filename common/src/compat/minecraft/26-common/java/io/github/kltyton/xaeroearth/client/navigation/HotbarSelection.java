package io.github.kltyton.xaeroearth.client.navigation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.entity.player.Inventory;

final class HotbarSelection {
    static int selected(Inventory inventory) { return inventory.getSelectedSlot(); }
    static void select(Inventory inventory, int slot) { inventory.setSelectedSlot(slot); }

    static void swapWithInventory(Minecraft minecraft, LocalPlayer player, int inventorySlot, int hotbarSlot) {
        minecraft.gameMode.handleContainerInput(player.inventoryMenu.containerId, inventorySlot, hotbarSlot,
                ContainerInput.SWAP, player);
    }

    private HotbarSelection() { }
}
