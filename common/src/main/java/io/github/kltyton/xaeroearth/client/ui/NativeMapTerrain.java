package io.github.kltyton.xaeroearth.client.ui;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.MapTerrainHit;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import xaero.map.gui.GuiMap;

/** Draws native terrain around the entity pass using the same camera and depth target. */
public final class NativeMapTerrain extends TerrainAccess {
    private static ActiveMapResources rendererResources;
    private static MapCamera camera;
    private static XaeroMapBridge bridge;
    private static long installedScene = -1;
    private static String viewMode;
    private static double streetScale;
    private static boolean transparentPending;

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
            NativeMapInput.close(map); viewMode = null; camera = null;
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
        if (!data.capture(map, nextCamera)) { suspend(); return; }
        if (!renderer.drawOpaque(nextCamera, data.surface(), data.models(), data.visibleModels(),
                mainTarget(minecraft), data.brightness(),
                map.getMapProcessor().getCurrentCaveLayer() != Integer.MAX_VALUE)) {
            suspend();
            return;
        }
        camera = nextCamera;
        transparentPending = true;
    }

    public static void finishTerrain(GuiMap map) {
        if (renderer == null || !transparentPending) return;
        try {
            var level = Minecraft.getInstance().level;
            var processor = map.getMapProcessor();
            boolean showNavigation = !GuiMap.hiddenUI && level != null && processor != null && processor.getWorld() == level
                    && processor.getMapWorld() != null
                    && processor.isCurrentMultiworldWritable() && !processor.isConsideringNetherFairPlay()
                    && level.dimension().equals(processor.getMapWorld().getCurrentDimensionId());
            renderer.drawTransparent(showNavigation);
        } finally {
            transparentPending = false;
        }
    }

    public static Vector3f project(double x, double z) {
        if (camera == null || bridge == null) return null;
        return camera.project(x, bridge.heightAt(x, z, (int) camera.y()) + 1, z);
    }

    public static Vector3f project(double x, double y, double z) {
        return camera == null ? null : camera.project(x, y, z);
    }

    public static Vec3 pick(double x, double y) {
        return camera == null || bridge == null ? null : MapTerrainHit.pick(camera, bridge, renderer, x, y);
    }

    public static Vec3 pickKnown(double x, double y) { return pick(x, y); }

    /** Shares the visible hit between the tooltip and the native context menu. */
    public static Vec3 updatePointer(GuiMap map, double screenX, double screenY) {
        if (camera == null || !io.github.kltyton.xaeroearth.client.EarthClient.themed(map)) return null;
        var point = pick(screenX, screenY);
        var access = (GuiMapAccess) map;
        if (point != null) {
            access.earth$mouseX((int) Math.floor(point.x));
            access.earth$mouseZ((int) Math.floor(point.z));
        }
        access.earth$mouseY(point == null ? Short.MAX_VALUE : (int) Math.ceil(point.y - 1e-5) - 1);
        return point;
    }

    public static boolean active() { return camera != null; }

    public static MapCamera camera() { return camera; }


    /** Pauses map input while keeping this world's resident GPU meshes and atlas. */
    public static void suspend() {
        camera = null; NativeMapInput.suspend();
        transparentPending = false;
        if (renderer != null) renderer.suspend();
    }

    public static void hide() {
        NativeMapInput.reset();
        camera = null; bridge = null; installedScene = -1;
        viewMode = null; transparentPending = false;
        if (renderer != null) renderer.close();
        renderer = null; rendererResources = null;
    }

    private NativeMapTerrain() { }
}
