package io.github.kltyton.xaeroearth.client.ui;

import io.github.kltyton.xaeroearth.api.client.EarthMapProjection;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.MapTerrainHit;
import io.github.kltyton.xaeroearth.client.chunkmap.picking.MapDepthPicker;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.integration.xaeroplus.WorldMapAppearance;
import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import io.github.kltyton.xaeroearth.client.mapmark.MapAnnotations;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import io.github.kltyton.xaeroearth.client.ui.measurement.MapRuler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import xaero.map.gui.GuiMap;

/** Draws native terrain around the entity pass using the same camera and depth target. */
public final class NativeMapTerrain extends TerrainAccess {
    private static ActiveMapResources rendererResources;
    private static MapCamera camera;
    private static GuiMap cameraOwner;
    private record Pointer(MapCamera camera, double x, double y, NativeTerrainScene.Surface surface,
                           long terrainRevision, NativeTileSession models, long modelRevision,
                           long coverageRevision, Vec3 hit) { }
    private static Pointer pointer;
    private static final MapDepthPicker depthPicker = new MapDepthPicker();
    private static XaeroMapBridge bridge;
    private static long installedScene = -1;
    private static String viewMode;
    private static double streetScale;
    private static boolean transparentPending;
    private static GuiMap backgroundOwner;

    public static void beginWorldBackground(GuiMap map) {
        backgroundOwner = null;
        if (WorldMapAppearance.transparentBackground()) {
            saveWorldBackground(mainTarget(Minecraft.getInstance()));
            backgroundOwner = map;
        } else {
            releaseWorldBackground();
        }
    }

    public static void prewarm(ActiveMapResources resources) {
        if (renderer != null && rendererResources == resources) return;
        hide();
        rendererResources = resources;
        renderer = new NativeTerrainScene();
    }

    public static void draw(GuiMap map, XaeroMapBridge data, ActiveMapResources resources) {
        transparentPending = false;
        if (!data.prepareFrame(map)) { suspend(); return; }
        prewarm(resources);
        bridge = data;
        if (installedScene != data.sceneKey()) {
            renderer.resetSurface();
            MapRuler.clear();
            NativeMapInput.close(map); viewMode = null; camera = null; cameraOwner = null;
            installedScene = data.sceneKey();
        }
        var access = (GuiMapAccess) map;
        NativeMapInput.prepare(map);
        Minecraft minecraft = Minecraft.getInstance();
        double scale = NativeMapInput.worldMapScale(map);
        String mode = NativeMapInput.view().name().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals(viewMode)) { viewMode = mode; if (mode.equals("street")) streetScale = access.earth$scale(); }
        double height = data.heightAt(access.earth$cameraX(), access.earth$cameraZ(),
                minecraft.player == null ? 64 : minecraft.player.blockPosition().getY());
        height = NativeMapInput.height(height);
        var nextCamera = new MapCamera(access.earth$cameraX(), height, access.earth$cameraZ(),
                scale, NativeMapInput.yaw(), NativeMapInput.angle(), mode, map.width, map.height,
                data.presentationUnit(), mode.equals("street") ? net.minecraft.util.Mth.clamp(Math.toDegrees(2 * Math.atan(
                        Math.tan(Math.toRadians(75) / 2) * streetScale / access.earth$scale())), 1, 170) : 75);
        if (!data.capture(map, nextCamera, NativeMinimapTerrain.cachedMappedTerrain())) { suspend(); return; }
        var target = mainTarget(minecraft);
        if (!renderer.drawOpaque(nextCamera, data.surface(), data.models(), data.visibleModelsView(),
                target, data.brightness(),
                map.getMapProcessor().getCurrentCaveLayer() != Integer.MAX_VALUE, true,
                () -> backgroundOwner == map && restoreWorldBackground(target),
                MapScope.selected(map.getMapProcessor()))) {
            suspend();
            return;
        }
        camera = nextCamera;
        cameraOwner = map;
        var window = minecraft.getWindow();
        captureMapDepth(target, depthPicker, pickingView(),
                minecraft.mouseHandler.xpos() * map.width / window.getScreenWidth(),
                minecraft.mouseHandler.ypos() * map.height / window.getScreenHeight());
        transparentPending = true;
    }

    public static void finishTerrain(GuiMap map) {
        if (renderer == null || !transparentPending) return;
        try {
            var processor = map.getMapProcessor();
            var scope = GuiMap.hiddenUI ? null : MapScope.selected(processor);
            renderer.drawTransparent(showsNavigation(map), MapAnnotations.surfaceMarks(scope),
                    scope == null ? java.util.List.of() : bridge.claims(scope), MapAnnotations.preview(map, scope), bridge, scope);
        } finally {
            transparentPending = false;
        }
    }

    static boolean showsNavigation(GuiMap map) {
        var level = Minecraft.getInstance().level;
        var processor = map.getMapProcessor();
        return cameraOwner == map && !GuiMap.hiddenUI && level != null && processor != null && processor.getWorld() == level
                && processor.getMapWorld() != null
                && processor.isCurrentMultiworldWritable() && !processor.isConsideringNetherFairPlay()
                && level.dimension().equals(processor.getMapWorld().getCurrentDimensionId());
    }

    public static Vector3f project(double x, double z) {
        if (camera == null || bridge == null) return null;
        return camera.project(x, bridge.heightAt(x, z, (int) camera.y()) + 1, z);
    }

    public static Vector3f project(double x, double y, double z) {
        return camera == null ? null : camera.project(x, y, z);
    }

    public static Vec3 pick(double x, double y) {
        if (camera == null || bridge == null) return null;
        var rasterHit = depthPicker.pick(pickingView(), x, y);
        if (rasterHit != null) return rasterHit.hit();
        var surface = renderer.visibleSurface();
        long terrainRevision = bridge.terrainRevision();
        var models = bridge.models();
        long modelRevision = models == null ? -1 : models.revision();
        long coverageRevision = renderer.coverageRevision();
        var last = pointer;
        if (last != null && last.camera().equals(camera) && last.x() == x && last.y() == y
                && last.surface() == surface && last.terrainRevision() == terrainRevision
                && last.models() == models && last.modelRevision() == modelRevision
                && last.coverageRevision() == coverageRevision) return last.hit();
        Vec3 point = MapTerrainHit.pick(camera, bridge, renderer, x, y);
        pointer = new Pointer(camera, x, y, surface, terrainRevision, models, modelRevision, coverageRevision, point);
        return point;
    }

    private static MapDepthPicker.View pickingView() {
        var surface = renderer.visibleSurface();
        return new MapDepthPicker.View(camera, installedScene, surface == null ? -1 : surface.revision(), renderer.coverageRevision());
    }

    public static Vec3 pickKnown(double x, double y) { return pick(x, y); }

    /** Shares the visible hit between the tooltip and the native context menu. */
    public static Vec3 updatePointer(GuiMap map, double screenX, double screenY) {
        if (!active(map) || !io.github.kltyton.xaeroearth.client.EarthClient.themed(map)) return null;
        var point = EarthMapProjection.pick(map, screenX, screenY);
        MapRuler.preview(map, point);
        MapAnnotations.cursor(map, point);
        var access = (GuiMapAccess) map;
        if (point != null) {
            access.earth$mouseX((int) Math.floor(point.x));
            access.earth$mouseZ((int) Math.floor(point.z));
        }
        access.earth$mouseY(point == null ? Short.MAX_VALUE : (int) Math.ceil(point.y - 1e-5) - 1);
        return point;
    }

    public static boolean active() { return camera != null; }
    public static boolean active(GuiMap map) { return camera != null && map == cameraOwner; }

    public static MapCamera camera() { return camera; }


    /** Pauses map input while keeping this world's resident GPU meshes and atlas. */
    public static void suspend() {
        backgroundOwner = null; releaseWorldBackground();
        camera = null; cameraOwner = null; pointer = null; NativeMapInput.suspend();
        depthPicker.invalidate();
        transparentPending = false;
        if (renderer != null) renderer.suspend();
    }

    public static void hide() {
        backgroundOwner = null; releaseWorldBackground();
        depthPicker.close();
        MapRuler.clear();
        NativeMapInput.reset();
        camera = null; cameraOwner = null; pointer = null; bridge = null; installedScene = -1;
        viewMode = null; transparentPending = false;
        if (renderer != null) renderer.close();
        renderer = null; rendererResources = null;
    }

    private NativeMapTerrain() { }
}
