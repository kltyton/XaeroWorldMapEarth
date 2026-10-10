package io.github.kltyton.xaeroearth.client.ui;

import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import org.lwjgl.glfw.GLFW;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;
import xaero.map.gui.GuiMap;
import xaero.map.animation.SlowingAnimation;
import net.minecraft.world.phys.Vec3;

/** Controls the map camera without forwarding movement to the player. */
public final class NativeMapInput {
    public static final double ISOMETRIC_ANGLE = Math.acos(1 / Math.sqrt(3));
    private static final Set<Integer> CAMERA_KEYS = Set.of(GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A,
            GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_SPACE,
            GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
    private static final Set<Integer> pressed = new HashSet<>();
    private static GuiMap currentMap;
    private static EarthPreferences.View currentView;
    private static double isometricYaw = Math.PI / 4;
    private static double isometricAngle = ISOMETRIC_ANGLE;
    private static double streetYaw;
    private static double streetAngle = Math.PI / 2;
    private static double streetY = Double.NaN;
    private static boolean rotating;
    private static int rotatingButton = -1;
    private static double lastMouseX, lastMouseY;
    private static MapCamera panCamera, releasedPanCamera;
    private static Vec3 panAnchor;
    private static double panHeight = Double.NaN;

    public static void prepare(GuiMap map) {
        if (currentMap != map) { reset(); currentMap = map; }
        var view = EarthPreferences.VIEW.get();
        if (view != currentView) {
            pressed.clear(); rotating = false;
            panCamera = releasedPanCamera = null; panAnchor = null; panHeight = Double.NaN;
            if (view == EarthPreferences.View.STREET) streetY = Double.NaN;
            currentView = view;
        }
    }

    private static boolean canControl(GuiMap map) {
        if (!EarthClient.themed(map) || !Minecraft.getInstance().isWindowActive()
                || map.getFocused() instanceof EditBox) return false;
        var menu = ((GuiMapAccess) map).earth$rightClickMenu();
        if (menu != null && !menu.isClosed()) return false;
        for (var child : map.children()) {
            if (child instanceof DropDownWidget dropdown && dropdown.visible && !dropdown.isClosed()) return false;
        }
        return true;
    }

    public static boolean mouseClicked(GuiMap map, double mouseX, double mouseY, int button) {
        prepare(map);
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && currentView != EarthPreferences.View.STREET
                && canControl(map) && map.getChildAt(mouseX, mouseY).isEmpty()) {
            panCamera = NativeMapTerrain.camera();
            releasedPanCamera = null;
            if (panCamera != null) {
                panAnchor = planePoint(panCamera, mouseX, mouseY);
                panHeight = panCamera.y();
            }
        }
        boolean rotationButton = button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                || currentView == EarthPreferences.View.STREET && button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        if (!rotationButton || !canControl(map)
                || currentView == EarthPreferences.View.TOP || map.getChildAt(mouseX, mouseY).isPresent()) return false;
        rotating = true; rotatingButton = button; lastMouseX = mouseX; lastMouseY = mouseY;
        return true;
    }

    public static boolean mouseReleased(GuiMap map, double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && currentMap == map && panCamera != null) {
            releasedPanCamera = panCamera; panCamera = null; panAnchor = null;
        }
        if (button != rotatingButton || currentMap != map || !rotating) return false;
        rotating = false; rotatingButton = -1;
        return canControl(map);
    }

    public static void pan(GuiMap map, double mouseX, double mouseY) {
        if (currentMap != map || panCamera == null) return;
        Vec3 point = planePoint(panCamera, mouseX, mouseY);
        var access = (GuiMapAccess) map;
        access.earth$cameraX(panCamera.x() + panAnchor.x - point.x);
        access.earth$cameraZ(panCamera.z() + panAnchor.z - point.z);
    }

    private static Vec3 planePoint(MapCamera camera, double x, double y) {
        var ray = camera.ray(x, y);
        double distance = -ray[0].y / ray[1].y;
        return new Vec3(ray[0].x + ray[1].x * distance, 0, ray[0].z + ray[1].z * distance);
    }

    public static void releasePan(GuiMap map) {
        MapCamera released = releasedPanCamera != null ? releasedPanCamera : panCamera;
        if (currentMap != map || released == null) return;
        var access = (GuiMapAccess) map;
        double x = access.earth$cameraX(), z = access.earth$cameraZ();
        double dx = access.earth$cameraAnimationX().getDestination() - x;
        double dz = access.earth$cameraAnimationZ().getDestination() - z;
        double c = Math.cos(released.yaw()), s = Math.sin(released.yaw());
        double vertical = dz / Math.cos(released.angle());
        access.earth$cameraAnimationX(new SlowingAnimation(x, x + c * dx - s * vertical, 0.9, 0.01));
        access.earth$cameraAnimationZ(new SlowingAnimation(z, z + s * dx + c * vertical, 0.9, 0.01));
        panCamera = releasedPanCamera = null; panAnchor = null;
    }

    public static void mouseMoved(GuiMap map, double x, double y) {
        prepare(map);
        if (!rotating) return;
        if (!canControl(map)) { rotating = false; return; }
        double dx = x - lastMouseX, dy = y - lastMouseY;
        if (currentView == EarthPreferences.View.ISOMETRIC) {
            isometricYaw = Math.IEEEremainder(isometricYaw + dx * 2 / Math.max(1, map.width), Math.PI * 2);
            isometricAngle = net.minecraft.util.Mth.clamp(isometricAngle - dy * 2 / Math.max(1, map.height), 0, Math.PI / 2 - 0.01);
        } else if (currentView == EarthPreferences.View.STREET) {
            streetYaw = Math.IEEEremainder(streetYaw + dx * 2 / Math.max(1, map.height), Math.PI * 2);
            streetAngle = net.minecraft.util.Mth.clamp(streetAngle - dy * 2 / Math.max(1, map.height), 0.0001, Math.PI - 0.0001);
        }
        lastMouseX = x; lastMouseY = y;
    }

    public static boolean keyPressed(GuiMap map, int key, int modifiers) {
        prepare(map);
        if (currentView != EarthPreferences.View.STREET || !canControl(map) || !CAMERA_KEYS.contains(key)
                || (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0) return false;
        pressed.add(key);
        return true;
    }

    public static boolean keyReleased(GuiMap map, int key) {
        if (currentMap != map || !pressed.remove(key)) return false;
        return currentView == EarthPreferences.View.STREET && canControl(map);
    }

    public static void tick(GuiMap map) {
        prepare(map);
        if (!canControl(map)) { pressed.clear(); rotating = false; return; }
        if (currentView != EarthPreferences.View.STREET || pressed.isEmpty() || Double.isNaN(streetY)) return;
        double right = (pressed.contains(GLFW.GLFW_KEY_D) ? 1 : 0) - (pressed.contains(GLFW.GLFW_KEY_A) ? 1 : 0);
        double forward = (pressed.contains(GLFW.GLFW_KEY_W) ? 1 : 0) - (pressed.contains(GLFW.GLFW_KEY_S) ? 1 : 0);
        double up = (pressed.contains(GLFW.GLFW_KEY_SPACE) ? 1 : 0)
                - (pressed.contains(GLFW.GLFW_KEY_LEFT_SHIFT) || pressed.contains(GLFW.GLFW_KEY_RIGHT_SHIFT) ? 1 : 0);
        if (right == 0 && forward == 0 && up == 0) return;
        var access = (GuiMapAccess) map;
        if (GuiMapAccess.earth$attachedCamera()) access.earth$toggleAttachedCamera(null);
        access.earth$resetCameraPosition(false);
        access.earth$cameraDestination(null);
        access.earth$cameraAnimationX(null); access.earth$cameraAnimationZ(null);
        double step = 1.5;
        double c = Math.cos(streetYaw), s = Math.sin(streetYaw);
        access.earth$cameraX(access.earth$cameraX() + (right * c + forward * s) * step);
        access.earth$cameraZ(access.earth$cameraZ() + (right * s - forward * c) * step);
        streetY += up * step;
    }

    public static double yaw() {
        return switch (EarthPreferences.VIEW.get()) {
            case ISOMETRIC -> isometricYaw;
            case TOP -> 0;
            case STREET -> streetYaw;
        };
    }

    public static double angle() {
        return switch (EarthPreferences.VIEW.get()) {
            case ISOMETRIC -> isometricAngle;
            case TOP -> 0;
            case STREET -> streetAngle;
        };
    }

    public static double height(double terrainHeight) {
        if (EarthPreferences.VIEW.get() != EarthPreferences.View.STREET) {
            if (GuiMapAccess.earth$attachedCamera()) panHeight = Double.NaN;
            return Double.isNaN(panHeight) ? terrainHeight : panHeight;
        }
        if (Double.isNaN(streetY)) streetY = terrainHeight + 1 + 1.62;
        return streetY;
    }

    public static void close(GuiMap map) { if (currentMap == map) reset(); }

    public static void suspend() { pressed.clear(); rotating = false; }

    public static void reset() {
        currentMap = null; currentView = null; pressed.clear(); rotating = false; rotatingButton = -1;
        isometricYaw = Math.PI / 4; isometricAngle = ISOMETRIC_ANGLE;
        streetYaw = 0; streetAngle = Math.PI / 2; streetY = Double.NaN;
        panCamera = releasedPanCamera = null; panAnchor = null; panHeight = Double.NaN;
    }

    private NativeMapInput() { }
}
