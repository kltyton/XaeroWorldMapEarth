package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackLodTransition;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeMesh;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.render.shaderpack.NativeMapShaderPackRenderer;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapShaderPasses;
import io.github.kltyton.xaeroearth.client.render.map.MapHighlightField;
import io.github.kltyton.xaeroearth.client.render.map.MapHighlightRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileIndex;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectionPreflight;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.NativeProjectedTerrain;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedDrawCoverage;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.IntSupplier;

abstract class TerrainPlatform {
    private static final MapShaderPasses<NativeMapShaderPackRenderer> MAP_SHADERS =
            new MapShaderPasses<>(NativeMapShaderPackRenderer::new, NativeMapShaderPackRenderer::close);
    private List<KuiNativeTerrainRenderer.Draw> unpackedTranslucent;
    private final MapHighlightRenderer highlights = new MapHighlightRenderer();
    private final MapHighlightRenderer nativeHighlights = new MapHighlightRenderer();
    private NativeProjectedTerrain projected;

    protected TerrainPlatform() { MAP_SHADERS.retain(); }
    protected boolean supportsProjectionImages() { return true; }

    protected boolean shaderRenderingActive() {
        return io.github.kltyton.xaeroearth.client.EarthPreferences.SHADER_PACK.get() && NativeMapShaderPackRenderer.packSelected();
    }

    protected boolean projectionImagesAvailable(MapCamera camera, ChunkTileSource source, MapScope scope) {
        return source != null && scope != null && !camera.mode().equals("street")
                && source.projectedCacheDirectory() != null && source.projectedResourceKey() != null;
    }

    protected ProjectionPreflight preflightProjection(RenderTarget target, MapCamera camera,
            List<ProjectedTileIndex.Mesh> meshes, ChunkTileSource source, MapScope scope) {
        if (projected == null) projected = new NativeProjectedTerrain();
        return projected.preflight(target, camera, meshes, source, scope);
    }

    protected void drawOpaqueTerrain(RenderTarget target, MapCamera camera, List<KuiNativeTerrainRenderer.Draw> draws,
                                     List<KuiNativeTerrainRenderer.Draw> casters,
                                     Set<KuiNativeTerrainRenderer.Draw> solids,
                                     Set<KuiNativeTerrainRenderer.Draw> contained,
                                     List<KuiNativeTerrainRenderer.Draw> projectionDraws, List<ProjectedTileIndex.Mesh> meshes,
                                     ChunkTileSource source, MapScope scope, float brightness,
                                     int coverageX, int coverageZ, boolean shaderPack) {
        var projection = projection(camera);
        if (projected != null) projected.poll();
        var surface = surfaceRenderer.surface();
        var transition = !shaderPack || camera.minimap() || camera.mode().equals("street") || surface == null ? null
                : new MapPackLodTransition<>(MapPackLodTransition.View.of(camera, surface, coverageX, coverageZ, brightness,
                        projection, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(), false),
                        surfaceRenderer.colorTexture(), surfaceRenderer.heightTexture(), coverage.getTextureView());
        var packed = MAP_SHADERS.forMap(camera).render(target, camera, projection, draws, casters, solids, contained,
                projectionDraws, meshes, delegate, source, scope, transition);
        var remaining = packed.rendered() ? packed.remaining() : draws;
        var coverage = ProjectedDrawCoverage.NONE;
        unpackedTranslucent = remaining;
        if (!packed.rendered() && !meshes.isEmpty() && !shaderRenderingActive() && projectionImagesAvailable(camera, source, scope)) {
            if (projected == null) projected = new NativeProjectedTerrain();
            coverage = projected.draw(target, camera, projectionDraws, meshes, source, scope, brightness);
        }
        try (var frame = NativeUniformFrameScope.open()) {
            coverage.draw(remaining, visible -> delegate.drawOpaque(target, projection, camera.mode().equals("street"), visible));
        }
    }

    protected void drawContraptionBlocks(RenderTarget target, MapCamera camera,
            List<io.github.kltyton.xaeroearth.client.integration.create.ContraptionBlockRender> blocks) {
        io.github.kltyton.xaeroearth.client.render.entity.MapEntityLayer.drawContraptionBlocks(camera, target, blocks);
    }

    protected void drawTransparentTerrain(RenderTarget target, MapCamera camera, List<KuiNativeTerrainRenderer.Draw> draws) {
        var remaining = unpackedTranslucent == null ? draws : unpackedTranslucent;
        unpackedTranslucent = null;
        delegate.drawTransparent(target, projection(camera), camera.mode().equals("street"), remaining);
    }


    protected io.github.kltyton.xaeroearth.client.chunkmap.geometry.GeometryUpload prepareDetailedUpload(
            io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry geometry) {
        return io.github.kltyton.xaeroearth.client.chunkmap.geometry.NativeTerrainBake.prepare(geometry);
    }

    protected void resetProjectedTerrain() {
        if (projected != null) projected.reset();
    }

    protected void closeAuxiliaryGeometry() {
        MAP_SHADERS.release(); fog.close(); boundary.close(); highlights.close();
        nativeHighlights.close();
        if (projected != null) { projected.close(); projected = null; }
    }

    protected void drawHighlights(RenderTarget target, MapCamera camera, MapHighlightField published, MapHighlightField field) {
        nativeHighlights.draw(target, camera, published);
        highlights.draw(target, camera, field);
    }

    private final io.github.kltyton.xaeroearth.client.render.map.MapBoundaryRenderer boundary =
            new io.github.kltyton.xaeroearth.client.render.map.MapBoundaryRenderer();

    protected void drawExploredBoundary(RenderTarget target, MapCamera camera) {
        var surface = surfaceRenderer.surface();
        if (surface != null) boundary.draw(target, camera, surface, surfaceRenderer.heightTexture());
    }

    protected final io.github.kltyton.xaeroearth.client.render.map.MapFogRenderer fog =
            new io.github.kltyton.xaeroearth.client.render.map.MapFogRenderer();

    protected void drawFog(RenderTarget target, MapCamera camera, boolean cave) {
        fog.draw(target, camera, cave);
    }

    protected void drawMarks(RenderTarget target, MapCamera camera,
                             io.github.kltyton.xaeroearth.client.mapmark.MapMarkMesh marks) {
        io.github.kltyton.xaeroearth.client.render.navigation.NavigationRenderer.drawMarks(target, camera, marks);
    }

    protected final KuiNativeTerrainRenderer delegate = new KuiNativeTerrainRenderer();
    protected final SurfaceRenderer surfaceRenderer = new SurfaceRenderer();
    protected DynamicTexture coverage;

    protected void drawNavigation(RenderTarget target, MapCamera camera) {
        io.github.kltyton.xaeroearth.client.render.navigation.NavigationRenderer.drawMap(target, camera);
    }

    protected void prepareInitialSurface(RenderTarget target, NativeTerrainScene.Surface surface) {
        surfaceRenderer.update(surface);
    }

    protected boolean drawSurface(MapCamera camera, NativeTerrainScene.Surface surface, RenderTarget target,
                                  float brightness, boolean underground, boolean preserveBackground, Runnable updateCoverage,
                                  IntSupplier coverageX, IntSupplier coverageZ) {
        updateCoverage.run();
        surfaceRenderer.update(surface);
        boolean drawn = surface != null && surfaceRenderer.draw(camera, target, coverage.getTextureView(),
                coverageX.getAsInt(), coverageZ.getAsInt(), brightness, !preserveBackground, underground);
        if (!drawn) clear(target, underground, preserveBackground);
        return drawn;
    }

    protected static Matrix4f projection(MapCamera camera) {
        return camera.nativeProjectionMatrix(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
    }

    protected static DynamicTexture createCoverage(int size) {
        return new DynamicTexture(() -> "KUI map coverage", size, size, true);
    }

    protected static void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixel(x, y, color);
    }

    private static void clear(RenderTarget target, boolean underground, boolean preserveBackground) {
        Optional<Vector4fc> color = Optional.of(new Vector4f(underground ? 0 : 0.46F, underground ? 0 : 0.67F,
                underground ? 0 : 0.82F, 1));
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Earth native terrain clear", target.getColorTextureView(), preserveBackground ? Optional.empty() : color,
                target.getDepthTextureView(), OptionalDouble.of(0))) { }
    }

}
