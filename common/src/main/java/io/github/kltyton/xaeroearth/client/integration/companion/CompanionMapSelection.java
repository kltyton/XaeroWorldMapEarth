package io.github.kltyton.xaeroearth.client.integration.companion;

import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import io.github.kltyton.xaeroearth.client.navigation.PlayerNavigation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import xaero.map.gui.GuiMap;

/** Keeps portrait hit testing and selection in the same world-map projection. */
public final class CompanionMapSelection {
    public record Portrait(Entity entity, int x, int y, boolean selected) {}
    public record Box(int left, int top, int right, int bottom) {}
    public static final int PORTRAIT_RADIUS = 10;
    private static final int MAX_SELECTION = 32;
    private static final Set<UUID> selected = new LinkedHashSet<>();
    private static final class Provider {
        private static final CompanionMapAccess INSTANCE = CompanionCallMapAccess.connect();
    }
    private static GuiMap screen;
    private static List<Portrait> portraits = List.of();
    private static Box box;
    private static boolean pressed, extend;
    private static int startX, startY;

    public static List<Portrait> frame(GuiMap map, float partialTicks) {
        if (screen != map) {
            clear();
            screen = map;
        }
        if (!available(map)) {
            portraits = List.of();
            selected.clear();
            pressed = false;
            box = null;
            return List.of();
        }
        var mc = Minecraft.getInstance();
        var camera = NativeMapTerrain.camera();
        var next = new ArrayList<Portrait>();
        var present = new LinkedHashSet<UUID>();
        Entity vehicle = mc.player.isPassenger() ? mc.player.getRootVehicle() : null;
        for (Entity companion : Provider.INSTANCE.visibleCompanions(mc.level)) {
            if ((vehicle != null && companion.getRootVehicle() == vehicle) || companion.isRemoved()
                    || mc.level.getEntity(companion.getId()) != companion) continue;
            present.add(companion.getUUID());
            var position = companion.getPosition(partialTicks);
            var point = camera.project(position.x, position.y + companion.getBbHeight(), position.z);
            if (!Float.isFinite(point.x) || !Float.isFinite(point.y) || Math.abs(point.z) > 1
                    || point.x < 0 || point.y < 0 || point.x >= map.width || point.y >= map.height) continue;
            next.add(new Portrait(companion, Math.round(point.x), Math.round(point.y), selected.contains(companion.getUUID())));
        }
        selected.retainAll(present);
        next.sort(Comparator.comparing(Portrait::selected).thenComparingInt(Portrait::y)
                .thenComparing(portrait -> portrait.entity.getUUID()));
        portraits = List.copyOf(next);
        return portraits;
    }

    private static boolean available(GuiMap map) {
        var access = Provider.INSTANCE;
        var mc = Minecraft.getInstance();
        var processor = map.getMapProcessor();
        return access != null && mc.level != null && mc.player != null && !GuiMap.hiddenUI
                && EarthClient.themed(map) && NativeMapTerrain.active()
                && processor.getWorld() == mc.level && processor.isMapWorldUsable()
                && processor.isCurrentMultiworldWritable() && !processor.isCurrentMapLocked()
                && !processor.isConsideringNetherFairPlay()
                && mc.level.dimension().equals(processor.getMapWorld().getCurrentDimensionId())
                && access.available(mc.level);
    }

    public static boolean press(GuiMap map, double x, double y, boolean shift) {
        if (screen != map || !available(map) || map.getChildAt(x, y).isPresent()) return false;
        var menu = ((GuiMapAccess) map).earth$rightClickMenu();
        if (menu != null && !menu.isClosed()) return false;
        Portrait hit = null;
        for (Portrait portrait : portraits) {
            if (Math.abs(portrait.x - x) <= PORTRAIT_RADIUS && Math.abs(portrait.y - y) <= PORTRAIT_RADIUS) hit = portrait;
        }
        if (hit == null && !shift && selected.isEmpty()) return false;
        pressed = true; extend = shift;
        startX = (int) x; startY = (int) y;
        box = null;
        return true;
    }

    public static void drag(double x, double y) {
        if (!pressed) return;
        if (box == null && Math.hypot(x - startX, y - startY) < 5) return;
        box = new Box(Math.min(startX, (int) x), Math.min(startY, (int) y),
                Math.max(startX, (int) x), Math.max(startY, (int) y));
    }

    public static boolean release(GuiMap map, double x, double y) {
        if (screen != map || !pressed) return false;
        drag(x, y);
        Box area = box;
        pressed = false; box = null;
        if (!available(map)) return true;
        if (area != null) {
            if (!extend) selected.clear();
            for (Portrait portrait : portraits) {
                if (selected.size() < MAX_SELECTION && portrait.x >= area.left && portrait.x <= area.right
                        && portrait.y >= area.top && portrait.y <= area.bottom)
                    selected.add(portrait.entity.getUUID());
            }
        } else {
            for (int i = portraits.size() - 1; i >= 0; i--) {
                Portrait portrait = portraits.get(i);
                if (Math.abs(portrait.x - x) > PORTRAIT_RADIUS || Math.abs(portrait.y - y) > PORTRAIT_RADIUS) continue;
                if (!extend) selected.clear();
                UUID id = portrait.entity.getUUID();
                if (!extend || !selected.remove(id) && selected.size() < MAX_SELECTION) selected.add(id);
                return true;
            }
            var point = NativeMapTerrain.pickKnown(x, y);
            if (!extend && point != null && !selected.isEmpty()) {
                var goal = new BlockPos((int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z));
                if (!Provider.INSTANCE.move(Minecraft.getInstance().level, Set.copyOf(selected), goal))
                    PlayerNavigation.playerMessage("xaeroearth.navigation.companion_unavailable");
            }
        }
        return true;
    }

    public static Box box() { return box; }

    public static void clear() {
        screen = null; portraits = List.of(); selected.clear(); pressed = false; box = null;
        if (Provider.INSTANCE != null) Provider.INSTANCE.clearSnapshot();
    }

    private CompanionMapSelection() {}
}
