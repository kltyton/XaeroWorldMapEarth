package io.github.kltyton.xaeroearth.client.mapmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kltyton.xaeroearth.api.client.EarthMapProjection;
import io.github.kltyton.xaeroearth.client.ClientScreen;
import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.integration.waystones.WaystonesOverlay;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.Vec3;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/** Native map actions and transient drawing state. Saved marks are shared by both maps. */
public final class MapAnnotations {
    private static final UUID PREVIEW_ID = new UUID(0, 0);
    private static MapMarkStore store;
    private static Draft draft;
    private static boolean drawing;
    private static double lastX, lastY;
    private static Vec3 cursor;
    private static List<MapMark> cachedLocal, cachedWaystones, cachedCombined;
    private record Draft(GuiMap map, MapScope scope, MapMark.Kind kind, boolean freehand, List<Vec3> points) { }

    private static MapMarkStore store() {
        if (store == null) store = new MapMarkStore(Minecraft.getInstance().gameDirectory.toPath()
                .resolve("xaeroearth").resolve("annotations").resolve("maps.json"));
        return store;
    }

    public static List<MapMark> saved(MapScope scope) {
        return scope == null ? List.of() : store().get(scope);
    }

    public static List<MapMark> preview(GuiMap map, MapScope scope) {
        var current = draft;
        if (!matches(map, scope) || current.points().isEmpty()) return List.of();
        var points = new ArrayList<>(current.points());
        if (!current.freehand() && cursor != null && points.size() < MapMark.POINT_LIMIT
                && points.get(points.size() - 1).distanceToSqr(cursor) > 0.0625) points.add(cursor);
        if (points.size() < 2) return List.of();
        // Preview the outline while editing. Ear clipping is only needed for the saved area.
        if (current.kind() == MapMark.Kind.AREA && points.size() >= 3 && points.size() < MapMark.POINT_LIMIT)
            points.add(points.get(0));
        return List.of(new MapMark(PREVIEW_ID, MapMark.Kind.LINE, points, ""));
    }

    public static void cursor(GuiMap map, Vec3 point) {
        if (draft != null && draft.map() == map) cursor = point;
    }

    public static List<MapMark> surfaceMarks(MapScope scope) {
        var local = saved(scope);
        var waystones = WaystonesOverlay.forMap(scope);
        if (waystones.isEmpty()) return local;
        if (local.isEmpty()) return waystones;
        if (local != cachedLocal || waystones != cachedWaystones) {
            var combined = new ArrayList<MapMark>(local.size() + waystones.size());
            combined.addAll(local); combined.addAll(waystones);
            cachedCombined = List.copyOf(combined);
            cachedLocal = local; cachedWaystones = waystones;
        }
        return cachedCombined;
    }

    public static void addOptions(GuiMap map, List<RightClickOption> options) {
        if (!EarthClient.themed(map) || GuiMap.hiddenUI) return;
        var scope = MapScope.selected(map.getMapProcessor());
        if (scope == null || !store().ready()) return;
        Vec3 point = menuPoint(map);
        if (matches(map, scope)) {
            var current = draft;
            if (!current.freehand() && current.points().size() >= (current.kind() == MapMark.Kind.AREA ? 3 : 2))
                add(options, map, "finish", () -> commit(current));
            add(options, map, "cancel", () -> cancel(map));
            return;
        }
        if (point == null) return;
        add(options, map, "freehand", () -> begin(map, scope, MapMark.Kind.LINE, null));
        add(options, map, "line", () -> begin(map, scope, MapMark.Kind.LINE, point));
        add(options, map, "area", () -> begin(map, scope, MapMark.Kind.AREA, point));
        add(options, map, "note", () -> name(map, scope, point, MapMark.Kind.NOTE, null));
        add(options, map, "waypoint", () -> name(map, scope, point, MapMark.Kind.WAYPOINT, null));
        var nearby = nearest(scope, point);
        if (nearby != null) {
            if (nearby.kind() == MapMark.Kind.NOTE || nearby.kind() == MapMark.Kind.WAYPOINT)
                add(options, map, "rename", () -> name(map, scope, point, nearby.kind(), nearby));
            add(options, map, "remove", () -> store().remove(scope, nearby.id()));
        }
    }

    public static boolean click(GuiMap map, double x, double y) {
        if (draft == null || !EarthClient.themed(map) || GuiMap.hiddenUI) return false;
        var scope = MapScope.selected(map.getMapProcessor());
        if (!matches(map, scope)) { cancel(map); return false; }
        if (map.getChildAt(x, y).isPresent()) return false;
        var menu = ((GuiMapAccess) map).earth$rightClickMenu();
        if (menu != null && !menu.isClosed()) return false;
        Vec3 point = EarthMapProjection.pick(map, x, y);
        if (point != null) {
            if (draft.freehand()) { drawing = true; lastX = x; lastY = y; }
            append(draft, point);
        }
        return true;
    }

    public static void drag(GuiMap map, double x, double y) {
        var current = draft;
        if (current == null || current.map() != map || !current.freehand() || !drawing
                || Math.hypot(x - lastX, y - lastY) < 2) return;
        lastX = x; lastY = y;
        var point = EarthMapProjection.pick(map, x, y);
        if (point == null) { commit(current); return; }
        append(current, point);
    }

    public static boolean release(GuiMap map, double x, double y) {
        var current = draft;
        if (current == null || current.map() != map || !current.freehand() || !drawing) return false;
        var point = EarthMapProjection.pick(map, x, y);
        if (point != null) append(current, point);
        drawing = false;
        if (current.points().size() >= 2) commit(current);
        else current.points().clear();
        return true;
    }

    private static void append(Draft current, Vec3 point) {
        var points = current.points();
        if (points.size() < MapMark.POINT_LIMIT && (points.isEmpty()
                || points.get(points.size() - 1).distanceToSqr(point) > 0.0625)) points.add(point);
    }

    private static void commit(Draft current) {
        if (draft != current || !current.scope().equals(MapScope.selected(current.map().getMapProcessor()))) return;
        if (current.points().size() >= (current.kind() == MapMark.Kind.AREA ? 3 : 2))
            store().add(current.scope(), new MapMark(UUID.randomUUID(), current.kind(), current.points(), ""));
        cancel(current.map());
    }

    public static boolean cancel(GuiMap map) {
        if (draft == null || draft.map() != map) return false;
        draft = null; cursor = null; drawing = false;
        return true;
    }

    public static void stop() {
        draft = null; cursor = null; drawing = false;
        if (store != null) { store.close(); store = null; }
    }

    public static boolean saveMeasurement(MapScope scope, Vec3 from, Vec3 to) {
        return scope != null && from != null && to != null
                && store().add(scope, new MapMark(UUID.randomUUID(), MapMark.Kind.MEASURE, List.of(from, to), ""));
    }

    public static JsonArray labels(GuiMap map, double documentScale) {
        var labels = new JsonArray();
        if (!EarthClient.themed(map) || GuiMap.hiddenUI) return labels;
        for (var mark : surfaceMarks(MapScope.selected(map.getMapProcessor()))) {
            boolean measurement = mark.kind() == MapMark.Kind.MEASURE;
            if (mark.text().isBlank() && !measurement) continue;
            var pos = mark.points().get(0);
            if (measurement) pos = pos.lerp(mark.points().get(1), 0.5);
            var p = EarthMapProjection.worldMap(map, pos.x, pos.y + 0.65, pos.z);
            if (p == null || p.x() < 0 || p.y() < 0 || p.x() >= map.width || p.y() >= map.height) continue;
            var label = new JsonObject();
            label.addProperty("id", mark.id().toString());
            label.addProperty("x", p.x() / documentScale);
            label.addProperty("y", p.y() / documentScale);
            if (measurement) {
                var a = mark.points().get(0); var b = mark.points().get(1);
                label.addProperty("text", I18n.get("xaeroearth.ruler.distance",
                        Math.round(Math.hypot(a.x - b.x, a.z - b.z)), Math.round(a.distanceTo(b))));
            } else label.addProperty("text", mark.text());
            labels.add(label);
        }
        return labels;
    }

    private static void begin(GuiMap map, MapScope scope, MapMark.Kind kind, Vec3 point) {
        var points = new ArrayList<Vec3>();
        if (point != null) points.add(point);
        draft = new Draft(map, scope, kind, point == null, points);
        cursor = null; drawing = false;
    }

    private static boolean matches(GuiMap map, MapScope scope) {
        return draft != null && draft.map() == map && draft.scope().equals(scope);
    }

    private static Vec3 menuPoint(GuiMap map) {
        var coordinates = (GuiMapAccess) map;
        int y = coordinates.earth$rightClickY();
        if (y == Short.MAX_VALUE || y == Integer.MAX_VALUE || y == Integer.MIN_VALUE) return null;
        return new Vec3(coordinates.earth$rightClickX() + 0.5, y + 1.0, coordinates.earth$rightClickZ() + 0.5);
    }

    private static MapMark nearest(MapScope scope, Vec3 point) {
        MapMark nearest = null;
        double best = 3.0 * 3.0;
        for (MapMark mark : saved(scope)) for (Vec3 vertex : mark.points()) {
            double distance = point.distanceToSqr(vertex);
            if (distance <= best) { best = distance; nearest = mark; }
        }
        return nearest;
    }

    private static void name(GuiMap map, MapScope scope, Vec3 point, MapMark.Kind kind, MapMark previous) {
        var minecraft = Minecraft.getInstance();
        var session = WorldMapSession.getCurrentSession();
        ClientScreen.open(minecraft, new MapMarkEditor(map, ClientScreen.viewLabel(
                "xaeroearth.marks." + (kind == MapMark.Kind.WAYPOINT ? "waypoint" : "note")),
                previous == null ? "" : previous.text(), text -> {
                    if (session == null || session != WorldMapSession.getCurrentSession()
                            || !scope.equals(MapScope.selected(session.getMapProcessor()))) return;
                    store().add(scope, new MapMark(previous == null ? UUID.randomUUID() : previous.id(),
                            kind, previous == null ? List.of(point) : previous.points(), text));
                }));
    }

    private static void add(List<RightClickOption> options, GuiMap map, String key, Runnable action) {
        options.add(new RightClickOption("xaeroearth.marks." + key, options.size(), map) {
            @Override public void onAction(Screen screen) {
                if (EarthClient.themed(map)) action.run();
            }
        });
    }

    private MapAnnotations() { }
}
