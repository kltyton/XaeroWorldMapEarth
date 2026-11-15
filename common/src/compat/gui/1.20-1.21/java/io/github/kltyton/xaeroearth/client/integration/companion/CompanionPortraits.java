package io.github.kltyton.xaeroearth.client.integration.companion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import xaero.map.gui.GuiMap;

public final class CompanionPortraits {
    public static void draw(GuiMap map, GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        CompanionMapSelection.drag(mouseX, mouseY);
        int size = CompanionMapSelection.PORTRAIT_RADIUS;
        for (var portrait : CompanionMapSelection.frame(map, partialTicks)) {
            int px = portrait.x() - size, py = portrait.y();
            int color = portrait.selected() ? 0xFF75DB64 : 0xE8242220;
            graphics.fill(px - 1, py - size - 1, px + 2 * size + 1, py + size + 1, color);
            if (!(portrait.entity() instanceof LivingEntity living)) {
                ItemStack icon = portrait.entity().getPickResult();
                if (icon == null || icon.isEmpty()) icon = new ItemStack(Items.SADDLE);
                graphics.renderItem(icon, portrait.x() - 8, py - 8);
                continue;
            }
            int modelSize = Math.max(2, (int) (18 / Math.max(0.75, living.getBbHeight())));
            graphics.enableScissor(px, py - size, px + size * 2, py + size);
            try {
                CompanionPortraitPlatform.draw(graphics, living, portrait.x(), py, size, modelSize);
            } finally {
                graphics.disableScissor();
            }
        }
        var box = CompanionMapSelection.box();
        if (box != null) {
            graphics.fill(box.left(), box.top(), box.right(), box.bottom(), 0x4075DB64);
            graphics.fill(box.left(), box.top(), box.right(), box.top() + 1, 0xFF75DB64);
            graphics.fill(box.left(), box.bottom() - 1, box.right(), box.bottom(), 0xFF75DB64);
            graphics.fill(box.left(), box.top(), box.left() + 1, box.bottom(), 0xFF75DB64);
            graphics.fill(box.right() - 1, box.top(), box.right(), box.bottom(), 0xFF75DB64);
        }
    }

    private CompanionPortraits() {}
}
