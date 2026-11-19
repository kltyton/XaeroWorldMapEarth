package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.ClientScreen;
import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import io.github.kltyton.xaeroearth.client.chunkmap.TerrainDetail;
import io.github.kltyton.xaeroearth.client.ui.minimap.MinimapFraming;
import io.github.kltyton.xaeroearth.client.integration.xaeroplus.WorldMapAppearance;
import io.github.kltyton.xaeroearth.client.mapmark.MapAnnotations;
import io.github.kltyton.xaeroearth.client.mapmark.MapMark;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import io.github.kltyton.xaeroearth.client.navigation.NavigationTrace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import xaero.common.HudMod;
import xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/** Paints the optional HUD terrain into Xaero's minimap target before its marker pass. */
public final class NativeMinimapTerrain {
    private static XaeroMapBridge bridge;
    private static NativeTerrainScene renderer;
    private static final MinimapAlpha ALPHA = new MinimapAlpha();
    private static long sceneKey = -1;
    private record MarkerProjection(MapCamera camera, double originX, double originY, double cosine, double sine,
                                    boolean circular, List<MapMark> marks) {}
    private record Label(String text, int x, int y, int width, double distance) {}
    @FunctionalInterface public interface LabelPainter { void draw(Font font, String text, int x, int y); }
    private static MarkerProjection markerProjection;
    private static MapCamera lastCamera;
    private static double detailRadius;
    private static double cameraHeight = Double.NaN;
    private static long lastFrame;

    public static void draw(RenderTarget target, Vec3 center, double scale, int viewWidth,
                            ResourceKey<Level> dimension, boolean cave, boolean lockedNorth, int shape,
                            double sine, double cosine) {
        NativeMapInput.flushOrientation();
        markerProjection = null;
        detailRadius = 0;
        var mc = Minecraft.getInstance();
        if (ClientScreen.screen(mc) instanceof GuiMap && !WorldMapAppearance.transparentBackground()) return;
        if (!EarthPreferences.ENABLED.get() || !EarthPreferences.MINIMAP_3D.get()
                || !mc.isSameThread() || mc.level == null || mc.player == null
                || !mc.level.dimension().equals(dimension) || !(scale > 0) || viewWidth <= 0) return;
        var session = WorldMapSession.getCurrentSession();
        var processor = session == null ? null : session.getMapProcessor();
        if (processor == null || processor.getWorld() != mc.level || processor.isConsideringNetherFairPlay()
                || !processor.isMapWorldUsable() || !processor.isCurrentMultiworldWritable()
                || cave != (processor.getCurrentCaveLayer() != Integer.MAX_VALUE)
                || !dimension.equals(processor.getMapWorld().getCurrentDimensionId())) return;
        if (bridge == null) bridge = new XaeroMapBridge();
        var models = EarthClient.modelsFor(processor);
        if (!bridge.prepareFrame(processor, models)) return;
        if (sceneKey != bridge.sceneKey()) {
            if (renderer != null) renderer.resetSurface();
            sceneKey = bridge.sceneKey();
            cameraHeight = Double.NaN;
        }
        var view = NativeMapInput.hudView();
        long now = System.nanoTime();
        double dt = (now - lastFrame) * 1e-9;
        lastFrame = now;
        double blend = -Math.expm1(-dt * 15);
        if (!Double.isFinite(cameraHeight) || Math.abs(cameraHeight - center.y) > 16 || dt > 0.5)
            cameraHeight = center.y;
        else cameraHeight += (center.y - cameraHeight) * blend;
        // Xaero rotates the north-up texture around its snapped origin. Put the actual
        // player center at the same subpixel position so that its native blit recenters it.
        double originX = target.width / 2.0 + centerOffset(center.x, scale);
        double originY = target.height / 2.0 + centerOffset(center.z, scale);
        int span = (int) Math.ceil(viewWidth * (lockedNorth || shape == 1 ? 1 : Math.sqrt(2))) + 4;
        int canvasWidth = Math.min(target.width, span);
        int canvasHeight = Math.min(target.height, span);
        double screenCos = lockedNorth ? 1 : sine;
        double screenSin = lockedNorth ? 0 : cosine;
        // North lock fixes the HUD at the world-map yaw and pitch. Otherwise Xaero controls heading
        // while HUD pitch still follows the world map (independent of player look pitch); counter-roll
        // in the framebuffer so Xaero's final blit keeps raised terrain upright.
        double heading = view != EarthPreferences.View.TOP && !lockedNorth
                ? Math.atan2(screenSin, screenCos) : 0;
        double yaw = lockedNorth ? NativeMapInput.hudYaw() : -heading;
        double pitchAngle = NativeMapInput.hudAngle();
        // Use the same rigid orthographic lookAt pitch projection as the world map (matchPlanarFootprint = false)
        // so HUD pitch truly matches world-map pitch while 1.000x horizontal scale matches Xaero 2D width.
        var camera = new MapCamera(center.x, cameraHeight, center.z, scale, yaw, pitchAngle,
                view.name().toLowerCase(Locale.ROOT), target.width, target.height, 1, 75, canvasWidth, canvasHeight,
                originX - canvasWidth / 2.0, originY - canvasHeight / 2.0, heading, false, true);
        double radius = Math.hypot(canvasWidth, canvasHeight) / (2 * scale);
        double groundRadius = radius / Math.max(0.25, Math.cos(camera.angle()));
        detailRadius = TerrainDetail.radius(scale, groundRadius);
        if (!bridge.capture(processor, models, camera) && bridge.surface() == null) return;
        if (bridge.surface() == null && bridge.visibleModelsView().isEmpty()) return;
        // Keep adjacent meshes resident when a turn moves them momentarily outside the HUD frustum.
        if (renderer == null) renderer = new NativeTerrainScene(96L << 20);
        var scope = MapScope.selected(processor);
        var hud = HudMod.INSTANCE;
        var options = hud.getHudConfigs().getClientConfigManager();
        int unexploredOpacity = options.getEffective(MinimapProfiledConfigOptions.UNDISCOVERED_OPACITY);
        float brightness = hud.getSupportMods().worldmapSupport.getMinimapBrightness();
        if (renderer.drawOpaque(camera, bridge.surface(), models, bridge.visibleModelsView(), target, brightness, cave,
                false, () -> {
                    ALPHA.background(target, unexploredOpacity);
                    return true;
                }, scope)) {
            // The native HUD applies its overall opacity in its final blit. Resolve cutout
            // pixels that wrote terrain depth before blending translucent faces and markers.
            ALPHA.resolve(target, renderer);
            var marks = MapAnnotations.surfaceMarks(scope);
            renderer.drawTransparent(true, marks, bridge.claims(scope), List.of(), bridge, scope);
            markerProjection = new MarkerProjection(camera, originX, originY,
                    screenCos, screenSin, shape == 1, marks);
            lastCamera = camera;
        }
    }

    public static double zoom(double nativeZoom) {
        return MinimapFraming.enabled() ? nativeZoom * MinimapFraming.value() : nativeZoom;
    }

    static XaeroMapBridge cachedMappedTerrain() {
        return bridge;
    }

    private static double centerOffset(double coordinate, double zoom) {
        int snapped = Mth.floor(coordinate);
        if (zoom == 0.5) snapped &= ~1;
        return (coordinate - snapped) * zoom;
    }

    public static Vector3f projectMarker(double x, double y, double z) {
        var projection = markerProjection;
        if (projection == null) return null;
        var camera = projection.camera();
        if (!Double.isFinite(y) || y == Short.MAX_VALUE || y == Integer.MAX_VALUE || y == Integer.MIN_VALUE)
            y = bridge.heightAt(x, z, (int) camera.y()) + 1;
        var point = camera.project(x, y, z);
        if (!Float.isFinite(point.x) || Math.abs(point.z) > 1) return new Vector3f(Float.NaN);
        double east = point.x - projection.originX();
        double south = point.y - projection.originY();
        point.x = (float) (projection.cosine() * east - projection.sine() * south);
        point.y = (float) (projection.sine() * east + projection.cosine() * south);
        return point;
    }

    /** Draw upright beside the marker, in Xaero's rotation framebuffer and marker coordinates. */
    public static void paintLabels(float halfWidth, LabelPainter painter) {
        var projection = markerProjection;
        if (projection == null || halfWidth < 14) return;
        var minecraft = Minecraft.getInstance();
        String remaining = NavigationTrace.distanceLabel(minecraft.level);
        if (remaining == null && projection.marks().isEmpty()) return;
        var font = minecraft.font;
        var candidates = new ArrayList<Label>();
        int maxTextWidth = Math.min(72, (int) halfWidth);
        double edge = halfWidth - 3;
        var drawn = new ArrayList<Label>(4);
        if (remaining != null) {
            int width = font.width(remaining);
            int y = (int) (projection.circular() ? edge * 0.70 : edge) - font.lineHeight;
            double room = projection.circular() ? Math.sqrt(edge * edge - Math.pow(y + font.lineHeight, 2)) : edge;
            if (width / 2.0 < room) {
                var label = new Label(remaining, -width / 2, y, width, 0);
                drawn.add(label);
                painter.draw(font, label.text, label.x, label.y);
            }
        }
        for (var mark : projection.marks()) {
            boolean ruler = mark.kind() == MapMark.Kind.MEASURE;
            if (mark.text().isBlank() && !ruler) continue;
            Vec3 anchor = mark.points().get(0);
            if (ruler) anchor = anchor.lerp(mark.points().get(1), 0.5);
            var point = projectMarker(anchor.x, anchor.y + 0.65, anchor.z);
            if (!Float.isFinite(point.x) || Math.abs(point.x) >= edge || Math.abs(point.y) >= edge) continue;
            String text = ruler ? I18n.get("xaeroearth.marks.hud_distance",
                    Math.round(mark.points().get(0).distanceTo(mark.points().get(1)))) : mark.text();
            if (font.width(text) > maxTextWidth) text = font.plainSubstrByWidth(text, maxTextWidth - font.width("…")) + "…";
            int width = font.width(text), x = Math.round(point.x) - width / 2, y = Math.round(point.y) - 12;
            double right = Math.max(Math.abs(x), Math.abs(x + width));
            double bottom = Math.max(Math.abs(y), Math.abs(y + font.lineHeight));
            if (projection.circular() ? Math.hypot(right, bottom) >= edge : right >= edge || bottom >= edge) continue;
            candidates.add(new Label(text, x, y, width, point.x * point.x + point.y * point.y));
        }
        candidates.sort(Comparator.comparingDouble(Label::distance));
        for (var label : candidates) {
            if (drawn.size() >= 4) break;
            if (drawn.stream().anyMatch(other -> Math.abs(label.y - other.y) < font.lineHeight + 2
                    && label.x < other.x + other.width + 2 && other.x < label.x + label.width + 2)) continue;
            drawn.add(label);
            painter.draw(font, label.text, label.x, label.y);
        }
    }

    /** True only if Earth painted this minimap frame. */
    public static boolean painted() { return markerProjection != null; }
    public static MapCamera camera() { return lastCamera; }
    public static void finishMarkers() { markerProjection = null; }
    public static double detailRadius() { return detailRadius; }

    public static void close() {
        ALPHA.close();
        if (renderer != null) renderer.close();
        if (bridge != null) bridge.close();
        renderer = null;
        bridge = null;
        sceneKey = -1;
        markerProjection = null;
        lastCamera = null;
        cameraHeight = Double.NaN;
        lastFrame = 0;
        detailRadius = 0;
    }

    private NativeMinimapTerrain() {}
}
