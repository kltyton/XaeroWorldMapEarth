package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.xaeroearth.client.EarthPreferences;

/** Uses projected block size and distance from the map focus to select terrain detail. */
public final class TerrainDetail {
    private TerrainDetail() {}

    public static double radius(double pixelsPerBlock, double viewportRadius) {
        return radius(pixelsPerBlock, viewportRadius, 0.0625, 0.1875);
    }

    /** Permit provisional cached models only near the focus while their inputs are checked. */
    public static double provisionalRadius(double pixelsPerBlock, double viewportRadius) {
        return radius(pixelsPerBlock, viewportRadius, 0.125, 0.375);
    }

    private static double radius(double pixelsPerBlock, double viewportRadius, double minimumScale, double range) {
        double detail = Math.max(0, Math.min(1, (pixelsPerBlock - minimumScale) / range));
        int configured = EarthPreferences.ISOMETRIC_DETAIL_DISTANCE.get();
        int limit = fast() ? Math.min(configured, 192) : configured;
        return Math.min(viewportRadius, limit * detail);
    }

    public static boolean fast() { return EarthPreferences.FAST_TERRAIN.get(); }

    public static boolean intersects(double focusX, double focusZ, double radius, double x, double z, int size) {
        if (radius <= 0) return false;
        double dx = Math.max(0, Math.max(x - focusX, focusX - x - size));
        double dz = Math.max(0, Math.max(z - focusZ, focusZ - z - size));
        return dx * dx + dz * dz <= radius * radius;
    }

    public static int mappedLevel(double pixelsPerBlock, double distance, double radius) {
        double cell = Math.max(1, Math.max(0.75 / pixelsPerBlock, 2 * distance / Math.max(16, radius)));
        return Math.min(3, Math.getExponent(cell));
    }

    public static double captureScale(double pixelsPerBlock) {
        return Math.scalb(1.0, Math.getExponent(pixelsPerBlock));
    }
}
