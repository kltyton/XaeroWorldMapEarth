package io.github.kltyton.xaeroearth.client.integration.xaeroplus;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Reads the existing XaeroPlus draw registry and its current window caches. */
final class DrawingSnapshot {
    sealed interface Shape permits Line, Ellipse, Area {}
    record Line(int x1, int z1, int x2, int z2, int argb, float width) implements Shape {}
    record Ellipse(int x, int z, int rx, int rz, int argb, float width) implements Shape {}
    record Area(int x, int z, int argb) implements Shape {}

    private record Feature(MethodHandle data, MethodHandle width) {}
    private record Coordinates(MethodHandle a, MethodHandle b, MethodHandle c, MethodHandle d) {
        int a(Object value) throws Throwable { return (int) a.invoke(value); }
        int b(Object value) throws Throwable { return (int) b.invoke(value); }
        int c(Object value) throws Throwable { return (int) c.invoke(value); }
        int d(Object value) throws Throwable { return (int) d.invoke(value); }
    }

    private static final String XP = "xaeroplus.";
    private final MethodHandle drawManager, registry, forEach, id, hud, fair, module, color, argb, areas, activeDimension;
    private final MethodHandle window, regionX, regionZ, regionSize;
    private final Feature lines, ellipses;
    private final Coordinates line, ellipse;
    private static boolean lookedUp;
    private static DrawingSnapshot source;

    static List<Shape> current(ResourceKey<Level> expected) {
        if (expected == null) return List.of();
        if (!lookedUp) { lookedUp = true; source = link(); }
        if (source == null) return List.of();
        try { return source.read(expected); }
        catch (Throwable failure) {
            if (failure instanceof Error error && !(error instanceof LinkageError)) throw error;
            LogUtils.getLogger().warn("Cannot read the installed XaeroPlus drawing registry", failure);
            source = null;
            return List.of();
        }
    }

    private List<Shape> read(ResourceKey<Level> expected) throws Throwable {
        if (!expected.equals(activeDimension.invoke())) return List.of();
        Object instance = hud.invoke();
        if (instance == null || (boolean) fair.invoke(instance)) return List.of();
        Object manager = drawManager.invoke();
        if (manager == null) return List.of();
        Object view = window.invoke();
        int x = (int) regionX.invoke(view), z = (int) regionZ.invoke(view), size = (int) regionSize.invoke(view);
        var features = new ArrayList<Object>();
        forEach.invoke(registry.invoke(manager), (java.util.function.Consumer<Object>) features::add);
        var shapes = new ArrayList<Shape>();
        for (Object feature : features) switch ((String) id.invoke(feature)) {
            case "Drawing-lines-saved" -> {
                float width = (float) lines.width.invoke(feature);
                for (var item : ((Map<?, ?>) lines.data.invoke(feature, x, z, size, expected)).entrySet())
                    shapes.add(line(item.getKey(), (int) item.getValue(), width));
            }
            case "Drawing-ellipses-saved" -> {
                float width = (float) ellipses.width.invoke(feature);
                for (var item : ((Map<?, ?>) ellipses.data.invoke(feature, x, z, size, expected)).entrySet())
                    shapes.add(ellipse(item.getKey(), (int) item.getValue(), width));
            }
            case "Drawing-highlights" -> {
                for (var item : ((Map<?, ?>) areas.invoke(feature)).entrySet()) {
                    long key = (long) item.getKey();
                    shapes.add(new Area((int) key * 16, (int) (key >>> 32) * 16, (int) (long) item.getValue()));
                }
            }
            case "Drawing-lines-in-progress" -> {
                int tint = (int) argb.invoke(color.invoke(module.invoke()));
                float width = (float) lines.width.invoke(feature);
                for (Object value : (List<?>) lines.data.invoke(feature, x, z, size, expected))
                    shapes.add(line(value, (tint & 0xffffff) | (80 << 24), width));
            }
            case "Drawing-ellipses-in-progress" -> {
                int tint = (int) argb.invoke(color.invoke(module.invoke()));
                float width = (float) ellipses.width.invoke(feature);
                for (Object value : (List<?>) ellipses.data.invoke(feature, x, z, size, expected))
                    shapes.add(ellipse(value, tint, width));
            }
            default -> { }
        }
        return List.copyOf(shapes);
    }

    private Line line(Object value, int tint, float width) throws Throwable {
        return new Line(line.a(value), line.b(value), line.c(value), line.d(value), tint, width);
    }

    private Ellipse ellipse(Object value, int tint, float width) throws Throwable {
        return new Ellipse(ellipse.a(value), ellipse.b(value), ellipse.c(value), ellipse.d(value), tint, width);
    }

    private static DrawingSnapshot link() {
        ClassLoader loader = DrawingSnapshot.class.getClassLoader();
        try { Class.forName(XP + "Globals", false, loader); }
        catch (ClassNotFoundException missing) { return null; }
        catch (LinkageError failure) {
            LogUtils.getLogger().warn("Cannot link XaeroPlus classes for the drawing overlay", failure);
            return null;
        }
        try { return new DrawingSnapshot(loader); }
        catch (ReflectiveOperationException | LinkageError failure) {
            LogUtils.getLogger().warn("The installed XaeroPlus version has a different drawing registry", failure);
            return null;
        }
    }

    private DrawingSnapshot(ClassLoader loader) throws ReflectiveOperationException {
        // These members are public, but xaeroplus is optional and some Earth targets
        // have no matching build. Binding a public adapter avoids a mandatory linkage;
        // access widening or a mixin into its private drawing cache is unnecessary.
        var lookup = MethodHandles.publicLookup();
        var globals = type(loader, "Globals");
        activeDimension = lookup.unreflect(globals.getMethod("getCurrentDimensionId"));
        drawManager = lookup.unreflectGetter(globals.getField("drawManager"));
        registry = lookup.unreflect(drawManager.type().returnType().getMethod("registry"));
        forEach = lookup.unreflect(registry.type().returnType().getMethod("forEach", java.util.function.Consumer.class));
        id = lookup.unreflect(type(loader, "feature.render.DrawFeature").getMethod("id"));
        var windowType = type(loader, "feature.render.MapRenderWindow");
        window = lookup.unreflect(windowType.getMethod("resolveCurrent"));
        regionX = lookup.unreflect(windowType.getMethod("windowX"));
        regionZ = lookup.unreflect(windowType.getMethod("windowZ"));
        regionSize = lookup.unreflect(windowType.getMethod("windowSize"));
        var hudType = Class.forName("xaero.common.HudMod", false, loader);
        hud = lookup.unreflectGetter(hudType.getField("INSTANCE"));
        fair = lookup.unreflect(hudType.getMethod("isFairPlay"));
        var drawing = type(loader, "module.impl.Drawing");
        module = MethodHandles.insertArguments(
                lookup.unreflect(type(loader, "module.ModuleManager").getMethod("getModule", Class.class)), 0, drawing);
        color = lookup.unreflect(drawing.getMethod("getDrawingColor"));
        argb = lookup.unreflect(color.type().returnType().getMethod("getInt"));
        areas = lookup.unreflect(type(loader, "feature.render.highlight.DirectChunkHighlightDrawFeature")
                .getMethod("chunkHighlights"));
        lines = feature(lookup, type(loader, "feature.render.line.AbstractLineDrawFeature"), "provideLinesInWindow", "lineWidth");
        ellipses = feature(lookup, type(loader, "feature.render.ellipse.AbstractEllipseDrawFeature"), "provideEllipsesInWindow", "thickness");
        line = coordinates(lookup, type(loader, "feature.render.line.Line"), "x1", "z1", "x2", "z2");
        ellipse = coordinates(lookup, type(loader, "feature.render.ellipse.Ellipse"), "centerX", "centerZ", "radiusX", "radiusZ");
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(XP + name, false, loader);
    }

    private static Feature feature(MethodHandles.Lookup lookup, Class<?> owner, String data, String width)
            throws ReflectiveOperationException {
        return new Feature(lookup.unreflect(owner.getMethod(data, int.class, int.class, int.class, ResourceKey.class)),
                lookup.unreflect(owner.getMethod(width)));
    }

    private static Coordinates coordinates(MethodHandles.Lookup lookup, Class<?> owner, String a, String b, String c, String d)
            throws ReflectiveOperationException {
        return new Coordinates(lookup.unreflect(owner.getMethod(a)), lookup.unreflect(owner.getMethod(b)),
                lookup.unreflect(owner.getMethod(c)), lookup.unreflect(owner.getMethod(d)));
    }
}
