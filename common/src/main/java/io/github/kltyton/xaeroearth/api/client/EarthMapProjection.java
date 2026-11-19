package io.github.kltyton.xaeroearth.api.client;

import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.ui.NativeMinimapTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import xaero.map.gui.GuiMap;

/** Client-only projection for addons drawing with the existing Xaero map renderer. */
public final class EarthMapProjection {
    public record Point(float x, float y, float depth) { }

    /** Coordinates are GUI pixels with the origin at the top left of the world map. */
    public static Point worldMap(GuiMap map, double worldX, double worldY, double worldZ) {
        if (!ready(map) || !finite(worldX, worldY, worldZ)) return null;
        return point(NativeMapTerrain.project(worldX, worldY, worldZ));
    }

    /** Returns a mapped surface in the displayed world, dimension and cave layer, or null. */
    public static Vec3 pick(GuiMap map, double guiX, double guiY) {
        if (!ready(map) || !finite(guiX, guiY, 0) || guiX < 0 || guiY < 0 || guiX >= map.width || guiY >= map.height)
            return null;
        return NativeMapTerrain.pick(guiX, guiY);
    }

    /** Uses Xaero's centered marker offsets, available only during its minimap marker pass. */
    public static Point minimap(double worldX, double worldY, double worldZ) {
        if (!Minecraft.getInstance().isSameThread() || !finite(worldX, worldY, worldZ)) return null;
        return point(NativeMinimapTerrain.projectMarker(worldX, worldY, worldZ));
    }

    private static boolean ready(GuiMap map) {
        return map != null && Minecraft.getInstance().isSameThread() && EarthClient.themed(map)
                && NativeMapTerrain.active(map);
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private static Point point(Vector3f projected) {
        return projected != null && finite(projected.x, projected.y, projected.z) && Math.abs(projected.z) <= 1
                ? new Point(projected.x, projected.y, projected.z) : null;
    }

    private EarthMapProjection() { }
}
