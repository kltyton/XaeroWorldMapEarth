package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTiles;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;

/** Anchors orthographic image tiles in projected world coordinates instead of screen coordinates. */
public final class ProjectedGrid {
    public static final int TILE_PIXELS = 256;
    public static final int GUTTER = 1;
    public static final int IMAGE_PIXELS = TILE_PIXELS + 2 * GUTTER;

    public record Cell(long x, long y) { }
    public record Bounds(long minX, long minY, long maxX, long maxY) { }
    record Depth(double near, double far) {
        Depth include(Depth other) { return new Depth(Math.min(near, other.near), Math.max(far, other.far)); }
    }
    public record View(List<Integer> matrixBits, int width, int height) {
        public View { matrixBits = List.copyOf(matrixBits); }
    }

    private final double ux, uy, uz, vx, vy, vz;
    private final double zx, zy, zz, cameraX, cameraY, cameraZ;
    private final double depthOrigin, orderX, orderZ;
    private final double anchorX, anchorY;
    private final int width, height;
    private final View view;
    private final Matrix4f projection;

    public ProjectedGrid(MapCamera camera, int width, int height) {
        if (camera.mode().equals("street") || camera.width() <= 0 || camera.height() <= 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Projected image tiles require an orthographic viewport");
        Matrix4f cameraView = camera.viewMatrix();
        Matrix4f transform = camera.projectionMatrix().mul(cameraView);
        float[] values = transform.get(new float[16]);
        var bits = new ArrayList<Integer>(values.length);
        // Viewport translation changes the screen anchor, not the projected world tile.
        for (int i = 0; i < values.length; i++)
            bits.add(Float.floatToIntBits(i == 12 || i == 13 ? 0.0F : values[i]));
        view = new View(bits, width, height);
        projection = camera.projectionMatrix();
        double xScale = width * 0.5, yScale = height * -0.5;
        ux = transform.m00() * xScale; uy = transform.m10() * xScale; uz = transform.m20() * xScale;
        vx = transform.m01() * yScale; vy = transform.m11() * yScale; vz = transform.m21() * yScale;
        zx = transform.m02(); zy = transform.m12(); zz = transform.m22();
        depthOrigin = transform.m32();
        orderX = cameraView.m02(); orderZ = cameraView.m22();
        cameraX = camera.x(); cameraY = camera.y(); cameraZ = camera.z();
        anchorX = (transform.m30() + 1.0) * xScale - worldX(camera.x(), camera.y(), camera.z());
        anchorY = (1.0 - transform.m31()) * height * 0.5 - worldY(camera.x(), camera.y(), camera.z());
        this.width = width;
        this.height = height;
    }

    public double worldX(double x, double y, double z) { return ux * x + uy * y + uz * z; }
    public double worldY(double x, double y, double z) { return vx * x + vy * y + vz * z; }
    public double screenX(double x, double y, double z) { return worldX(x, y, z) + anchorX; }
    public double screenY(double x, double y, double z) { return worldY(x, y, z) + anchorY; }
    public double cellScreenX(long x) { return x * (double) TILE_PIXELS + anchorX; }
    public double cellScreenY(long y) { return y * (double) TILE_PIXELS + anchorY; }
    public View view() { return view; }

    /** Converts a cached tile's depth to this camera's depth after orthographic panning. */
    public double depthOffsetFrom(ProjectedGrid baked, boolean reverseZ) {
        if (!view.equals(baked.view)) throw new IllegalArgumentException("Projected views differ");
        return depthOffsetFrom(baked.cameraX, baked.cameraY, baked.cameraZ, reverseZ);
    }

    public double depthOffsetFrom(double bakedX, double bakedY, double bakedZ, boolean reverseZ) {
        double delta = zx * (bakedX - cameraX) + zy * (bakedY - cameraY) + zz * (bakedZ - cameraZ);
        return delta * (reverseZ ? -0.5 : 0.5);
    }

    double drawOrder(ChunkTiles.Tile tile) {
        return orderX * (tile.x() * 32.0 + 16) + orderZ * (tile.z() * 32.0 + 16);
    }

    Depth depthRange(ChunkTiles.Tile tile, DetailedGeometry.Bounds bounds) {
        double origin = zx * tile.x() * 32.0 + zz * tile.z() * 32.0 + depthOrigin;
        double x0 = zx * bounds.minX(), x1 = zx * bounds.maxX();
        double y0 = zy * bounds.minY(), y1 = zy * bounds.maxY();
        double z0 = zz * bounds.minZ(), z1 = zz * bounds.maxZ();
        double near = origin + Math.min(x0, x1) + Math.min(y0, y1) + Math.min(z0, z1);
        double far = origin + Math.max(x0, x1) + Math.max(y0, y1) + Math.max(z0, z1);
        return new Depth(near, far);
    }

    double depthTranslation(double x, double y, double z) { return zx * x + zy * y + zz * z; }

    static boolean containsDepth(Depth range, double translation) {
        return range.near() - translation > -0.99999 && range.far() - translation < 0.99999;
    }

    /** Crops the current viewport projection to an individual physical-pixel tile. */
    public Matrix4f tileProjection(Cell cell) {
        double x = cellScreenX(cell.x()) - GUTTER, y = cellScreenY(cell.y()) - GUTTER;
        return new Matrix4f().translation((float) ((width - 2 * x - IMAGE_PIXELS) / IMAGE_PIXELS),
                (float) ((IMAGE_PIXELS - height + 2 * y) / IMAGE_PIXELS), 0)
                .scale((float) width / IMAGE_PIXELS, (float) height / IMAGE_PIXELS, 1).mul(projection);
    }

    public Bounds visible() {
        // Match clip() at the pixel centers. A sliver between the viewport edge and its
        // first/last pixel can never cover a pixel or cause a switch from image to mesh.
        return new Bounds(cell(0.5 - anchorX), cellAbove(0.5 - anchorY), cell(width - 0.5 - anchorX),
                cellAbove(height - 0.5 - anchorY));
    }

    /** An offscreen face can still affect the offscreen portion of a visible image. */
    public boolean contributesTo(ChunkTiles.Tile tile, DetailedGeometry.Bounds bounds, Bounds cells) {
        double x = tile.x() * 32.0, z = tile.z() * 32.0;
        var area = covering(x + bounds.minX(), bounds.minY(), z + bounds.minZ(),
                x + bounds.maxX(), bounds.maxY(), z + bounds.maxZ());
        return area.minX() <= cells.maxX() && area.maxX() >= cells.minX()
                && area.minY() <= cells.maxY() && area.maxY() >= cells.minY();
    }

    ProjectedDrawCoverage.Clip clip(Cell cell) {
        return clip(cell, 1, 1, 0, 0);
    }

    /** Assigns each pixel center to one cell in the live raster, including scaled or jittered pack passes. */
    public ProjectedDrawCoverage.Clip clip(Cell cell, float scaleX, float scaleY, float offsetX, float offsetY) {
        int x0 = Math.max(0, pixelBegin(cellScreenX(cell.x()), scaleX, offsetX));
        int x1 = Math.min(width, pixelBegin(cellScreenX(cell.x() + 1), scaleX, offsetX));
        int y0 = Math.max(0, pixelBegin(height - cellScreenY(cell.y() + 1), scaleY, offsetY));
        int y1 = Math.min(height, pixelBegin(height - cellScreenY(cell.y()), scaleY, offsetY));
        return x0 < x1 && y0 < y1 ? new ProjectedDrawCoverage.Clip(x0, y0, x1 - x0, y1 - y0) : null;
    }

    private static int pixelBegin(double coordinate, float scale, float offset) {
        return (int) Math.ceil(coordinate * scale + offset - 0.5);
    }

    /** Covers every projected tile intersected by a world-space axis-aligned volume. */
    public Bounds covering(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid world bounds");
        double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? minX : maxX;
            double y = (i & 2) == 0 ? minY : maxY;
            double z = (i & 4) == 0 ? minZ : maxZ;
            double px = worldX(x, y, z), py = worldY(x, y, z);
            left = Math.min(left, px); right = Math.max(right, px);
            top = Math.min(top, py); bottom = Math.max(bottom, py);
        }
        return new Bounds(cell(left - GUTTER), cell(top - GUTTER), cell(right + GUTTER), cell(bottom + GUTTER));
    }

    private static long cell(double coordinate) { return (long) Math.floor(coordinate / TILE_PIXELS); }
    // In screen Y, the lower-left raster scissor includes the bottom edge and excludes the top.
    private static long cellAbove(double coordinate) { return (long) Math.ceil(coordinate / TILE_PIXELS) - 1; }
}
