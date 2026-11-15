package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import io.github.kltyton.xaeroearth.client.chunkmap.TerrainDetail;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import xaero.map.WorldMapSession;

/** Paints the optional HUD terrain into Xaero's minimap target before its marker pass. */
public final class NativeMinimapTerrain {
    private static XaeroMapBridge bridge;
    private static NativeTerrainScene renderer;
    private static long sceneKey = -1;
    private record MarkerProjection(MapCamera camera, double originX, double originY, double cosine, double sine) {}
    private static MarkerProjection markerProjection;
    private static double detailRadius;
    private static double cameraHeight = Double.NaN;
    private static long lastFrame;

    public static void draw(RenderTarget target, Vec3 center, double scale, int viewWidth,
                            ResourceKey<Level> dimension, boolean cave, boolean lockedNorth,
                            double sine, double cosine) {
        markerProjection = null;
        detailRadius = 0;
        var mc = Minecraft.getInstance();
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
        var view = NativeMapInput.view();
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
        int span = (int) Math.ceil(viewWidth * (lockedNorth ? 1 : Math.sqrt(2))) + 4;
        int canvasWidth = Math.min(target.width, span);
        int canvasHeight = Math.min(target.height, span);
        var camera = new MapCamera(center.x, cameraHeight, center.z, scale, 0, NativeMapInput.angle(),
                view.name().toLowerCase(Locale.ROOT), target.width, target.height, 1, 75, canvasWidth, canvasHeight,
                originX - canvasWidth / 2.0, originY - canvasHeight / 2.0);
        double radius = Math.hypot(canvasWidth, canvasHeight) / (2 * scale);
        if (view != EarthPreferences.View.STREET)
            radius /= Math.max(0.05, Math.cos(camera.angle()));
        detailRadius = TerrainDetail.radius(scale, radius);
        if (!bridge.capture(processor, models, camera) && bridge.surface() == null) return;
        if (bridge.surface() == null && bridge.visibleModels().isEmpty()) return;
        // Keep adjacent meshes resident when a turn moves them momentarily outside the HUD frustum.
        if (renderer == null) renderer = new NativeTerrainScene(96L << 20);
        if (renderer.drawOpaque(camera, bridge.surface(), models, bridge.visibleModels(), target, bridge.brightness(), cave)) {
            renderer.drawTransparent(true);
            markerProjection = new MarkerProjection(camera, originX, originY,
                    lockedNorth ? 1 : sine, lockedNorth ? 0 : cosine);
        }
    }

    public static double zoom(double nativeZoom) {
        return EarthPreferences.ENABLED.get() && EarthPreferences.MINIMAP_3D.get()
                ? NativeMapInput.minimapScale(nativeZoom) : nativeZoom;
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

    public static void finishMarkers() { markerProjection = null; }
    public static double detailRadius() { return detailRadius; }

    public static void close() {
        if (renderer != null) renderer.close();
        if (bridge != null) bridge.close();
        renderer = null;
        bridge = null;
        sceneKey = -1;
        markerProjection = null;
        cameraHeight = Double.NaN;
        lastFrame = 0;
        detailRadius = 0;
    }

    private NativeMinimapTerrain() {}
}
