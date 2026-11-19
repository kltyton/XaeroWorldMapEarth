package io.github.kltyton.xaeroearth.client.chunkmap;

import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Shared world/screen projection for native terrain, entities and map controls. */
public final class MapCamera {
    private final double x, y, z, pixelsPerBlock, yaw, angle;
    private final String mode;
    private final int width, height;
    private final double unit, fieldOfView;
    private final int viewWidth, viewHeight;
    private final double viewX, viewY, roll;
    private final boolean matchPlanarFootprint, minimap;
    private Vector3f cachedEye;
    private Matrix4f cachedView, cachedViewportProjection, cachedProjection, cachedViewProjection;

    public MapCamera(double x, double y, double z, double pixelsPerBlock,
                     double yaw, double angle, String mode, int width, int height, double unit, double fieldOfView,
                     int viewWidth, int viewHeight, double viewX, double viewY, double roll,
                     boolean matchPlanarFootprint, boolean minimap) {
        this.x = x; this.y = y; this.z = z; this.pixelsPerBlock = pixelsPerBlock;
        this.yaw = yaw; this.angle = angle; this.mode = mode;
        this.width = width; this.height = height; this.unit = unit; this.fieldOfView = fieldOfView;
        this.viewWidth = viewWidth; this.viewHeight = viewHeight;
        this.viewX = viewX; this.viewY = viewY; this.roll = roll;
        this.matchPlanarFootprint = matchPlanarFootprint; this.minimap = minimap;
    }
    public MapCamera(double x, double y, double z, double pixelsPerBlock,
                     double yaw, double angle, String mode, int width, int height, double unit, double fieldOfView,
                     int viewWidth, int viewHeight, double viewX, double viewY, double roll,
                     boolean matchPlanarFootprint) {
        this(x, y, z, pixelsPerBlock, yaw, angle, mode, width, height, unit, fieldOfView,
                viewWidth, viewHeight, viewX, viewY, roll, matchPlanarFootprint, false);
    }
    public MapCamera(double x, double y, double z, double pixelsPerBlock, double yaw, double angle, String mode,
                     int width, int height, double unit, double fieldOfView) {
        this(x, y, z, pixelsPerBlock, yaw, angle, mode, width, height, unit, fieldOfView, width, height, 0, 0, 0, false);
    }
    public MapCamera(double x, double y, double z, double pixelsPerBlock,
                            double yaw, String mode, int width, int height, double unit, double fieldOfView) {
        this(x, y, z, pixelsPerBlock, yaw, mode.equals("top") ? 0 : mode.equals("street")
                ? Math.PI / 2 : Math.acos(1 / Math.sqrt(3)), mode, width, height, unit, fieldOfView);
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public double pixelsPerBlock() { return pixelsPerBlock; }
    public double yaw() { return yaw; }
    public double angle() { return angle; }
    public String mode() { return mode; }
    public int width() { return width; }
    public int height() { return height; }
    public double unit() { return unit; }
    public double fieldOfView() { return fieldOfView; }
    public int viewWidth() { return viewWidth; }
    public int viewHeight() { return viewHeight; }
    public double viewX() { return viewX; }
    public double viewY() { return viewY; }
    public double roll() { return roll; }
    public boolean matchPlanarFootprint() { return matchPlanarFootprint; }
    public boolean minimap() { return minimap; }

    public Matrix4f viewProjectionMatrix() {
        Matrix4f cached = cachedViewProjection;
        if (cached == null) {
            cached = projectionMatrix().mul(viewMatrix());
            cachedViewProjection = cached;
        }
        return new Matrix4f(cached);
    }

    /** Camera properties that affect viewport rays, before moving the viewport or its world anchor. */
    public boolean sameViewportProjection(MapCamera other) {
        return other != null && yaw == other.yaw && angle == other.angle && roll == other.roll
                && pixelsPerBlock == other.pixelsPerBlock && unit == other.unit
                && fieldOfView == other.fieldOfView && mode.equals(other.mode)
                && viewWidth == other.viewWidth && viewHeight == other.viewHeight
                && matchPlanarFootprint == other.matchPlanarFootprint;
    }

    public Vector3f eyeOffset() {
        Vector3f cached = cachedEye;
        if (cached == null) {
            float cameraAngle = mode.equals("top") ? 0.0001f : (float) Math.max(0.0001, Math.min(angle, Math.PI - 0.0001));
            float distance = mode.equals("street") ? 0.0001f * (float) unit : orthographicRange();
            cached = new Vector3f(-(float) Math.sin(yaw) * (float) Math.sin(cameraAngle) * distance,
                    (float) Math.cos(cameraAngle) * distance, (float) Math.cos(yaw) * (float) Math.sin(cameraAngle) * distance);
            cachedEye = cached;
        }
        return new Vector3f(cached);
    }

    public Matrix4f viewMatrix() {
        Matrix4f cached = cachedView;
        if (cached == null) {
            cached = new Matrix4f().lookAt(eyeOffset(), new Vector3f(), new Vector3f(0, 1, 0));
            if (matchPlanarFootprint && mode.equals("isometric")) {
                // Match Xaero's horizontal footprint at the camera's reference elevation.
                // Keep the original contribution of height to the vertical screen position.
                float cosine = (float) Math.max(0.0001, Math.cos(angle));
                cached.m01(cached.m01() / cosine).m21(cached.m21() / cosine);
            }
            cachedView = cached;
        }
        return new Matrix4f(cached);
    }

    public Matrix4f projectionMatrix() {
        Matrix4f cached = cachedProjection;
        if (cached == null) {
            Matrix4f projection = viewportProjectionMatrix();
            cached = viewWidth == width && viewHeight == height && viewX == 0 && viewY == 0 ? projection
                    : new Matrix4f().translation((float) ((viewWidth + 2 * viewX) / width - 1),
                            (float) (1 - (viewHeight + 2 * viewY) / height), 0)
                            .scale((float) viewWidth / width, (float) viewHeight / height, 1).mul(projection);
            cachedProjection = cached;
        }
        return new Matrix4f(cached);
    }

    public Matrix4f viewportProjectionMatrix() {
        Matrix4f cached = cachedViewportProjection;
        if (cached == null) {
            float halfHeight = (float) (viewHeight / (2 * pixelsPerBlock));
            float halfWidth = halfHeight * viewWidth / viewHeight;
            var projection = mode.equals("street")
                    ? new Matrix4f().perspective((float) Math.toRadians(fieldOfView), (float) viewWidth / viewHeight,
                            0.01f * (float) unit, 2000 * (float) unit)
                    : new Matrix4f().ortho(-halfWidth, halfWidth, -halfHeight, halfHeight, (float) unit, 2 * orthographicRange());
            if (roll != 0) {
                float aspect = (float) viewWidth / viewHeight;
                projection = new Matrix4f().scaling(1 / aspect, 1, 1).rotateZ((float) roll)
                        .scale(aspect, 1, 1).mul(projection);
            }
            cached = projection;
            cachedViewportProjection = cached;
        }
        return new Matrix4f(cached);
    }

    private float orthographicRange() {
        return (float) (768 * unit / Math.max(0.05, Math.cos(angle))
                + Math.hypot(viewWidth, viewHeight) / (2 * pixelsPerBlock));
    }

    /** Converts the shared projection to Minecraft's reverse-Z device clip range. */
    public Matrix4f nativeProjectionMatrix(boolean zeroToOne) {
        return new Matrix4f().m22(zeroToOne ? -0.5F : -1F).m32(zeroToOne ? 0.5F : 0F).mul(projectionMatrix());
    }

    /** Converts OpenGL clip depth to the device range without reversing its depth order. */
    public Matrix4f nativeForwardProjectionMatrix(boolean zeroToOne) {
        return zeroToOne ? new Matrix4f().m22(0.5F).m32(0.5F).mul(projectionMatrix()) : projectionMatrix();
    }

    public Vector3f project(double worldX, double worldY, double worldZ) {
        Matrix4f vp = cachedViewProjection;
        if (vp == null) vp = cachedViewProjection = projectionMatrix().mul(viewMatrix());
        Vector4f projected = vp.transform(new Vector4f((float) (worldX - x),
                (float) (worldY - y), (float) (worldZ - z), 1));
        if (projected.w <= 0) return new Vector3f(Float.NaN, Float.NaN, Float.NaN);
        projected.div(projected.w);
        return new Vector3f((projected.x + 1) * width / 2, (1 - projected.y) * height / 2, projected.z);
    }

    public Vector3f[] ray(double screenX, double screenY) {
        Matrix4f inverse = viewProjectionMatrix().invert();
        float nx = (float) (screenX / width * 2 - 1), ny = (float) (1 - screenY / height * 2);
        Vector4f near = inverse.transform(new Vector4f(nx, ny, -1, 1)); near.div(near.w);
        Vector4f far = inverse.transform(new Vector4f(nx, ny, 1, 1)); far.div(far.w);
        return new Vector3f[]{new Vector3f(near.x, near.y, near.z),
                new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z).normalize()};
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(otherInstance(o))) return false;
        MapCamera other = (MapCamera) o;
        return Double.compare(x, other.x) == 0 && Double.compare(y, other.y) == 0 && Double.compare(z, other.z) == 0
                && Double.compare(pixelsPerBlock, other.pixelsPerBlock) == 0
                && Double.compare(yaw, other.yaw) == 0 && Double.compare(angle, other.angle) == 0
                && width == other.width && height == other.height
                && Double.compare(unit, other.unit) == 0 && Double.compare(fieldOfView, other.fieldOfView) == 0
                && viewWidth == other.viewWidth && viewHeight == other.viewHeight
                && Double.compare(viewX, other.viewX) == 0 && Double.compare(viewY, other.viewY) == 0
                && Double.compare(roll, other.roll) == 0
                && matchPlanarFootprint == other.matchPlanarFootprint && minimap == other.minimap
                && Objects.equals(mode, other.mode);
    }

    private static boolean otherInstance(Object o) { return o instanceof MapCamera; }

    @Override public int hashCode() {
        return Objects.hash(x, y, z, pixelsPerBlock, yaw, angle, mode, width, height, unit, fieldOfView,
                viewWidth, viewHeight, viewX, viewY, roll, matchPlanarFootprint, minimap);
    }

    @Override public String toString() {
        return "MapCamera[x=" + x + ", y=" + y + ", z=" + z + ", pixelsPerBlock=" + pixelsPerBlock
                + ", yaw=" + yaw + ", angle=" + angle + ", mode=" + mode + ", width=" + width + ", height=" + height + "]";
    }
}
