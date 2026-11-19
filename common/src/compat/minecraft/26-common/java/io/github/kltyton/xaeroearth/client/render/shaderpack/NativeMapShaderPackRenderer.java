package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.Draw;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeUniformFrameScope;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedGrid;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileIndex;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.world.level.block.Block;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Uses the selected Iris pack in an independent render target and never registers KUI's normal pipelines. */
public final class NativeMapShaderPackRenderer implements AutoCloseable {
    private static final ThreadLocal<NativePackLightGeometry> ACTIVE_VERTICES = new ThreadLocal<>();
    private static final ThreadLocal<MaskPass> ACTIVE_MASK = new ThreadLocal<>();
    private static final ThreadLocal<NativeMapMaterialPass> ACTIVE_MATERIAL = new ThreadLocal<>();
    public record Result(boolean rendered, List<Draw> remaining) {
        private static final Result NONE = new Result(false, List.of());
    }
    private record MaskPass(GpuBufferSlice full, Map<Draw, GpuBufferSlice> partial) { }
    private NativeIrisAccess.Selection selection, rejected;
    private TextureTarget target;
    private Object pipeline;
    private String materialProfile;
    private boolean partial;
    private MapPackStorageBindings storage;
    private boolean storageResized;
    private ClientLevel level;
    private MapScope mapScope;
    private final NativePackLightGeometry vertices = new NativePackLightGeometry();
    private final DynamicUniformStorage<MaskParameters> maskParameters =
            new DynamicUniformStorage<>("Earth Iris chunk mask", 112, 1024);
    private final DynamicUniformStorage<CompositeParameters> compositeParameters =
            new DynamicUniformStorage<>("Earth Iris composition", 144, 4);
    private final MapPackDepthMapping.Reader rasterReader = new MapPackDepthMapping.Reader();
    private final MapPackMaterialTiles materialTiles = new MapPackMaterialTiles();
    private final HashMap<Integer, MapPackMaterialTileProgram> materialPrograms = new HashMap<>();
    private int solidProgram, replayArray;
    private boolean captureFailed;
    private MapPackDepthMapping raster = MapPackDepthMapping.FULL_SIZE;
    private final MapPackTemporalState temporal = new MapPackTemporalState();
    private static boolean selectionFailureReported;

    private record CompositeParameters(MapPackDepthMapping mapping, MapPackRegion region, MapPackLodTransition.View blend)
            implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer buffer) {
            buffer.putFloat(mapping.xScale()).putFloat(mapping.yScale()).putFloat(mapping.xOffset()).putFloat(mapping.yOffset());
            buffer.putFloat(region.x()).putFloat(region.bottom()).putFloat(0).putFloat(0);
            blend.inverse().get(buffer);
            buffer.position(buffer.position() + 64);
            buffer.putFloat(blend.originX()).putFloat(blend.originZ()).putFloat(blend.step()).putFloat(blend.brightness());
            buffer.putFloat(blend.coverageX()).putFloat(blend.coverageZ()).putFloat(blend.width()).putFloat(blend.cameraY());
            buffer.putFloat(blend.depthScale()).putFloat(blend.depthBias()).putFloat(blend.packedHeight()).putFloat(0);
        }
    }

    public static boolean available() { return NativeIrisAccess.get() != null; }

    public static boolean packSelected() {
        var iris = NativeIrisAccess.get();
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
        return NativeIrisAccess.get().shadowMatrix(radius, near, far, fov);
    }

    public static Matrix4f shadowUniformProjection(Matrix4f raster) {
        return NativeMapPackDevice.shadowUniformProjection(raster);
    }

    public static RenderPipeline terrainPipeline() {
        return switch (MapPackTarget.terrainLayer()) {
            case 0 -> NativeMapPackDevice.OPAQUE;
            case 1 -> NativeMapPackDevice.TRANSLUCENT;
            case 2 -> NativeMapPackDevice.SHADOW_OPAQUE;
            case 3 -> NativeMapPackDevice.SHADOW_TRANSLUCENT;
            case 4 -> NativeMapPackDevice.SOLID;
            default -> null;
        };
    }

    public static void bindLightmap(RenderPass pass) {
        var light = Minecraft.getInstance().gameRenderer.levelLightmap();
        if (light != null) pass.bindTexture("Sampler2", light,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    public static GpuBuffer terrainVertexBuffer(GpuBuffer data, boolean indexed) {
        var pass = ACTIVE_VERTICES.get();
        return pass == null ? data : pass.buffer(data, indexed);
    }

    public static void bindChunkMask(RenderPass pass, Draw draw) {
        var material = ACTIVE_MATERIAL.get();
        if (material != null) material.draw(draw);
        var active = ACTIVE_MASK.get();
        if (active == null) return;
        pass.setUniform(NativeIrisAccess.MASK_UNIFORM, draw.chunkMask() == 15 ? active.full() : active.partial().get(draw));
    }

    public static void submitMaterial(Runnable draw) {
        var active = ACTIVE_MATERIAL.get();
        if (active == null) draw.run();
        else active.submit(draw);
    }

    private record MaskParameters(Matrix4f inverse, int[] viewport, double[] depth, int mask, boolean zeroToOne,
                                  float originX, float originZ)
            implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer buffer) {
            for (float value : inverse.get(new float[16])) buffer.putFloat(value);
            for (int value : viewport) buffer.putFloat(value);
            buffer.putFloat((float) depth[0]).putFloat((float) depth[1]).putFloat(mask).putFloat(zeroToOne ? 1 : 0);
            buffer.putFloat(originX).putFloat(originZ).putFloat(0).putFloat(0);
        }
    }

    public Result render(RenderTarget destination, MapCamera camera, Matrix4f projection,
                          List<Draw> draws, List<Draw> casters, Set<Draw> solids, Set<Draw> contained,
                          List<Draw> sources, List<ProjectedTileIndex.Mesh> meshes,
                          KuiNativeTerrainRenderer renderer, ChunkTileSource source, MapScope currentScope,
                          MapPackLodTransition<GpuTextureView> transition) {
        var minecraft = Minecraft.getInstance();
        if (!EarthPreferences.SHADER_PACK.get() || minecraft.level == null
                || !(destination.getColorTexture() instanceof GlTexture)) {
            release();
            rejected = null;
            return Result.NONE;
        }
        var iris = NativeIrisAccess.get();
        if (iris == null) return Result.NONE;
        try {
            var desired = iris.selection();
            if (desired == null) {
                release();
                rejected = null;
                return Result.NONE;
            }
            if (draws.isEmpty()) return Result.NONE;
            if (desired.equals(rejected)) return Result.NONE;
            var region = MapPackRegion.of(camera, destination.width, destination.height);
            var packView = region.camera();
            boolean scopeChanged = mapScope != null && currentScope != null && !currentScope.equals(mapScope);
            if (!desired.equals(selection) || target == null || level != minecraft.level || scopeChanged) {
                release();
                selection = desired;
                level = minecraft.level;
                target = NativeMapPackDevice.target(destination, region.width(), region.height());
                storage = iris.storage(desired);
            } else if (target.width != region.width() || target.height != region.height()) {
                target.resize(region.width(), region.height());
                temporal.reset();
                storageResized = true;
            }
            if (currentScope != null) mapScope = currentScope;
            vertices.beginFrame();
            Result result;
            try (var frame = NativeUniformFrameScope.open();
                 var buffers = storage == null ? null : storage.enter()) {
                result = draw(iris, desired, packView, region.projection(camera, projection), draws, casters, solids, contained, sources, meshes,
                        renderer, source);
            }
            if (!result.rendered()) return result;
            compositeParameters.endFrame();
            var blend = transition == null ? MapPackLodTransition.View.DISABLED : transition.view();
            NativeMapPackDevice.composite(target, destination,
                    compositeParameters.writeUniform(new CompositeParameters(raster, region, blend)), transition);
            return result;
        } catch (Throwable error) {
            if (error instanceof VirtualMachineError critical) throw critical;
            if (error instanceof ThreadDeath critical) throw critical;
            rejected = selection;
            LogUtils.getLogger().warn("Earth could not draw the current Iris pack", error);
            release();
            return Result.NONE;
        }
    }

    private Result draw(NativeIrisAccess iris, NativeIrisAccess.Selection desired, MapCamera camera,
                      Matrix4f projection, List<Draw> draws, List<Draw> casters, Set<Draw> solids, Set<Draw> contained,
                      List<Draw> sources, List<ProjectedTileIndex.Mesh> meshes,
                      KuiNativeTerrainRenderer renderer, ChunkTileSource source) throws Throwable {
        boolean perspective = camera.mode().equals("street");
        Matrix4f view = camera.viewMatrix();
        var packCamera = MapPackProjection.of(camera, view, projection);
        var shaderCamera = new MapPackProjection(packCamera.view(),
                NativeMapPackDevice.packUniformProjection(packCamera.projection()), packCamera.viewScale());
        try (var scope = new MapPackTarget(target, new Vector3d(camera.x(), camera.y(), camera.z()))) {
            temporal.begin(scope, camera, shaderCamera, matrix -> matrix);
            scope.sampleEye(camera, level, source);
            scope.renderDistance(MapPackProjection.renderDistance(camera, source));
            scope.shadowDistance(iris.shadowDistance());
            var previous = iris.camera();
            try {
                NativeMapPackDevice.clear(target);
                iris.camera(shaderCamera.view(), shaderCamera.projection());
                if (pipeline == null) {
                    initialize(iris, desired, scope);
                    if (storage != null) storage.remember();
                    var ids = MemoryUtil.memAllocInt(Block.BLOCK_STATE_REGISTRY.size());
                    try {
                        iris.materialIds(ids);
                        materialTiles.materials(ids, 1, materialProfile == null ? null : scope.materialWaving());
                        vertices.materials(ids);
                    } finally { MemoryUtil.memFree(ids); }
                }
                var batch = MapPackDraws.select(draws, solids, contained, partial);
                if (batch.terrain().isEmpty()) return Result.NONE;
                var casterBatch = batch.withShadowCasters(casters, solids, contained, partial);
                maskParameters.endFrame();
                var solidDraws = batch.solid();
                var cutoutDraws = batch.cutout();
                var waterDraws = batch.terrain().stream().filter(draw -> draw.mesh().translucentCount() > 0).toList();
                var waterCasters = casterBatch == batch ? waterDraws
                        : casterBatch.terrain().stream().filter(draw -> draw.mesh().translucentCount() > 0).toList();
                scope.pipeline(pipeline);
                scope.shadows((shadowView, shadowProjection, transparent) -> {
                    var fromMap = new Matrix4f(shadowView).mul(new Matrix4f(view).invert());
                    if (transparent) drawShadow(iris, scope, renderer, shadowProjection, shadowView,
                            transform(waterCasters, model -> new Matrix4f(fromMap).mul(model)), true, false);
                    else {
                        drawShadow(iris, scope, renderer, shadowProjection, shadowView,
                                transform(casterBatch.solid(), model -> new Matrix4f(fromMap).mul(model)), false, true);
                        drawShadow(iris, scope, renderer, shadowProjection, shadowView,
                                transform(casterBatch.cutout(), model -> new Matrix4f(fromMap).mul(model)), false, false);
                    }
                });
                iris.start(pipeline);
                if (storage != null && storageResized) storage.remember();
                storageResized = false;
                iris.prepare(pipeline, NativeMapPackDevice.camera(Minecraft.getInstance()));
                materialTiles.scope(source, mapScope, materialProfile);
                drawSolids(iris, scope, renderer, camera, packCamera, perspective, solidDraws, sources, meshes);
                drawPackLayer(iris, scope, renderer, packCamera, perspective, cutoutDraws, false);
                iris.water(pipeline);
                drawPackLayer(iris, scope, renderer, packCamera, perspective, waterDraws, true);
                iris.finish(pipeline);
                raster = scope.raster();
                temporal.finish(camera, shaderCamera);
                return new Result(true, batch.remaining());
            } finally { iris.restore(previous); }
        }
    }

    private void initialize(NativeIrisAccess iris, NativeIrisAccess.Selection desired, MapPackTarget scope) throws Throwable {
        scope.materialTiles(MapPackMaterialTileTextures.available());
        try {
            pipeline = iris.create(desired);
            scope.pipeline(pipeline);
            partial = iris.registerMasks(pipeline);
            materialProfile = scope.materialProfile();
        } catch (RuntimeException materialFailure) {
            if (scope.materialProfile() == null) throw materialFailure;
            if (pipeline != null) {
                iris.destroy(pipeline);
                pipeline = null;
                scope.pipeline(null);
            }
            scope.materialTiles(false);
            LogUtils.getLogger().warn("Cannot compile Iris material capture for this shader pack", materialFailure);
            pipeline = iris.create(desired);
            scope.pipeline(pipeline);
            partial = iris.registerMasks(pipeline);
        }
    }

    private void drawPackLayer(NativeIrisAccess iris, MapPackTarget scope, KuiNativeTerrainRenderer renderer,
                               MapPackProjection camera, boolean perspective, List<Draw> draws,
                               boolean transparent) throws Throwable {
        if (draws.isEmpty()) return;
        iris.phase(pipeline, transparent, false);
        var projected = camera.viewScale() == 1 ? draws : transform(draws, camera::scaleModelView);
        withMaterial(materialPass(null, null, 0), () -> drawLayer(scope, renderer, camera.projection(), camera.view(),
                perspective, projected, transparent, false, false));
    }

    private void drawSolids(NativeIrisAccess iris, MapPackTarget scope, KuiNativeTerrainRenderer renderer,
                            MapCamera camera, MapPackProjection optics, boolean perspective, List<Draw> solids,
                            List<Draw> sources, List<ProjectedTileIndex.Mesh> meshes) throws Throwable {
        if (solids.isEmpty()) return;
        iris.phase(pipeline, false, true);
        MapPackMaterialTiles.Frame frame = null;
        var previous = materialPrograms.get(solidProgram);
        if (!perspective && !captureFailed && previous != null && previous.supported() && !meshes.isEmpty()) {
            if (replayArray == 0) replayArray = GL30.glGenVertexArrays();
            try {
                frame = materialTiles.prepare(camera, target.width, target.height, solids, sources, meshes,
                        NativeMapPackDevice.reverseDepth(), (framebuffer, grid, cell, contributors) -> {
                            var crop = NativeMapPackDevice.materialProjection(grid.tileProjection(cell))
                                    .scale(1 / optics.viewScale());
                            var pass = materialPass(null, null, framebuffer);
                            withMaterial(pass, () -> drawLayer(scope, renderer, crop, optics.view(), false,
                                    scale(contributors, optics), false, false, true));
                            if (!pass.submitted())
                                throw new IllegalStateException("Iris material capture did not submit a solid draw");
                        });
            } catch (RuntimeException failure) {
                captureFailed = true;
                materialTiles.clear();
                LogUtils.getLogger().warn("Cannot record the current Iris material tiles", failure);
            }
        }
        var pass = materialPass(frame, new Matrix4f(optics.projection()).mul(optics.view()).invert(), 0);
        List<Draw> visible = solids;
        if (frame != null && !frame.pictures().isEmpty()) {
            var uncovered = frame.uncovered();
            visible = solids.stream().filter(draw -> {
                var cells = uncovered.get(draw);
                return cells == null || !cells.isEmpty();
            }).toList();
            if (visible.isEmpty()) visible = List.of(solids.get(0));
        }
        var projected = scale(visible, optics);
        var original = visible;
        withMaterial(pass, () -> drawLayer(scope, renderer, optics.projection(), optics.view(), perspective,
                projected, false, false, true, original));
        solidProgram = pass.programId();
    }

    private NativeMapMaterialPass materialPass(MapPackMaterialTiles.Frame frame, Matrix4f inverse, int framebuffer) {
        return new NativeMapMaterialPass(materialPrograms, rasterReader, frame, inverse, target.width, target.height,
                framebuffer, replayArray, NativeMapPackDevice.reverseDepth(), NativeMapPackDevice.zeroToOne());
    }

    private static void withMaterial(NativeMapMaterialPass pass, Runnable draw) {
        ACTIVE_MATERIAL.set(pass);
        try { draw.run(); }
        finally { ACTIVE_MATERIAL.remove(); }
    }

    private static List<Draw> scale(List<Draw> draws, MapPackProjection camera) {
        return camera.viewScale() == 1 ? draws : transform(draws, camera::scaleModelView);
    }

    private static List<Draw> transform(List<Draw> draws, java.util.function.UnaryOperator<Matrix4f> transform) {
        return draws.stream().map(draw -> new Draw(draw.mesh(), transform.apply(draw.modelView()),
                draw.chunkMask(), draw.skyLightFactor())).toList();
    }

    private void drawShadow(NativeIrisAccess iris, MapPackTarget scope, KuiNativeTerrainRenderer renderer,
                            Matrix4f projection, Matrix4f view, List<Draw> draws, boolean transparent, boolean solid) {
        if (draws.isEmpty()) return;
        try {
            Object previous = iris.currentPhase(pipeline);
            try {
                iris.phase(pipeline, transparent, solid);
                drawLayer(scope, renderer, projection, view, false, draws, transparent, true, solid);
            } finally { iris.restorePhase(pipeline, previous); }
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot draw Iris map shadows", failure); }
    }

    private void drawLayer(MapPackTarget scope, KuiNativeTerrainRenderer renderer, Matrix4f projection, Matrix4f view,
                           boolean perspective, List<Draw> draws, boolean transparent, boolean shadow, boolean solid) {
        drawLayer(scope, renderer, projection, view, perspective, draws, transparent, shadow, solid, draws);
    }

    private void drawLayer(MapPackTarget scope, KuiNativeTerrainRenderer renderer, Matrix4f projection, Matrix4f view,
                           boolean perspective, List<Draw> draws, boolean transparent, boolean shadow, boolean solid,
                           List<Draw> original) {
        scope.terrain(transparent, shadow, solid);
        try {
            ACTIVE_VERTICES.set(vertices);
            var materials = ACTIVE_MATERIAL.get();
            ACTIVE_MASK.set(prepareMasks(projection, view, draws, shadow, materials));
            if (materials != null) materials.sources(draws, original);
            if (transparent) renderer.drawTransparent(target, projection, perspective, draws);
            else renderer.drawOpaque(target, projection, perspective, draws);
            if (!shadow) MapPackTarget.captureRaster(rasterReader, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        } finally {
            ACTIVE_VERTICES.remove();
            ACTIVE_MASK.remove();
            scope.endTerrain();
        }
    }

    private MaskPass prepareMasks(Matrix4f projection, Matrix4f view, List<Draw> draws, boolean shadow,
                                  NativeMapMaterialPass materials) {
        var viewport = new MapPackViewport();
        viewport.capture();
        if (!shadow) {
            // KUI opens the terrain pass over the full physical attachment.
            viewport.viewport[0] = viewport.viewport[1] = 0;
            viewport.viewport[2] = target.width;
            viewport.viewport[3] = target.height;
        }
        if (materials != null) materials.adjustViewport(viewport);
        var inverseView = new Matrix4f(view).invert();
        boolean zeroToOne = NativeMapPackDevice.zeroToOne();
        var full = maskParameters.writeUniform(new MaskParameters(new Matrix4f(), new int[4], new double[2],
                15, false, 0, 0));
        var partial = new IdentityHashMap<Draw, GpuBufferSlice>();
        for (Draw draw : draws) {
            if (draw.chunkMask() == 15) continue;
            var inverse = new Matrix4f(projection).mul(draw.modelView()).invert();
            var world = new Matrix4f(inverseView).mul(draw.modelView());
            partial.put(draw, maskParameters.writeUniform(new MaskParameters(inverse, viewport.viewport,
                    viewport.depth, draw.chunkMask(), zeroToOne, world.m30(), world.m32())));
        }
        return new MaskPass(full, partial);
    }

    private void release() {
        temporal.reset();
        vertices.close();
        rasterReader.clear();
        materialTiles.close();
        materialPrograms.clear();
        if (replayArray != 0) GL30.glDeleteVertexArrays(replayArray);
        replayArray = solidProgram = 0;
        captureFailed = false;
        if (target == null) return;
        try (var scope = new MapPackTarget(target, new Vector3d());
             var buffers = storage == null ? null : storage.preserve()) {
            if (pipeline != null) NativeIrisAccess.get().destroy(pipeline);
        } catch (Throwable error) {
            if (error instanceof VirtualMachineError critical) throw critical;
            if (error instanceof ThreadDeath critical) throw critical;
            LogUtils.getLogger().warn("Cannot release Earth Iris map pipeline", error);
        } finally {
            pipeline = null;
            materialProfile = null;
            storage = null;
            storageResized = false;
            selection = null;
            level = null;
            mapScope = null;
            target.destroyBuffers();
            target = null;
        }
    }

    @Override public void close() { release(); maskParameters.close(); compositeParameters.close(); }
}
