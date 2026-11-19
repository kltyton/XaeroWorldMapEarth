package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.Draw;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileIndex;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** One Iris pipeline and a color/depth target per Earth view. */
public final class GlMapShaderPackRenderer implements AutoCloseable {
    public enum Layers { NONE, OPAQUE, ALL }
    public record Result(Layers layers, List<Draw> remaining) {
        private static final Result NONE = new Result(Layers.NONE, List.of());
    }
    private final MapPackGeometry geometry = new MapPackGeometry();
    private final GlMapPackComposite composite = new GlMapPackComposite();
    private TextureTarget target;
    private MapPackDepthMapping raster = MapPackDepthMapping.FULL_SIZE;
    private final MapPackTemporalState temporal = new MapPackTemporalState();
    private Object pipeline;
    private String materialProfile;
    private MapPackStorageBindings storage;
    private ClientLevel level;
    private MapScope mapScope;
    private IrisPackAccess.Selection selection;
    private IrisPackAccess.Selection rejected;
    private static boolean selectionFailureReported;

    public static boolean available() { return IrisPackAccess.get() != null; }

    public static boolean packSelected() {
        var iris = IrisPackAccess.get();
        if (iris == null) return false;
        try {
            boolean selected = iris.selection() != null;
            selectionFailureReported = false;
            return selected;
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError critical) throw critical;
            if (failure instanceof ThreadDeath critical) throw critical;
            if (!selectionFailureReported) LogUtils.getLogger().warn("Cannot check the active map shader pack", failure);
            selectionFailureReported = true;
            return true;
        }
    }

    public static Matrix4f shadowMatrix(float radius, float near, float far, Float fov) {
        try { return IrisPackAccess.get().shadowMatrix(radius, near, far, fov); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Iris shadow projection is unavailable", failure); }
    }

    public Result render(RenderTarget destination, KuiMapDepthTarget depth, MapCamera camera,
                         List<Draw> draws, List<Draw> casters, Set<Draw> solids, Set<Draw> contained,
                         List<Draw> projectionDraws, List<ProjectedTileIndex.Mesh> projectionMeshes, ChunkTileSource source,
                         MapScope currentScope, MapPackLodTransition<Integer> transition) {
        var minecraft = Minecraft.getInstance();
        if (!EarthPreferences.SHADER_PACK.get() || minecraft.level == null) {
            if (target != null) release();
            rejected = null;
            return Result.NONE;
        }
        IrisPackAccess iris = IrisPackAccess.get();
        if (iris == null) return Result.NONE;
        try {
            var desired = iris.selection();
            if (desired == null) {
                if (target != null) release();
                rejected = null;
                return Result.NONE;
            }
            if (draws.isEmpty()) return Result.NONE;
            if (desired.equals(rejected)) return Result.NONE;
            var region = MapPackRegion.of(camera, destination.width, destination.height);
            var packView = region.camera();
            Result rendered;
            try (var graphics = new KuiMapRenderState(null); var gl = new PackGlState()) {
                boolean resized = false;
                boolean scopeChanged = mapScope != null && currentScope != null && !currentScope.equals(mapScope);
                if (!desired.equals(selection) || target == null || level != minecraft.level || scopeChanged) {
                    releaseTarget();
                    selection = desired;
                    level = minecraft.level;
                    target = new TextureTarget(region.width(), region.height(), true, Minecraft.ON_OSX);
                    storage = iris.storage(desired);
                } else if (target.width != region.width() || target.height != region.height()) {
                    // Iris tracks the depth attachment through the target instance's version.
                    target.resize(region.width(), region.height(), Minecraft.ON_OSX);
                    temporal.reset();
                    resized = true;
                }
                if (currentScope != null) mapScope = currentScope;
                try (var buffers = storage == null ? null : storage.enter()) {
                    rendered = renderScene(iris, desired, packView, draws, casters, solids, contained, projectionDraws,
                            projectionMeshes, minecraft, resized, source);
                }
            }
            if (rendered.layers() != Layers.NONE) composite.render(target, destination, depth, raster, region, transition);
            return rendered;
        } catch (Throwable error) {
            if (error instanceof VirtualMachineError critical) throw critical;
            if (error instanceof ThreadDeath critical) throw critical;
            rejected = selection;
            LogUtils.getLogger().warn("Earth could not draw the current Iris/Oculus pack", error);
            release();
            return Result.NONE;
        }
    }

    private Result renderScene(IrisPackAccess iris, IrisPackAccess.Selection desired, MapCamera camera,
                                List<Draw> draws, List<Draw> casters, Set<Draw> solids, Set<Draw> contained,
                                List<Draw> projectionDraws, List<ProjectedTileIndex.Mesh> projectionMeshes,
                                Minecraft minecraft, boolean resized,
                                ChunkTileSource source) throws Throwable {
        Matrix4f mapView = camera.viewMatrix();
        var packCamera = MapPackProjection.of(camera, mapView, camera.projectionMatrix());
        Vector3d origin = new Vector3d(camera.x(), camera.y(), camera.z());
        try (var scope = new MapPackTarget(target, origin)) {
            temporal.begin(scope, camera, packCamera, iris::historyMatrix);
            scope.sampleEye(camera, minecraft.level, source);
            scope.renderDistance(MapPackProjection.renderDistance(camera, source));
            scope.shadowDistance(iris.shadowDistance());
            var prior = iris.cameraState();
            try {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                target.setClearColor(0, 0, 0, 0);
                target.clear(Minecraft.ON_OSX);
                iris.setCamera(packCamera.view(), packCamera.projection());
                if (pipeline == null) {
                    scope.materialTiles(MapPackMaterialTileTextures.available());
                    scope.beginLegacyShaderCompilation();
                    try {
                        pipeline = iris.create(desired);
                        materialProfile = scope.materialProfile();
                    } catch (RuntimeException materialFailure) {
                        if (scope.materialProfile() == null) throw materialFailure;
                        scope.materialTiles(false);
                        LogUtils.getLogger().warn("Cannot compile Iris/Oculus material capture for this shader pack", materialFailure);
                        scope.beginLegacyShaderCompilation();
                        pipeline = iris.create(desired);
                    } finally {
                        scope.endLegacyShaderCompilation();
                    }
                    if (storage != null) storage.remember();
                    geometry.uploadMaterials(iris, materialProfile == null ? null : scope.materialWaving());
                }
                geometry.beginFrame();
                scope.pipeline(pipeline);
                var solid = iris.shader(pipeline, false, false, true);
                var cutout = iris.shader(pipeline, false, false, false);
                var water = iris.shader(pipeline, true, false, false);
                var shadow = iris.shader(pipeline, false, true, false);
                if (solid == null || cutout == null) throw new IllegalStateException("Shader pack did not provide a terrain program");
                boolean partial = geometry.supportsMask(solid) && geometry.supportsMask(cutout)
                        && geometry.supportsMask(water) && geometry.supportsMask(shadow);
                var batch = MapPackDraws.select(draws, solids, contained, partial);
                if (batch.terrain().isEmpty()) return Result.NONE;
                var casterBatch = batch.withShadowCasters(casters, solids, contained, partial);
                var solidDraws = batch.solid();
                var cutoutDraws = batch.cutout();
                scope.shadows((view, shadowProjection, transparent) -> {
                    if (transparent) drawShadow(iris, mapView, view, shadowProjection, casterBatch.terrain(), true, false, partial);
                    else {
                        drawShadow(iris, mapView, view, shadowProjection, casterBatch.solid(), false, true, partial);
                        drawShadow(iris, mapView, view, shadowProjection, casterBatch.cutout(), false, false, partial);
                    }
                });
                iris.begin(pipeline);
                if (storage != null && resized) storage.remember();
                iris.prepare(pipeline, minecraft.gameRenderer.getMainCamera());
                if (!solidDraws.isEmpty()) {
                    iris.phase(pipeline, false, true);
                    geometry.materialScope(source, mapScope, materialProfile);
                    var material = camera.mode().equals("street") || projectionMeshes.isEmpty() ? null
                            : new MapPackGeometry.MaterialScene(camera, target.width, target.height, packCamera.viewScale(),
                                    projectionDraws, projectionMeshes, scope);
                    geometry.draw(solid, mapView, packCamera.view(), packCamera.projection(), solidDraws,
                            false, false, partial, material);
                }
                if (!cutoutDraws.isEmpty()) {
                    iris.phase(pipeline, false, false);
                    geometry.draw(cutout, mapView, packCamera.view(), packCamera.projection(), cutoutDraws, false, false, partial);
                }
                iris.translucent(pipeline);
                iris.phase(pipeline, true, false);
                geometry.draw(water, mapView, packCamera.view(), packCamera.projection(), batch.terrain(), true, false, partial);
                iris.finish(pipeline);
                raster = scope.raster();
                temporal.finish(camera, packCamera);
                return new Result(water != null ? Layers.ALL : Layers.OPAQUE, batch.remaining());
            } finally { iris.restore(prior); }
        }
    }

    private void drawShadow(IrisPackAccess iris, Matrix4f camera, Matrix4f view, Matrix4f projection,
                            List<Draw> draws, boolean transparent, boolean solid, boolean partial) {
        if (draws.isEmpty()) return;
        try {
            Object previous = iris.currentPhase(pipeline);
            try {
                iris.phase(pipeline, transparent, solid);
                geometry.draw(iris.shader(pipeline, transparent, true, solid), camera, view, projection, draws, transparent, true, partial);
            } finally { iris.restorePhase(pipeline, previous); }
        }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot draw shader pack map shadows", failure); }
    }

    private void release() {
        try (var graphics = new KuiMapRenderState(null); var gl = new PackGlState()) { releaseTarget(); }
    }

    private void releaseTarget() {
        temporal.reset();
        if (target == null) return;
        try (var scope = new MapPackTarget(target, new Vector3d());
             var buffers = storage == null ? null : storage.preserve()) {
            if (pipeline != null) IrisPackAccess.get().destroy(pipeline);
        } catch (Throwable failure) {
            if (failure instanceof Error error) throw error;
            LogUtils.getLogger().warn("Cannot release Earth shader pack pipeline", failure);
        } finally {
            pipeline = null;
            materialProfile = null;
            storage = null;
            selection = null;
            level = null;
            mapScope = null;
            geometry.resetPrograms();
            target.destroyBuffers();
            target = null;
        }
    }

    @Override public void close() {
        if (target != null) release();
        geometry.close();
        composite.close();
    }
}
