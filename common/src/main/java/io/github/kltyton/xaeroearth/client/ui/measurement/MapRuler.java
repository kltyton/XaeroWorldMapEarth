package io.github.kltyton.xaeroearth.client.ui.measurement;

import com.google.gson.JsonObject;
import io.github.kltyton.xaeroearth.api.client.EarthMapProjection;
import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.mapmark.MapAnnotations;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.Vec3;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/** A temporary ruler in the selected Xaero world, dimension and cave layer. */
public final class MapRuler {
    private static GuiMap owner;
    private static Vec3 start, end, cursor;

    public static void addOptions(GuiMap map, List<RightClickOption> options) {
        if (!EarthClient.themed(map) || !NativeMapTerrain.active()) return;
        var access = (GuiMapAccess) map;
        int height = access.earth$rightClickY();
        if (height != Short.MAX_VALUE && height != Integer.MAX_VALUE && height != Integer.MIN_VALUE) {
            var point = new Vec3(access.earth$rightClickX() + 0.5, height + 1.0, access.earth$rightClickZ() + 0.5);
            options.add(new RulerOption(map, options.size(), point, owner == map && start != null && end == null));
        }
        if (owner == map && start != null) options.add(new RulerOption(map, options.size(), null, false));
    }

    public static void preview(GuiMap map, Vec3 point) {
        if (owner == map && end == null) cursor = point;
    }

    public static JsonObject presentation(GuiMap map, double documentScale) {
        if (owner != map || start == null || !NativeMapTerrain.active() || GuiMap.hiddenUI) return null;
        var target = end != null ? end : cursor;
        var first = EarthMapProjection.worldMap(map, start.x, start.y + 0.08, start.z);
        if (first == null) return null;
        var second = target == null ? null : EarthMapProjection.worldMap(map, target.x, target.y + 0.08, target.z);
        var json = new JsonObject();
        json.addProperty("x", first.x() / documentScale);
        json.addProperty("y", first.y() / documentScale);
        if (second != null) {
            json.addProperty("endX", second.x() / documentScale);
            json.addProperty("endY", second.y() / documentScale);
            double horizontal = Math.hypot(target.x - start.x, target.z - start.z);
            double direct = target.distanceTo(start);
            json.addProperty("label", I18n.get("xaeroearth.ruler.distance", Math.round(horizontal), Math.round(direct)));
        } else {
            json.addProperty("label", I18n.get("xaeroearth.ruler.origin"));
        }
        return json;
    }

    public static void clear() {
        owner = null;
        start = end = cursor = null;
    }

    private static final class RulerOption extends RightClickOption {
        private final GuiMap map;
        private final Vec3 point;
        private final boolean finish;

        private RulerOption(GuiMap map, int index, Vec3 point, boolean finish) {
            super(point == null ? "xaeroearth.ruler.clear" : finish ? "xaeroearth.ruler.finish" : "xaeroearth.ruler.start", index, map);
            this.map = map;
            this.point = point;
            this.finish = finish;
        }

        @Override public void onAction(Screen screen) {
            if (!EarthClient.themed(map) || !NativeMapTerrain.active()) return;
            if (point == null) clear();
            else if (finish && owner == map && start != null) {
                end = point; cursor = null;
                if (MapAnnotations.saveMeasurement(MapScope.selected(map.getMapProcessor()), start, end)) clear();
            }
            else { owner = map; start = point; end = cursor = null; }
        }
    }

    private MapRuler() { }
}
