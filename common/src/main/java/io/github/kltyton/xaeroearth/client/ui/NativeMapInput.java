package io.github.kltyton.xaeroearth.client.ui;

import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.ui.measurement.MapRuler;
import io.github.kltyton.xaeroearth.client.mapmark.MapAnnotations;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;
import xaero.map.gui.GuiMap;
import xaero.map.animation.SlowingAnimation;

/** Controls the map camera without forwarding movement to the player. */
public final class NativeMapInput {
    public static final double DEFAULT_ISOMETRIC_YAW = 45.0;
    public static final double DEFAULT_ISOMETRIC_PITCH = Math.toDegrees(Math.asin(1 / Math.sqrt(3)));
    private static final long DOUBLE_CLICK_NANOS = 350_000_000L;
    private static final Set<Integer> CAMERA_KEYS = Set.of(GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A,
            GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_SPACE,
            GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
    private static final Set<Integer> pressed = new HashSet<>();
    private static GuiMap currentMap;
    private static EarthPreferences.View currentView;
    private static boolean streetSelected;
    private static boolean isometricOrientationDirty;
    private static double isometricYaw, isometricPitch;
    private static boolean topOrientationDirty;
    private static double topYaw;
    private static long nextOrientationSave;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static long lastIsometricPress;
    private static double streetYaw, streetAngle = Math.PI / 2;
    private static double streetY = Double.NaN;
    private static double streetX, streetZ;
    private static boolean rememberStreetOrigin, streetCameraWasAttached;
    private static boolean rotating;
    private static int rotatingButton = -1;
    private static double lastMouseX, lastMouseY;
    private static PlanarPan drag, releasedDrag;
    private static double panHeight = Double.NaN;

    /** Inverts the projection restricted to the fixed horizontal drag plane. */
    private record PlanarPan(MapCamera camera, double mouseX, double mouseY,
                             double dxX, double dxY, double dzX, double dzY) {
        static PlanarPan start(MapCamera camera, double mouseX, double mouseY) {
            if (camera == null || !finite(camera.x(), camera.z()) || camera.width() <= 0 || camera.height() <= 0)
                return null;
            var matrix = camera.viewProjectionMatrix();
            double xx = matrix.m00(), xz = matrix.m20(), yx = matrix.m01(), yz = matrix.m21();
            double determinant = xx * yz - xz * yx;
            if (!Double.isFinite(determinant) || Math.abs(determinant) < 1e-12) return null;
            double horizontal = 2.0 / (camera.width() * determinant), vertical = -2.0 / (camera.height() * determinant);
            return new PlanarPan(camera, mouseX, mouseY, yz * horizontal, -xz * vertical,
                    -yx * horizontal, xx * vertical);
        }
        double x(double screenX, double screenY) { return dxX * screenX + dxY * screenY; }
        double z(double screenX, double screenY) { return dzX * screenX + dzY * screenY; }
    }

    public static void prepare(GuiMap map) {
        flushOrientation();
        if (currentMap != map) {
            if (currentMap != null) close(currentMap);
            currentMap = map;
        }
        var view = view();
        if (view != currentView) {
            flushOrientation();
            restoreStreetOrigin(map);
            pressed.clear(); rotating = false;
            drag = releasedDrag = null; panHeight = Double.NaN;
            if (currentView == EarthPreferences.View.STREET) resetStreetCamera();
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
            drag = PlanarPan.start(NativeMapTerrain.camera(), mouseX, mouseY);
            releasedDrag = null;
            if (drag != null) panHeight = drag.camera().y();
        }
        boolean rotationButton = button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                || currentView == EarthPreferences.View.STREET && button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        if (!rotationButton || !canControl(map) || map.getChildAt(mouseX, mouseY).isPresent()) return false;
        rotating = true; rotatingButton = button; lastMouseX = mouseX; lastMouseY = mouseY;
        if (currentView == EarthPreferences.View.ISOMETRIC && !isometricOrientationDirty) {
            isometricYaw = EarthPreferences.ISOMETRIC_YAW.get();
            isometricPitch = EarthPreferences.ISOMETRIC_PITCH.get();
        } else if (currentView == EarthPreferences.View.TOP && !topOrientationDirty) {
            topYaw = EarthPreferences.TOP_YAW.get();
        }
        return true;
    }

    public static boolean mouseReleased(GuiMap map, double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && MapAnnotations.release(map, mouseX, mouseY)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && currentMap == map && drag != null) {
            releasedDrag = drag; drag = null;
        }
        if (button != rotatingButton || currentMap != map || !rotating) return false;
        rotating = false; rotatingButton = -1;
        flushOrientation();
        return canControl(map);
    }

    public static void pan(GuiMap map, double mouseX, double mouseY) {
        if (currentMap != map || drag == null) return;
        double dx = drag.mouseX() - mouseX, dy = drag.mouseY() - mouseY;
        double x = drag.camera().x() + drag.x(dx, dy), z = drag.camera().z() + drag.z(dx, dy);
        if (!finite(x, z)) return;
        var access = (GuiMapAccess) map;
        access.earth$cameraX(x);
        access.earth$cameraZ(z);
    }

    private static boolean finite(double x, double z) {
        return Double.isFinite(x) && Double.isFinite(z);
    }

    public static void releasePan(GuiMap map) {
        PlanarPan released = releasedDrag != null ? releasedDrag : drag;
        if (currentMap != map || released == null) return;
        drag = releasedDrag = null;
        var access = (GuiMapAccess) map;
        var nativeX = access.earth$cameraAnimationX();
        var nativeZ = access.earth$cameraAnimationZ();
        double x = access.earth$cameraX(), z = access.earth$cameraZ();
        double scale = released.camera().pixelsPerBlock();
        double dx = nativeX == null ? 0 : (nativeX.getDestination() - x) * scale;
        double dy = nativeZ == null ? 0 : (nativeZ.getDestination() - z) * scale;
        double nextX = x + released.x(dx, dy), nextZ = z + released.z(dx, dy);
        access.earth$cameraAnimationX(finite(x, nextX) ? new SlowingAnimation(x, nextX, 0.9, 0.01) : null);
        access.earth$cameraAnimationZ(finite(z, nextZ) ? new SlowingAnimation(z, nextZ, 0.9, 0.01) : null);
    }

    public static void mouseMoved(GuiMap map, double x, double y) {
        prepare(map);
        MapAnnotations.drag(map, x, y);
        if (!rotating) return;
        if (!canControl(map)) { rotating = false; return; }
        double dx = x - lastMouseX, dy = y - lastMouseY;
        if (currentView == EarthPreferences.View.ISOMETRIC) {
            isometricYaw = Math.IEEEremainder(isometricYaw + Math.toDegrees(dx * 2 / Math.max(1, map.width)), 360);
            isometricPitch = Mth.clamp(isometricPitch + Math.toDegrees(dy * 2 / Math.max(1, map.height)), 1, 90);
            isometricOrientationDirty = true;
        } else if (currentView == EarthPreferences.View.TOP && dx != 0.0) {
            topYaw = Math.IEEEremainder(topYaw + Math.toDegrees(dx * 2 / Math.max(1, map.width)), 360);
            topOrientationDirty = true;
        } else if (currentView == EarthPreferences.View.STREET) {
            streetYaw = Math.IEEEremainder(streetYaw + dx * 2 / Math.max(1, map.height), Math.PI * 2);
            streetAngle = Mth.clamp(streetAngle - dy * 2 / Math.max(1, map.height), 0.0001, Math.PI - 0.0001);
        }
        lastMouseX = x; lastMouseY = y;
    }

    public static boolean keyPressed(GuiMap map, int key, int modifiers) {
        prepare(map);
        if (key == GLFW.GLFW_KEY_ESCAPE && MapAnnotations.cancel(map)) return true;
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
        if (EarthClient.themed(map)) recoverInvalidPosition(map);
        if (!canControl(map)) { pressed.clear(); rotating = false; return; }
        if (currentView != EarthPreferences.View.STREET || pressed.isEmpty() || Double.isNaN(streetY)) return;
        double right = (pressed.contains(GLFW.GLFW_KEY_D) ? 1 : 0) - (pressed.contains(GLFW.GLFW_KEY_A) ? 1 : 0);
        double forward = (pressed.contains(GLFW.GLFW_KEY_W) ? 1 : 0) - (pressed.contains(GLFW.GLFW_KEY_S) ? 1 : 0);
        double up = (pressed.contains(GLFW.GLFW_KEY_SPACE) ? 1 : 0)
                - (pressed.contains(GLFW.GLFW_KEY_LEFT_SHIFT) || pressed.contains(GLFW.GLFW_KEY_RIGHT_SHIFT) ? 1 : 0);
        if (right == 0 && forward == 0 && up == 0) return;
        var access = (GuiMapAccess) map;
        if (!rememberStreetOrigin) {
            streetX = access.earth$cameraX();
            streetZ = access.earth$cameraZ();
            streetCameraWasAttached = GuiMapAccess.earth$attachedCamera();
            rememberStreetOrigin = true;
        }
        if (GuiMapAccess.earth$attachedCamera()) access.earth$toggleAttachedCamera(null);
        access.earth$resetCameraPosition(false);
        access.earth$cameraDestination(null);
        access.earth$cameraAnimationX(null); access.earth$cameraAnimationZ(null);
        double step = 1.5;
        double c = Math.cos(yaw()), s = Math.sin(yaw());
        access.earth$cameraX(access.earth$cameraX() + (right * c + forward * s) * step);
        access.earth$cameraZ(access.earth$cameraZ() + (right * s - forward * c) * step);
        streetY += up * step;
    }

    private static void recoverInvalidPosition(GuiMap map) {
        var access = (GuiMapAccess) map;
        if (finite(access.earth$cameraX(), access.earth$cameraZ())) return;
        var last = NativeMapTerrain.camera();
        var player = Minecraft.getInstance().player;
        if (last != null && finite(last.x(), last.z())) {
            access.earth$cameraX(last.x());
            access.earth$cameraZ(last.z());
        } else if (player != null) {
            access.earth$cameraX(player.getX());
            access.earth$cameraZ(player.getZ());
        }
        access.earth$cameraDestination(null);
        access.earth$cameraAnimationX(null);
        access.earth$cameraAnimationZ(null);
        drag = releasedDrag = null;
        panHeight = Double.NaN;
    }

    public static double yaw() {
        return switch (view()) {
            case ISOMETRIC -> Math.toRadians(isometricOrientationDirty ? isometricYaw : EarthPreferences.ISOMETRIC_YAW.get());
            case TOP -> Math.toRadians(topOrientationDirty ? topYaw : EarthPreferences.TOP_YAW.get());
            case STREET -> streetYaw;
        };
    }

    public static double angle() {
        return switch (view()) {
            case ISOMETRIC -> Math.toRadians(90 - (isometricOrientationDirty ? isometricPitch : EarthPreferences.ISOMETRIC_PITCH.get()));
            case TOP -> 0;
            case STREET -> streetAngle;
        };
    }

    /** Persistent map view for the HUD minimap (temporary world-map street view never leaks to HUD). */
    public static EarthPreferences.View hudView() {
        var saved = EarthPreferences.VIEW.get();
        return saved == EarthPreferences.View.STREET ? EarthPreferences.View.ISOMETRIC : saved;
    }

    public static double hudYaw() {
        return switch (hudView()) {
            case ISOMETRIC -> Math.toRadians(isometricOrientationDirty ? isometricYaw : EarthPreferences.ISOMETRIC_YAW.get());
            case TOP -> Math.toRadians(topOrientationDirty ? topYaw : EarthPreferences.TOP_YAW.get());
            case STREET -> 0;
        };
    }

    public static double hudAngle() {
        return switch (hudView()) {
            case ISOMETRIC -> Math.toRadians(90 - (isometricOrientationDirty ? isometricPitch : EarthPreferences.ISOMETRIC_PITCH.get()));
            case TOP, STREET -> 0;
        };
    }

    public static double height(double terrainHeight) {
        if (view() != EarthPreferences.View.STREET) {
            if (GuiMapAccess.earth$attachedCamera()) panHeight = Double.NaN;
            return Double.isNaN(panHeight) ? terrainHeight : panHeight;
        }
        if (Double.isNaN(streetY)) streetY = terrainHeight + 1 + 1.62;
        return streetY;
    }

    public static void close(GuiMap map) {
        MapAnnotations.cancel(map);
        if (currentMap != map) return;
        MapRuler.clear();
        reset();
    }

    private static void restoreStreetOrigin(GuiMap map) {
        if (rememberStreetOrigin) {
            var access = (GuiMapAccess) map;
            rememberStreetOrigin = false;
            if (streetCameraWasAttached && !GuiMapAccess.earth$attachedCamera())
                GuiMapAccess.earth$attachedCamera(true);
            access.earth$cameraX(streetX);
            access.earth$cameraZ(streetZ);
        }
    }

    public static void suspend() {
        pressed.clear(); rotating = false; flushOrientation();
    }

    public static void selectView(GuiMap map, EarthPreferences.View view) {
        prepare(map);
        if (view == EarthPreferences.View.ISOMETRIC) {
            long now = System.nanoTime();
            if (lastIsometricPress != 0 && now - lastIsometricPress <= DOUBLE_CLICK_NANOS) {
                isometricYaw = DEFAULT_ISOMETRIC_YAW;
                isometricPitch = DEFAULT_ISOMETRIC_PITCH;
                isometricOrientationDirty = true;
                flushOrientation();
                lastIsometricPress = 0;
            } else lastIsometricPress = now;
        } else lastIsometricPress = 0;
        streetSelected = view == EarthPreferences.View.STREET;
        if (!streetSelected) {
            EarthPreferences.VIEW.set(view);
            EarthPreferences.save();
        }
    }

    public static EarthPreferences.View view() {
        if (streetSelected) return EarthPreferences.View.STREET;
        var saved = EarthPreferences.VIEW.get();
        return saved == EarthPreferences.View.STREET ? EarthPreferences.View.ISOMETRIC : saved;
    }

    public static double worldMapScale(GuiMap map) {
        return ((GuiMapAccess) map).earth$scale() / Minecraft.getInstance().getWindow().getGuiScale();
    }

    public static void reset() {
        rotating = false;
        flushOrientation(true);
        lastIsometricPress = 0;
        if (currentMap != null) restoreStreetOrigin(currentMap);
        currentMap = null; currentView = null; pressed.clear(); rotating = false; rotatingButton = -1;
        streetSelected = false;
        resetStreetCamera();
        rememberStreetOrigin = false;
        drag = releasedDrag = null; panHeight = Double.NaN;
    }

    public static void flushOrientation() { flushOrientation(false); }

    private static void flushOrientation(boolean immediate) {
        if (!(isometricOrientationDirty || topOrientationDirty)
                || !immediate && (rotating || System.nanoTime() < nextOrientationSave)) return;
        nextOrientationSave = System.nanoTime() + 250_000_000L;
        try {
            if (isometricOrientationDirty) {
                EarthPreferences.ISOMETRIC_YAW.set(isometricYaw);
                EarthPreferences.ISOMETRIC_PITCH.set(isometricPitch);
            }
            if (topOrientationDirty) EarthPreferences.TOP_YAW.set(topYaw);
            EarthPreferences.save();
            isometricOrientationDirty = false;
            topOrientationDirty = false;
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            if (!(cause instanceof java.io.IOException)) throw failure;
            LOGGER.warn("Could not save map orientation; will retry", failure);
        }
    }

    private static void resetStreetCamera() {
        streetYaw = 0;
        streetAngle = Math.PI / 2;
        streetY = Double.NaN;
    }

    private NativeMapInput() { }
}
