package io.github.kltyton.xaeroearth.client.integration.companion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import xaero.map.gui.GuiMap;

public final class CompanionPortraits {
    public static void draw(GuiMap map, PoseStack graphics, int mouseX, int mouseY, float partialTicks) {
        CompanionMapSelection.drag(mouseX, mouseY);
        int size = CompanionMapSelection.PORTRAIT_RADIUS;
        var window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        for (var portrait : CompanionMapSelection.frame(map, partialTicks)) {
            int px = portrait.x() - size, py = portrait.y();
            int color = portrait.selected() ? 0xFF75DB64 : 0xE8242220;
            GuiComponent.fill(graphics, px - 1, py - size - 1, px + 2 * size + 1, py + size + 1, color);
            if (!(portrait.entity() instanceof LivingEntity living)) {
                ItemStack icon = portrait.entity().getPickResult();
                if (icon == null || icon.isEmpty()) icon = new ItemStack(Items.SADDLE);
                Minecraft.getInstance().getItemRenderer().renderGuiItem(icon, portrait.x() - 8, py - 8);
                continue;
            }
            int modelSize = Math.max(2, (int) (18 / Math.max(0.75, living.getBbHeight())));
            RenderSystem.enableScissor((int) (px * scale), (int) (window.getHeight() - (py + size) * scale), (int) (2 * size * scale), (int) (2 * size * scale));
            try {
                InventoryScreen.renderEntityInInventory(portrait.x(), portrait.y() + size + modelSize / 2, modelSize, 0, 0, living);
            } finally {
                RenderSystem.disableScissor();
            }
        }
        var box = CompanionMapSelection.box();
        if (box != null) {
            GuiComponent.fill(graphics, box.left(), box.top(), box.right(), box.bottom(), 0x4075DB64);
            GuiComponent.fill(graphics, box.left(), box.top(), box.right(), box.top() + 1, 0xFF75DB64);
            GuiComponent.fill(graphics, box.left(), box.bottom() - 1, box.right(), box.bottom(), 0xFF75DB64);
            GuiComponent.fill(graphics, box.left(), box.top(), box.left() + 1, box.bottom(), 0xFF75DB64);
            GuiComponent.fill(graphics, box.right() - 1, box.top(), box.right(), box.bottom(), 0xFF75DB64);
        }
    }

    private CompanionPortraits() {}
}
