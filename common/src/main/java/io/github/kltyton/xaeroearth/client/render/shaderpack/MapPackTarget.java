package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntPredicate;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Supplies a separate main target, camera and pipeline only while a shader pack draws a map. */
public final class MapPackTarget implements AutoCloseable {
    private static final ThreadLocal<MapPackTarget> CURRENT = new ThreadLocal<>();
    private static final AtomicLong SHADER_GENERATIONS = new AtomicLong();
    private final RenderTarget target;
    private final Vector3d camera;
    private MapPackImageBindings images;
    private MapPackEye eye;
    private Object pipeline;
    private ShadowPass shadows;
    private Matrix4f shadowView;
    private Matrix4f shadowProjection;
    private MapPackDepthMapping raster = MapPackDepthMapping.FULL_SIZE;
    private boolean materialTiles;
    private IntPredicate materialWaving;
    private final TreeSet<String> materialProfiles = new TreeSet<>();
    private int renderDistance;
    private int shadowDistance;
    private boolean nativeTerrain;
    private boolean transparent;
    private boolean shadowTerrain;
    private boolean solidTerrain;
    private String shaderSuffix;
    private Matrix4f previousView, previousProjection;
    private Function<Matrix4f, ?> previousMatrixAdapter;
    private boolean clearTemporalTargets;

    @FunctionalInterface
    public interface ShadowPass {
        void draw(org.joml.Matrix4f view, org.joml.Matrix4f projection, boolean transparent);
    }

    public MapPackTarget(RenderTarget target, Vector3d camera) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested map shader pack pass");
        this.target = target;
        this.camera = camera;
        CURRENT.set(this);
    }

    public static RenderTarget current() {
        var scope = CURRENT.get();
        return scope == null ? null : scope.target;
    }

    public static Vector3d camera() {
        var scope = CURRENT.get();
        return scope == null ? null : new Vector3d(scope.camera);
    }

    void previousMatrices(MapPackProjection previous, Function<Matrix4f, ?> adapter, boolean clear) {
        previousView = previous.view();
        previousProjection = previous.projection();
        previousMatrixAdapter = adapter;
        clearTemporalTargets = clear;
    }

    public static boolean clearTemporalTargets() {
        var scope = CURRENT.get();
        return scope != null && scope.clearTemporalTargets;
    }

    public static Object previousMatrix(boolean projection) {
        var scope = CURRENT.get();
        if (scope == null || scope.previousMatrixAdapter == null) return null;
        Matrix4f previous = projection ? scope.previousProjection : scope.previousView;
        return scope.previousMatrixAdapter.apply(new Matrix4f(previous));
    }

    public static void beforeImageBinding(int unit) {
        var scope = CURRENT.get();
        if (scope == null) return;
        if (scope.images == null) scope.images = new MapPackImageBindings();
        scope.images.remember(unit);
    }

    public void sampleEye(MapCamera map, ClientLevel level, ChunkTileSource source) { eye = MapPackEye.at(map, level, source); }

    public static MapPackEye eye() {
        var scope = CURRENT.get();
        return scope == null ? null : scope.eye;
    }

    public void pipeline(Object pipeline) { this.pipeline = pipeline; }

    public void beginLegacyShaderCompilation() {
        shaderSuffix = "_xaeroearth_" + SHADER_GENERATIONS.incrementAndGet();
    }

    public static String legacyShaderName(String name) {
        var scope = CURRENT.get();
        return scope == null || scope.shaderSuffix == null ? name : name + scope.shaderSuffix;
    }

    public void endLegacyShaderCompilation() { shaderSuffix = null; }
    public void shadows(ShadowPass pass) { this.shadows = pass; }
    public void renderDistance(int blocks) { this.renderDistance = blocks; }
    public void shadowDistance(int distance) { this.shadowDistance = distance; }

    public static int shadowDistance() {
        var scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("No active map shader pass");
        return scope.shadowDistance;
    }

    public static int renderDistance() {
        var scope = CURRENT.get();
        return scope == null ? 0 : scope.renderDistance;
    }

    public void terrain(boolean transparent, boolean shadow, boolean solid) {
        this.nativeTerrain = true;
        this.transparent = transparent;
        this.shadowTerrain = shadow;
        this.solidTerrain = solid;
    }

    public void endTerrain() { nativeTerrain = false; }

    public static int terrainLayer() {
        var scope = CURRENT.get();
        return scope == null || !scope.nativeTerrain ? -1
                : scope.solidTerrain && !scope.shadowTerrain ? 4
                : (scope.shadowTerrain ? 2 : 0) + (scope.transparent ? 1 : 0);
    }

    public static Object pipeline() {
        var scope = CURRENT.get();
        return scope == null ? null : scope.pipeline;
    }

    public static ShadowPass shadows() {
        var scope = CURRENT.get();
        return scope == null ? null : scope.shadows;
    }

    public static void shadowMatrices(Matrix4f view, Matrix4f projection) {
        var scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("No active map shader pass");
        scope.shadowView = new Matrix4f(view);
        scope.shadowProjection = new Matrix4f(projection);
    }

    public static Matrix4f shadowView() {
        var scope = CURRENT.get();
        return scope == null || scope.shadowView == null ? null : new Matrix4f(scope.shadowView);
    }

    public static Matrix4f shadowProjection() {
        var scope = CURRENT.get();
        return scope == null || scope.shadowProjection == null ? null : new Matrix4f(scope.shadowProjection);
    }

    public static void captureRaster(MapPackDepthMapping.Reader reader, int program) {
        var scope = CURRENT.get();
        if (scope != null && scope.raster == MapPackDepthMapping.FULL_SIZE)
            scope.raster = reader.read(program, scope.target.width, scope.target.height);
    }

    public MapPackDepthMapping raster() { return raster; }

    static MapPackDepthMapping activeRaster() {
        var scope = CURRENT.get();
        return scope == null ? MapPackDepthMapping.FULL_SIZE : scope.raster;
    }

    public void materialTiles(boolean enabled) { materialTiles = enabled; }

    public static boolean buildsMaterialTiles() {
        var scope = CURRENT.get();
        return scope != null && scope.materialTiles;
    }

    static void materialProfile(String fingerprint, IntPredicate waving) {
        var scope = CURRENT.get();
        if (scope == null || !scope.materialProfiles.add(fingerprint) || waving == null) return;
        scope.materialWaving = scope.materialWaving == null ? waving : scope.materialWaving.or(waving);
    }

    String materialProfile() {
        return materialProfiles.isEmpty() ? null : String.join(",", materialProfiles);
    }

    IntPredicate materialWaving() { return materialWaving; }

    @Override public void close() {
        try {
            if (images != null) images.restore();
        } finally {
            CURRENT.remove();
        }
    }
}
