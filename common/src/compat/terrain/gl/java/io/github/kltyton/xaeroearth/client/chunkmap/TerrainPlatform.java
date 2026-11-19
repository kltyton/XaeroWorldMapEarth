package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackLodTransition;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiMapDepthTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiMapRenderState;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeMesh;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.render.shaderpack.GlMapShaderPackRenderer;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapShaderPasses;
import io.github.kltyton.xaeroearth.client.render.map.MapHighlightField;
import io.github.kltyton.xaeroearth.client.render.map.MapHighlightRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.GlProjectedTerrain;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedDrawCoverage;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileIndex;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectionPreflight;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.IntSupplier;

abstract class TerrainPlatform {
    private static final MapShaderPasses<GlMapShaderPackRenderer> MAP_SHADERS =
            new MapShaderPasses<>(GlMapShaderPackRenderer::new, GlMapShaderPackRenderer::close);
    private List<KuiNativeTerrainRenderer.Draw> unpackedTranslucent;
    private final MapHighlightRenderer highlights = new MapHighlightRenderer();
    private final MapHighlightRenderer nativeHighlights = new MapHighlightRenderer();
    private GlProjectedTerrain projected;

    protected TerrainPlatform() { MAP_SHADERS.retain(); }
    protected boolean supportsProjectionImages() { return true; }

    protected boolean shaderRenderingActive() {
        return io.github.kltyton.xaeroearth.client.EarthPreferences.SHADER_PACK.get() && GlMapShaderPackRenderer.packSelected();
    }

    protected boolean projectionImagesAvailable(MapCamera camera, ChunkTileSource source, MapScope scope) {
        return source != null && scope != null && !camera.mode().equals("street")
                && source.projectedCacheDirectory() != null && source.projectedResourceKey() != null;
    }

    protected ProjectionPreflight preflightProjection(RenderTarget target, MapCamera camera,
            List<ProjectedTileIndex.Mesh> meshes, ChunkTileSource source, MapScope scope) {
        if (projected == null) projected = new GlProjectedTerrain();
        return projected.preflight(target, camera, meshes, source, scope);
    }

    protected void drawOpaqueTerrain(RenderTarget target, MapCamera camera, List<KuiNativeTerrainRenderer.Draw> draws,
                                     List<KuiNativeTerrainRenderer.Draw> casters,
                                     Set<KuiNativeTerrainRenderer.Draw> solids,
                                     Set<KuiNativeTerrainRenderer.Draw> contained,
                                     List<KuiNativeTerrainRenderer.Draw> projectionDraws, List<ProjectedTileIndex.Mesh> meshes,
                                     ChunkTileSource source, MapScope scope, float brightness,
                                     int coverageX, int coverageZ, boolean shaderPack) {
        if (projected != null) projected.poll();
        var projection = projection(camera);
        var surface = surfaceRenderer.surface();
        var transition = !shaderPack || camera.minimap() || camera.mode().equals("street") || surface == null ? null
                : new MapPackLodTransition<>(MapPackLodTransition.View.of(camera, surface, coverageX, coverageZ, brightness,
                        projection, false, false),
                        surfaceRenderer.colorTexture(), surfaceRenderer.heightTexture(), coverage.getId());
        var packed = MAP_SHADERS.forMap(camera).render(target, delegate.depthTarget(), camera, draws, casters, solids, contained,
                projectionDraws, meshes, source, scope, transition);
        var remaining = packed.layers() == GlMapShaderPackRenderer.Layers.NONE ? draws : packed.remaining();
        var coverage = ProjectedDrawCoverage.NONE;
        unpackedTranslucent = packed.layers() == GlMapShaderPackRenderer.Layers.ALL ? packed.remaining() : draws;
        if (packed.layers() == GlMapShaderPackRenderer.Layers.NONE && !meshes.isEmpty()
                && !shaderRenderingActive() && projectionImagesAvailable(camera, source, scope)) {
            if (projected == null) projected = new GlProjectedTerrain();
            coverage = projected.draw(target, delegate.depthTarget(), camera, projectionDraws, meshes, source, scope, brightness);
        }
        coverage.draw(remaining, visible -> delegate.drawOpaque(target, projection, camera.mode().equals("street"), visible));
    }

    protected void drawContraptionBlocks(RenderTarget target, MapCamera camera,
            List<io.github.kltyton.xaeroearth.client.integration.create.ContraptionBlockRender> blocks) {
        io.github.kltyton.xaeroearth.client.render.entity.MapEntityLayer.drawContraptionBlocks(camera, target, delegate.depthTarget(), blocks);
    }

    protected void drawTransparentTerrain(RenderTarget target, MapCamera camera, List<KuiNativeTerrainRenderer.Draw> draws) {
        var remaining = unpackedTranslucent == null ? draws : unpackedTranslucent;
        unpackedTranslucent = null;
        delegate.drawTransparent(target, projection(camera), camera.mode().equals("street"), remaining);
    }

    private final io.github.kltyton.xaeroearth.client.chunkmap.geometry.GlTerrainBake tileBake =
            new io.github.kltyton.xaeroearth.client.chunkmap.geometry.GlTerrainBake();
    private boolean loggedBakeFailure;

    protected io.github.kltyton.xaeroearth.client.chunkmap.geometry.GeometryUpload prepareDetailedUpload(
            io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry geometry) {
        try { return tileBake.prepare(geometry); }
        catch (java.io.IOException failed) {
            if (!loggedBakeFailure) {
                LogUtils.getLogger().warn("Earth will assemble this terrain tile on the CPU", failed);
                loggedBakeFailure = true;
            }
            try { return tileBake.triangles(geometry.assembleOnCpu(), true); }
            catch (java.io.IOException badData) { throw new java.io.UncheckedIOException(badData); }
        }
    }

    protected void resetProjectedTerrain() {
        if (projected != null) projected.reset();
    }

    protected void closeAuxiliaryGeometry() {
        MAP_SHADERS.release(); tileBake.close(); fog.close(); boundary.close(); highlights.close();
        nativeHighlights.close();
        if (projected != null) projected.close();
    }

    protected void drawHighlights(RenderTarget target, MapCamera camera, MapHighlightField published, MapHighlightField field) {
        nativeHighlights.draw(target, delegate.depthTarget(), camera, published);
        highlights.draw(target, delegate.depthTarget(), camera, field);
    }

    private final io.github.kltyton.xaeroearth.client.render.map.MapBoundaryRenderer boundary =
            new io.github.kltyton.xaeroearth.client.render.map.MapBoundaryRenderer();

    protected void drawExploredBoundary(RenderTarget target, MapCamera camera) {
        var surface = surfaceRenderer.surface();
        if (surface != null) boundary.draw(target, delegate.depthTarget(), camera, surface, surfaceRenderer.heightTexture());
    }

    protected final io.github.kltyton.xaeroearth.client.render.map.MapFogRenderer fog =
            new io.github.kltyton.xaeroearth.client.render.map.MapFogRenderer();

    protected void drawFog(RenderTarget target, MapCamera camera, boolean cave) {
        fog.draw(target, camera, cave, delegate.depthTarget());
    }

    protected void drawMarks(RenderTarget target, MapCamera camera,
                             io.github.kltyton.xaeroearth.client.mapmark.MapMarkMesh marks) {
        io.github.kltyton.xaeroearth.client.render.navigation.NavigationRenderer.drawMarks(target, camera, delegate.depthTarget(), marks);
    }

    protected final KuiNativeTerrainRenderer delegate = new KuiNativeTerrainRenderer();
    protected final SurfaceRenderer surfaceRenderer = new SurfaceRenderer();
    protected DynamicTexture coverage;

    protected void drawNavigation(RenderTarget target, MapCamera camera) {
        io.github.kltyton.xaeroearth.client.render.navigation.NavigationRenderer.drawMap(target, camera, delegate.depthTarget());
    }

    public KuiMapDepthTarget depthTarget() { return delegate.depthTarget(); }

    protected void prepareInitialSurface(RenderTarget target, NativeTerrainScene.Surface surface) {
        try (var state = new KuiMapRenderState(target, delegate.depthTarget())) {
            surfaceRenderer.update(surface);
        }
    }

    protected boolean drawSurface(MapCamera camera, NativeTerrainScene.Surface surface, RenderTarget target,
                                  float brightness, boolean underground, boolean preserveBackground, Runnable updateCoverage,
                                  IntSupplier coverageX, IntSupplier coverageZ) {
        try (var state = new KuiMapRenderState(target, delegate.depthTarget())) {
            updateCoverage.run();
            surfaceRenderer.update(surface);
            if (!preserveBackground)
                GlStateManager._clearColor(underground ? 0 : 0.46F, underground ? 0 : 0.67F, underground ? 0 : 0.82F, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            GlStateManager._clearDepth(1);
            GlStateManager._clear(GL11.GL_DEPTH_BUFFER_BIT | (preserveBackground ? 0 : GL11.GL_COLOR_BUFFER_BIT), Minecraft.ON_OSX);
            return surface != null && surfaceRenderer.draw(camera, coverage.getId(),
                    coverageX.getAsInt(), coverageZ.getAsInt(), brightness);
        }
    }

    protected static Matrix4f projection(MapCamera camera) {
        return camera.projectionMatrix();
    }

    protected static DynamicTexture createCoverage(int size) {
        return new DynamicTexture(size, size, true);
    }

    protected static void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixelRGBA(x, y, color);
    }

}
