package io.github.kltyton.xaeroearth.client.ui;

import com.sighs.apricityui.chunkmap.AuiMapCamera;
import com.sighs.apricityui.chunkmap.AuiActiveMapResources;
import com.sighs.apricityui.chunkmap.AuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;
import xaero.map.gui.GuiMap;

/** Draws native terrain around the entity pass using the same camera and depth target. */
public final class NativeMapTerrain {
    private static AuiNativeTerrainRenderer renderer;
    private static AuiActiveMapResources rendererResources;
    private static AuiMapCamera camera;
    private static XaeroMapBridge bridge;
    private static long installedScene = -1;
    private static String viewMode;
    private static double streetScale;
    private static boolean transparentPending;

    public static void prewarm(AuiActiveMapResources resources) {
        if (renderer != null && rendererResources == resources) return;
        hide();
        rendererResources = resources;
        renderer = new AuiNativeTerrainRenderer();
    }

    public static void draw(GuiMap map, XaeroMapBridge data, AuiActiveMapResources resources) {
        transparentPending = false;
        if (!data.prepareFrame(map)) { suspend(); return; }
        prewarm(resources);
        bridge = data;
        if (installedScene != data.sceneKey()) {
            renderer.resetSurface();
            NativeMapInput.reset(); viewMode = null; camera = null;
            installedScene = data.sceneKey();
        }
        var access = (GuiMapAccess) map;
        NativeMapInput.prepare(map);
        Minecraft minecraft = Minecraft.getInstance();
        double scale = access.earth$scale() / minecraft.getWindow().getGuiScale();
        String mode = EarthPreferences.VIEW.get().name().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals(viewMode)) { viewMode = mode; if (mode.equals("street")) streetScale = access.earth$scale(); }
        double height = data.heightAt(access.earth$cameraX(), access.earth$cameraZ(),
                minecraft.player == null ? 64 : minecraft.player.blockPosition().getY());
        height = NativeMapInput.height(height);
        var nextCamera = new AuiMapCamera(access.earth$cameraX(), height, access.earth$cameraZ(),
                scale, NativeMapInput.yaw(), NativeMapInput.angle(), mode, map.width, map.height,
                data.presentationUnit(), mode.equals("street") ? Math.clamp(Math.toDegrees(2 * Math.atan(
                        Math.tan(Math.toRadians(75) / 2) * streetScale / access.earth$scale())), 1, 170) : 75);
        if (!data.capture(map, nextCamera)) { suspend(); return; }
        if (!renderer.drawOpaque(nextCamera, data.surface(), data.models(), data.visibleModels(),
                minecraft.getMainRenderTarget(), data.brightness(),
                map.getMapProcessor().getCurrentCaveLayer() != Integer.MAX_VALUE)) {
            suspend();
            return;
        }
        camera = nextCamera;
        transparentPending = true;
    }

    public static void finishTerrain() {
        if (renderer == null || !transparentPending) return;
        try {
            renderer.drawTransparent();
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

    public static net.minecraft.world.phys.Vec3 pick(double screenX, double screenY) {
        if (camera == null || bridge == null) return null;
        var ray = camera.ray(screenX, screenY);
        if (Math.abs(ray[1].y) < 0.00001) return null;
        double distance = (0 - ray[0].y) / ray[1].y;
        for (int i = 0; i < 12; i++) {
            double x = camera.x() + ray[0].x + ray[1].x * distance;
            double z = camera.z() + ray[0].z + ray[1].z * distance;
            double terrainY = bridge.heightAt(x, z, (int) camera.y()) + 1;
            double next = (terrainY - camera.y() - ray[0].y) / ray[1].y;
            if (Math.abs(next - distance) < 0.01) {
                if (next < 0) return null;
                return new net.minecraft.world.phys.Vec3(x, terrainY, z);
            }
            distance = next;
        }
        return null;
    }

    public static boolean active() { return camera != null; }

    public static AuiMapCamera camera() { return camera; }

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
