package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.GeometryUpload;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedGrid;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileIndex;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectionPreflight;
import io.github.kltyton.xaeroearth.client.mapmark.MapMark;
import io.github.kltyton.xaeroearth.client.mapmark.MapMarkMesh;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import io.github.kltyton.xaeroearth.client.integration.create.CreateStructures;
import io.github.kltyton.xaeroearth.client.integration.create.ContraptionBlockRender;
import io.github.kltyton.xaeroearth.client.integration.xaeroplus.DrapedDrawings;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackDraws;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/** Native map scene: owns geometry residency, coverage and surface passes; GPU mesh rendering is delegated to the KUI primitive. */
public final class NativeTerrainScene extends TerrainPlatform implements AutoCloseable {
    public record Surface(long x, long z, int width, int depth, int step, byte[] rgba,
                          int patchCount, int outlineCount, int joinCount, byte[] boundary, int floor, long revision,
                          float[][] packedHeights) {
        private static final int PACKED_PAGE = 1 << 16;
        public Surface(long x, long z, int width, int depth, int step, byte[] rgba, int[] heights,
                       int[] patches, int[] outline, int[] joins, byte[] boundary, int floor, long revision) {
            this(x, z, width, depth, step, rgba, heights, new byte[0], patches, outline, joins, boundary, floor, revision);
        }

        public Surface(long x, long z, int width, int depth, int step, byte[] rgba, int[] heights, byte[] blockLight,
                       int[] patches, int[] outline, int[] joins, byte[] boundary, int floor, long revision) {
            this(x, z, width, depth, step, rgba, patches.length, outline.length, joins.length, boundary, floor, revision,
                    packHeights(width, depth, heights, blockLight, patches, outline, joins, boundary));
        }

        public int boundaryRow() { return depth + (patchCount + outlineCount + joinCount + width - 1) / width; }
        int heightTextureRows() { return Math.addExact(boundary.length == 0 ? boundaryRow() : Math.addExact(boundaryRow(), depth), depth); }
        int vertexCount() { return Math.multiplyExact(patchCount + outlineCount + joinCount, 6); }

        float topHeight(int sample) { return packedHeights[sample / PACKED_PAGE][sample % PACKED_PAGE]; }

        void writeHeightRows(ByteBuffer buffer, int row, int rows) {
            var output = buffer.asFloatBuffer();
            int start = Math.multiplyExact(row, width), end = Math.multiplyExact(row + rows, width);
            while (start < end) {
                float[] page = packedHeights[start / PACKED_PAGE];
                int offset = start % PACKED_PAGE, length = Math.min(page.length - offset, end - start);
                output.put(page, offset, length);
                start += length;
            }
            buffer.position(buffer.position() + Math.multiplyExact(output.position(), Float.BYTES));
        }

        private static float[][] packHeights(int width, int depth, int[] heights, byte[] blockLight, int[] patches,
                                           int[] outline, int[] joins, byte[] boundary) {
            if (width < 1 || depth < 1 || heights.length != Math.multiplyExact(width, depth))
                throw new IllegalArgumentException("Invalid surface height dimensions");
            int boundaryRow = depth + (patches.length + outline.length + joins.length + width - 1) / width;
            int lightRow = boundary.length == 0 ? boundaryRow : Math.addExact(boundaryRow, depth);
            int rows = Math.addExact(lightRow, depth);
            if (blockLight.length != 0 && blockLight.length != Math.multiplyExact(width, depth))
                throw new IllegalArgumentException("Invalid surface block light dimensions");
            int length = Math.multiplyExact(width, rows);
            var packed = new float[1 + (length - 1) / PACKED_PAGE][];
            for (int page = 0; page < packed.length; page++)
                packed[page] = new float[Math.min(PACKED_PAGE, length - page * PACKED_PAGE)];
            int index = 0;
            for (int height : heights) put(packed, index++, height + 1.0F);
            for (int patch : patches) put(packed, index++, patch);
            for (int edge : outline) put(packed, index++, edge);
            for (int edge : joins) put(packed, index++, edge);
            index = width * boundaryRow;
            for (byte distance : boundary) put(packed, index++, Byte.toUnsignedInt(distance));
            index = width * lightRow;
            if (blockLight.length == 0) for (int i = index; i < length; i++) put(packed, i, 1.0F);
            else for (byte light : blockLight) put(packed, index++, Byte.toUnsignedInt(light) / 255.0F);
            return packed;
        }

        private static void put(float[][] pages, int index, float value) {
            pages[index >>> 16][index & 0xFFFF] = value;
        }
    }
    public record Statistics(int visible, int resident, int loading, long residentBytes, int drawCalls,
                             int minecraftFps, double minecraftFrameMillis, double cpuMillis) { }
    private static final long OFFSCREEN_LIMIT = 384L << 20;
    private static final int UPLOAD_BUDGET = 24 << 20, UPLOAD_BATCH = 6 << 20;
    private static final long UPLOAD_BUDGET_NS = 3_500_000L;
    private static final int RETIRE_BATCH = 32;
    private static final long RETIRE_BUDGET_NS = 1_000_000L;
    private static final long PENDING_VISIBILITY_GRACE_NS = 250_000_000L;
    private static final long PROJECTION_SETTLE_NS = 120_000_000L;
    private static final int COVERAGE_SIZE = 200;
    private static final int DECODING_THREADS = net.minecraft.util.Mth.clamp(
            Runtime.getRuntime().availableProcessors() / 2, 2, 12);
    private static final int MAX_PENDING_DECODES = Math.max(24, DECODING_THREADS * 3);
    private final ExecutorService decoding = Executors.newFixedThreadPool(DECODING_THREADS, task -> {
                Thread thread = new Thread(task, "Earth native map geometry"); thread.setDaemon(true); return thread;
            });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long offscreenLimit;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);
    private long residentBytes;
    private GeometryMode geometryMode = GeometryMode.NORMAL;
    private MapCamera projectionCamera;
    private int projectionWidth, projectionHeight;
    private long projectionChanged;
    private final Map<ChunkTiles.Tile, Integer> coverageMasks = new HashMap<>();
    private final Map<ChunkTiles.Tile, String> displayedKeys = new HashMap<>();
    private record DisplayedLayer(String key, int mask) { }
    private final Map<ChunkTiles.Tile, List<DisplayedLayer>> displayedLayers = new HashMap<>();
    private int coverageX = Integer.MIN_VALUE, coverageZ = Integer.MIN_VALUE;
    private long coverageRevision;
    private RenderTarget frameTarget;
    private MapCamera frameCamera;
    private boolean frameEdge;
    private List<KuiNativeTerrainRenderer.Draw> frameDraws = List.of();
    private List<ContraptionBlockRender> frameContraptionBlocks = List.of();
    private final MapMarkMesh marks = new MapMarkMesh();
    private final MapMarkMesh claims = new MapMarkMesh();
    private final MapMarkMesh markPreview = new MapMarkMesh();
    private final MapMarkMesh addonOverlays = new MapMarkMesh();
    private final DrapedDrawings xaeroPlus = new DrapedDrawings();
    private volatile Statistics statistics = new Statistics(0,0,0,0,0,0,0,0);
    private volatile boolean lastFrameCacheView, lastFrameCachePack;
    private volatile int lastFrameLiveDraws, lastFrameProjectedPlan, lastFrameCoverageTiles;

    public record FrameDiagnostics(boolean cacheView, boolean cachePack, int liveDraws,
                                   int projectedPlan, int coverageTiles, long coverageRevision) { }

    public FrameDiagnostics frameDiagnostics() {
        return new FrameDiagnostics(lastFrameCacheView, lastFrameCachePack, lastFrameLiveDraws,
                lastFrameProjectedPlan, lastFrameCoverageTiles, coverageRevision);
    }

    public NativeTerrainScene() {
        this(OFFSCREEN_LIMIT);
    }

    public NativeTerrainScene(long offscreenLimit) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Create map scenes on the client thread");
        this.offscreenLimit = offscreenLimit;
    }


    public boolean drawOpaque(MapCamera camera, Surface surface, ChunkTileSource source,
                              Map<ChunkTiles.Tile,Integer> masks, RenderTarget target, float brightness, boolean underground,
                              boolean softenBoundary, java.util.function.BooleanSupplier restoreBackground, MapScope scope) {
        if (closed.get()) return false;
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Draw map scenes on the client thread");
        long started = System.nanoTime();
        suspend();
        long now = System.nanoTime();
        var mode = shaderRenderingActive() ? GeometryMode.SHADER
                : projectionImagesAvailable(camera, source, scope) ? GeometryMode.PROJECTED : GeometryMode.NORMAL;
        boolean modeChanged = geometryMode != mode;
        discardOtherGeometryMode(mode);
        boolean settled = mode != GeometryMode.NORMAL && settledProjection(camera, target, now);
        boolean cacheView = mode == GeometryMode.PROJECTED && settled;
        boolean cachePack = mode == GeometryMode.SHADER && settled && !camera.mode().equals("street");
        var imageGrid = cacheView || cachePack ? new ProjectedGrid(camera, target.width, target.height) : null;
        var imageCells = imageGrid == null ? null : imageGrid.visible();
        var view = camera.viewMatrix();
        var frustum = new FrustumIntersection(camera.viewportProjectionMatrix().mul(view));
        var wanted = new ArrayList<VisibleTile>();
        var requestedKeys = new HashSet<String>();
        double detailRadius = TerrainDetail.radius(camera.pixelsPerBlock(), Double.POSITIVE_INFINITY);
        double provisionalRadius = TerrainDetail.provisionalRadius(camera.pixelsPerBlock(), Double.POSITIVE_INFINITY);
        if (source != null) for (var mapped : masks.entrySet()) {
            var tile = mapped.getKey();
            int previousMask = coverageMasks.getOrDefault(tile, 0);
            double radius = previousMask != 0 ? detailRadius + 12 : detailRadius;
            if (detailRadius <= 0 || !TerrainDetail.intersects(camera.x(), camera.z(), radius, tile.x() * 32.0, tile.z() * 32.0, 32)) continue;
            int renderMask = mapped.getValue();
            if (!TerrainDetail.intersects(camera.x(), camera.z(), provisionalRadius + 12,
                    tile.x() * 32.0, tile.z() * 32.0, 32))
                renderMask &= source.validatedMask(tile) | previousMask;
            if (renderMask == 0) continue;
            String key = source.contentKey(tile);
            if (key == null) continue;
            Entry cached = entries.get(key);
            var metadata = source.projectionMetadata(tile, key);
            var bounds = cached != null && cached.hasDrawable() ? cached.bounds
                    : metadata == null ? null : metadata.bounds();
            var fragments = source.fragments(tile);
            if (bounds != null) for (var fragment : fragments) {
                var layerBounds = geometryBounds(source, tile, fragment.contentKey(), entries.get(fragment.contentKey()));
                if (layerBounds == null) { bounds = null; break; }
                bounds = union(bounds, layerBounds);
            }
            // A pending replacement's bounds do not describe the fragment still on screen.
            if (bounds != null && previousMask != 0) for (var layer : displayedLayers.getOrDefault(tile, List.of())) {
                if (layer.key().equals(key) || (layer.mask() & renderMask) == 0) continue;
                var resident = entries.get(layer.key());
                if (resident != null && resident.hasDrawable()) bounds = union(bounds, resident.bounds);
            }
            // A model can extend beyond its owning tile. Until decoded, its true bounds are unknown.
            boolean inView = bounds == null || frustum.testAab((float)(tile.x()*32.0-camera.x()+bounds.minX()),
                    bounds.minY()-(float)camera.y(), (float)(tile.z()*32.0-camera.z()+bounds.minZ()),
                    (float)(tile.x()*32.0-camera.x()+bounds.maxX()), bounds.maxY()-(float)camera.y(),
                    (float)(tile.z()*32.0-camera.z()+bounds.maxZ()));
            boolean contributesToImage = !inView && imageGrid != null
                    && imageGrid.contributesTo(tile, bounds, imageCells);
            boolean shadowOnly = !inView && !contributesToImage && mode == GeometryMode.SHADER
                    && cached != null && cached.gpu != null;
            if (inView || contributesToImage || shadowOnly) {
                requestedKeys.add(key);
                boolean visiblePriority = inView && (bounds != null || frustum.testAab(
                        (float) (tile.x() * 32.0 - camera.x() - 8), source.minY() - (float) camera.y(),
                        (float) (tile.z() * 32.0 - camera.z() - 8), (float) (tile.x() * 32.0 - camera.x() + 40),
                        source.maxY() - (float) camera.y(), (float) (tile.z() * 32.0 - camera.z() + 40)));
                double dx = tile.x() * 32.0 + 16 - camera.x(), dz = tile.z() * 32.0 + 16 - camera.z();
                int baseProjectionMask = source.projectionMask(tile, key);
                wanted.add(new VisibleTile(tile, key, renderMask, baseProjectionMask, cached, metadata,
                        fragments, visiblePriority, shadowOnly, dx * dx + dz * dz));
                if (cached != null) cached.lastWanted = now;
                for (var fragment : fragments) {
                    requestedKeys.add(fragment.contentKey());
                    var layer = entries.get(fragment.contentKey());
                    if (layer != null) layer.lastWanted = now;
                }
            }
        }
        wanted.sort(Comparator.comparing((VisibleTile tile) -> !tile.visiblePriority())
                .thenComparingDouble(VisibleTile::distanceSquared)
                .thenComparingInt(tile -> tile.tile().x()).thenComparingInt(tile -> tile.tile().z()));
        var planned = new ArrayList<ProjectedTileIndex.Mesh>();
        if (cacheView) for (var requested : wanted) {
            var tile = requested.tile();
            String key = requested.key();
            var metadata = requested.metadata();
            String displayed = displayedKeys.get(tile);
            if (metadata == null || displayed != null && !displayed.equals(key)) continue;
            int first = planned.size();
            int mask = requested.mask() & requested.baseProjectionMask();
            if (mask != 0) planned.add(projectionMesh(tile, key, mask, metadata, requested.cached()));
            for (var fragment : requested.fragments()) {
                int layerMask = requested.mask() & fragment.mask();
                if (layerMask == 0) continue;
                var layerMetadata = source.projectionMetadata(tile, fragment.contentKey());
                if (layerMetadata == null) {
                    while (planned.size() > first) planned.remove(planned.size() - 1);
                    break;
                }
                planned.add(projectionMesh(tile, fragment.contentKey(), layerMask, layerMetadata, entries.get(fragment.contentKey())));
            }
        }
        // Preflight and rasterization consume the same ordered plan. Preserve the draw order
        // of equal-depth faces when appending geometry to an existing image.
        planned.sort(Comparator.comparingDouble((ProjectedTileIndex.Mesh mesh) ->
                view.m02() * (mesh.tile().x() * 32.0 + 16 - camera.x())
                        + view.m22() * (mesh.tile().z() * 32.0 + 16 - camera.z()))
                .thenComparingInt(mesh -> mesh.tile().x()).thenComparingInt(mesh -> mesh.tile().z()));
        var preflight = planned.isEmpty() ? ProjectionPreflight.NONE
                : preflightProjection(target, camera, planned, source, scope);
        var obsolete = entries.entrySet().iterator();
        int pending = 0;
        while (obsolete.hasNext()) {
            var value = obsolete.next(); Entry entry = value.getValue();
            if (entry.work == null) continue;
            if (requestedKeys.contains(value.getKey())
                    || entry.started && now - entry.lastWanted < PENDING_VISIBILITY_GRACE_NS) {
                pending++;
                continue;
            }
            // Keep already-running decodes briefly so small pans can reuse their result.
            // An offscreen job still in the executor queue should not take a visible slot.
            entry.release();
            obsolete.remove();
        }
        int uploaded = 0;
        long uploadDeadline = 0;
        var geometries = new ArrayList<Placed>();
        var shadowGeometries = new ArrayList<Placed>();
        var readyEvictable = new HashSet<String>();
        var staticEvictable = new HashSet<String>();
        boolean classifyProjection = supportsProjectionImages();
        for (var requested : wanted) {
            var tile = requested.tile();
            String key = requested.key();
            int renderMask = requested.mask();
            int baseProjectionMask = requested.baseProjectionMask();
            var fragments = requested.fragments();
            Entry alreadyLoaded = requested.cached();
            if (requested.shadowOnly()) {
                if (alreadyLoaded == null || alreadyLoaded.gpu == null) continue;
                int baseMask = renderMask & baseProjectionMask & alreadyLoaded.mask;
                if (baseMask != 0) shadowGeometries.add(place(tile, key, baseMask, alreadyLoaded));
                for (var fragment : fragments) {
                    Entry layer = entries.get(fragment.contentKey());
                    if (layer == null || layer.gpu == null) continue;
                    int layerMask = renderMask & fragment.mask() & layer.mask;
                    if (layerMask != 0) shadowGeometries.add(place(tile, fragment.contentKey(), layerMask, layer));
                }
                continue;
            }
            var imageState = preflight.state(tile);
            if (imageState == ProjectionPreflight.State.READING
                    && !coverageMasks.containsKey(tile) && (alreadyLoaded == null || alreadyLoaded.gpu == null)) continue;
            if (imageState == ProjectionPreflight.State.READY) {
                if (alreadyLoaded != null && alreadyLoaded.work != null) {
                    alreadyLoaded.release();
                    entries.remove(key);
                    pending--;
                }
                if (alreadyLoaded != null && alreadyLoaded.gpu != null) {
                    if (!key.equals(displayedKeys.put(tile, key))) coverageRevision++;
                }
                // Keep resident VBOs of currently visible READY tiles protected ahead of offscreen
                // tiles so wheel-zoom animations and reverse pans never drop back to coarse or blank.
                readyEvictable.add(key);
                for (var fragment : fragments) readyEvictable.add(fragment.contentKey());
                addProjectedTile(geometries, source, requested, false);
                continue;
            }
            if (imageState == ProjectionPreflight.State.STATIC_READY
                    && fragments.isEmpty()
                    && alreadyLoaded != null && alreadyLoaded.mode == GeometryMode.PROJECTED
                    && alreadyLoaded.splitLive && alreadyLoaded.live != null) {
                // Static opaque faces are completely pictured; draw only the tiny live/translucent layer
                // and allow trim() to reclaim the static VBO if VRAM exceeds the budget.
                staticEvictable.add(key);
                int mask = renderMask & baseProjectionMask & alreadyLoaded.mask;
                if (mask != 0) {
                    geometries.add(new Placed(tile, key, mask, null, alreadyLoaded.live, false,
                            alreadyLoaded.bounds, false, null));
                }
                continue;
            }
            Entry entry = alreadyLoaded;
            if (entry != null && entry.gpu == null && entry.work == null && !entry.failed && pending < MAX_PENDING_DECODES) {
                if (entry.live != null) {
                    scheduleGeometryInto(entry, source, tile, key, classifyProjection, mode, now);
                    pending++;
                } else {
                    entries.remove(key);
                    entry = null;
                }
            }
            if (entry == null && pending < MAX_PENDING_DECODES) {
                entry = scheduleGeometry(source, tile, key, classifyProjection, mode, now); pending++;
            }
            if (entry != null && !entry.failed && entry.gpu == null && entry.work != null && entry.work.isDone() && uploaded < UPLOAD_BUDGET
                    && (uploadDeadline == 0 || System.nanoTime() < uploadDeadline)) {
                if (uploadDeadline == 0) uploadDeadline = System.nanoTime() + UPLOAD_BUDGET_NS;
                uploaded += uploadReady(entry, key, tile, Math.min(UPLOAD_BATCH, UPLOAD_BUDGET - uploaded), uploadDeadline);
            }
            String displayedKey = displayedKeys.get(tile);
            Entry displayed = displayedKey == null ? null : entries.get(displayedKey);
            int availableMask = 0;
            int readyMask = 0;
            var preparedFragments = fragments.isEmpty() ? List.<Placed>of() : new ArrayList<Placed>(fragments.size());
            if (entry != null && entry.gpu != null) for (var fragment : fragments) {
                String layerKey = fragment.contentKey();
                int layerMask = renderMask & fragment.mask();
                if (layerMask == 0) continue;
                availableMask |= layerMask;
                Entry layer = entries.get(layerKey);
                if (layer == null && pending < MAX_PENDING_DECODES) {
                    layer = scheduleGeometry(source, tile, layerKey, classifyProjection, mode, now); pending++;
                }
                if (layer != null && !layer.failed && layer.gpu == null && layer.work != null && layer.work.isDone()
                        && uploaded < UPLOAD_BUDGET && (uploadDeadline == 0 || System.nanoTime() < uploadDeadline)) {
                    if (uploadDeadline == 0) uploadDeadline = System.nanoTime() + UPLOAD_BUDGET_NS;
                    uploaded += uploadReady(layer, layerKey, tile, Math.min(UPLOAD_BATCH, UPLOAD_BUDGET - uploaded), uploadDeadline);
                }
                if (layer != null && layer.gpu != null) {
                    requestedKeys.add(layerKey);
                    int mask = layerMask & layer.mask;
                    readyMask |= mask;
                    if (mask != 0) preparedFragments.add(place(tile, layerKey, mask, layer));
                }
            }
            if (imageState == ProjectionPreflight.State.DISPLAYABLE) {
                addProjectedTile(geometries, source, requested, true);
                continue;
            }
            int previouslyDrawn = coverageMasks.getOrDefault(tile, 0) & renderMask;
            if (entry != null && entry.gpu != null
                    && (displayed == null || (previouslyDrawn & availableMask & ~readyMask) == 0)) {
                if (!key.equals(displayedKeys.put(tile, key))) coverageRevision++;
                displayedKey = key;
                displayed = entry;
            }
            int start = geometries.size();
            // Outside projected image coverage, require a resident static mesh (gpu != null)
            // before drawing or claiming quadrant coverage. If STATIC_READY evicted entry.gpu
            // and panning moved this tile to an unpictured edge, keep coarse terrain visible
            // until the static mesh reloads instead of leaving floating water over a hole.
            if (displayed != null && displayed.gpu != null) {
                requestedKeys.add(displayedKey);
                int mask = renderMask & displayed.mask;
                if (key.equals(displayedKey)) mask &= baseProjectionMask;
                if (mask != 0) geometries.add(place(tile, displayedKey, mask, displayed));
                if (key.equals(displayedKey)) geometries.addAll(preparedFragments);
            }
            retainDisplayedLayers(geometries, start, tile, renderMask, requestedKeys);
        }
        boolean trimmed = trim(requestedKeys, readyEvictable, staticEvictable, source != null && source.persistentCache(), cacheView);
        if (modeChanged || trimmed) {
            displayedKeys.values().removeIf(key -> !entries.containsKey(key));
            displayedLayers.values().removeIf(layers -> {
                for (var layer : layers) if (entries.containsKey(layer.key())) return false;
                return true;
            });
        }
        if (surface == null && geometries.isEmpty()) return false;
        if (camera.minimap() && geometries.isEmpty() && visibleSurface() == null) {
            prepareInitialSurface(target, surface);
            if (visibleSurface() == null) return false;
        }
        var draws = new ArrayList<KuiNativeTerrainRenderer.Draw>();
        var casters = new ArrayList<KuiNativeTerrainRenderer.Draw>();
        var solids = MapPackDraws.identitySet();
        var contained = MapPackDraws.identitySet();
        var bySource = new HashMap<ProjectedTileIndex.Source, KuiNativeTerrainRenderer.Draw>();
        // Translucent faces are submitted from far to near along the same view direction.
        geometries.sort(Comparator.comparingDouble((Placed placed) ->
                view.m02() * (placed.tile.x() * 32.0 + 16 - camera.x())
                        + view.m22() * (placed.tile.z() * 32.0 + 16 - camera.z()))
                .thenComparingInt(placed -> placed.tile.x()).thenComparingInt(placed -> placed.tile.z()));
        for (Placed placed : geometries) {
            if (placed.mesh == null && placed.live == null) continue;
            var draw = appendTerrainDraws(placed, view, camera, brightness, draws, solids, contained, mode);
            if (draw != null && (cacheView || cachePack && placed.solid))
                bySource.put(new ProjectedTileIndex.Source(placed.tile, placed.key), draw);
        }
        for (Placed placed : shadowGeometries)
            appendTerrainDraws(placed, view, camera, brightness, casters, solids, contained, mode);
        var projectionMeshes = planned;
        if (planned.isEmpty() && (cacheView || cachePack)) for (Placed placed : geometries) {
            if (placed.mesh == null || mode == GeometryMode.SHADER && (!placed.solid || placed.animated)) continue;
            projectionMeshes.add(new ProjectedTileIndex.Mesh(
                    placed.tile, placed.key, placed.mask, placed.bounds, placed.animated, placed.solidMaterialStates));
        }
        var projectionDraws = new ArrayList<KuiNativeTerrainRenderer.Draw>(projectionMeshes.size());
        for (var mesh : projectionMeshes) {
            var draw = bySource.get(mesh.source());
            projectionDraws.add(draw);
        }
        var structures = CreateStructures.frame(scope, camera, delegate, brightness);
        draws.addAll(structures.terrain());
        frameContraptionBlocks = structures.blocks();
        frameCamera = camera; frameTarget = target; frameDraws = List.copyOf(draws);
        boolean preserveBackground = restoreBackground.getAsBoolean();
        boolean surfaceDrawn = drawSurface(camera, surface, target, brightness, underground, preserveBackground,
                () -> updateCoverage(camera, geometries), () -> coverageX, () -> coverageZ);
        var displayed = visibleSurface();
        frameEdge = surfaceDrawn && softenBoundary && displayed != null && displayed.boundary().length != 0
                && !underground && !preserveBackground && !camera.mode().equals("street")
                && io.github.kltyton.xaeroearth.client.EarthPreferences.UNEXPLORED_FOG.get();
        drawOpaqueTerrain(target, camera, frameDraws, casters, solids, contained, projectionDraws, projectionMeshes,
                source, scope, brightness, coverageX, coverageZ, mode == GeometryMode.SHADER);
        // Fog occupies the same untouched pixels as XaeroPlus's transparent background.
        if (!preserveBackground && io.github.kltyton.xaeroearth.client.EarthPreferences.UNEXPLORED_FOG.get())
            drawFog(target, camera, underground);
        int calls = surfaceDrawn ? 1 : 0;
        for (var draw : draws) {
            var mesh = draw.mesh();
            if (mesh.opaqueCount() > 0) calls++;
            if (mesh.translucentCount() > 0) calls++;
        }
        int residentCount = 0, loadingCount = 0;
        for (var entry : entries.values()) {
            if (entry.hasDrawable()) residentCount++;
            if (entry.work != null) loadingCount++;
        }
        lastFrameCacheView = cacheView;
        lastFrameCachePack = cachePack;
        lastFrameLiveDraws = draws.size();
        lastFrameProjectedPlan = projectionMeshes.size();
        lastFrameCoverageTiles = coverageMasks.size();
        statistics = new Statistics(geometries.size(), residentCount, loadingCount, residentBytes, calls,
                FrameMetrics.fps(), FrameMetrics.frameMillis(), (System.nanoTime() - started) / 1_000_000.0);
        return true;
    }

    private static KuiNativeTerrainRenderer.Draw appendTerrainDraws(Placed placed, Matrix4f view, MapCamera camera,
            float brightness, List<KuiNativeTerrainRenderer.Draw> draws, Set<KuiNativeTerrainRenderer.Draw> solids,
            Set<KuiNativeTerrainRenderer.Draw> contained, GeometryMode mode) {
        Matrix4f transform = new Matrix4f(view).translate(
                (float) (placed.tile.x() * 32.0 - camera.x()), (float) -camera.y(),
                (float) (placed.tile.z() * 32.0 - camera.z()));
        KuiNativeTerrainRenderer.Draw draw = null;
        boolean fullyContained = mode == GeometryMode.SHADER && placed.mask != 15
                && MapPackDraws.contained(placed.bounds, placed.mask);
        if (placed.mesh != null) {
            draw = new KuiNativeTerrainRenderer.Draw(placed.mesh, transform, placed.mask, brightness);
            draws.add(draw);
            if (mode == GeometryMode.SHADER && placed.solid) solids.add(draw);
            if (fullyContained) contained.add(draw);
        }
        if (placed.live != null) {
            var live = new KuiNativeTerrainRenderer.Draw(placed.live, transform, placed.mask, brightness);
            draws.add(live);
            if (fullyContained) contained.add(live);
        }
        return draw;
    }

    public void drawTransparent(boolean showNavigation, List<MapMark> annotations,
                                List<MapMark> claimData, List<MapMark> preview, XaeroMapBridge data, MapScope scope) {
        if (frameTarget == null || closed.get()) return;
        try {
            if (!frameContraptionBlocks.isEmpty()) drawContraptionBlocks(frameTarget, frameCamera, frameContraptionBlocks);
            drawTransparentTerrain(frameTarget, frameCamera, frameDraws);
            var drawings = xaeroPlus.project(scope, frameCamera, data, this);
            drawHighlights(frameTarget, frameCamera, data.nativeHighlights(this), xaeroPlus.highlights());
            if (frameEdge) drawExploredBoundary(frameTarget, frameCamera);
            claims.update(claimData, frameCamera);
            if (claims.count() != 0) drawMarks(frameTarget, frameCamera, claims);
            addonOverlays.update(drawings, frameCamera);
            if (addonOverlays.count() != 0) drawMarks(frameTarget, frameCamera, addonOverlays);
            marks.update(annotations, frameCamera, data, this);
            if (marks.count() != 0) drawMarks(frameTarget, frameCamera, marks);
            markPreview.update(preview, frameCamera, data, this);
            if (markPreview.count() != 0) drawMarks(frameTarget, frameCamera, markPreview);
            if (showNavigation) drawNavigation(frameTarget, frameCamera);
        } finally { suspend(); }
    }

    private Entry scheduleGeometry(ChunkTileSource source, ChunkTiles.Tile tile, String key,
                                   boolean classifyProjection, GeometryMode mode, long now) {
        Entry scheduled = new Entry();
        scheduleGeometryInto(scheduled, source, tile, key, classifyProjection, mode, now);
        entries.put(key, scheduled);
        return scheduled;
    }

    private void scheduleGeometryInto(Entry scheduled, ChunkTileSource source, ChunkTiles.Tile tile, String key,
                                      boolean classifyProjection, GeometryMode mode, long now) {
        scheduled.lastWanted = now;
        scheduled.mode = mode;
        scheduled.abandoned = false;
        scheduled.started = false;
        scheduled.work = CompletableFuture.supplyAsync(() -> {
            scheduled.started = true;
            if (scheduled.abandoned || closed.get()) return null;
            try {
                var data = source.geometry(tile, key);
                if (data == null) return null;
                var mesh = data.mesh();
                try {
                    if (closed.get() || scheduled.abandoned || !source.contains(tile, key)) {
                        mesh.close(); return null;
                    }
                    scheduled.mask = data.mask();
                    var atlas = classifyProjection ? source.animatedAtlas() : null;
                    var metadata = atlas == null ? null : source.projectionMetadata(tile, key);
                    scheduled.bounds = metadata != null && metadata.bounds() != null ? metadata.bounds() : mesh.bounds();
                    BitSet animated = null;
                    if (metadata != null && scheduled.bounds.equals(metadata.bounds())
                            && (!metadata.animated() || metadata.animatedBounds() != null)) {
                        scheduled.animated = metadata.animated();
                        scheduled.animatedBounds = metadata.animatedBounds();
                    } else {
                        if (atlas != null) animated = mesh.animatedOpaqueFaces(atlas);
                        scheduled.animatedBounds = animated == null ? null : mesh.animatedOpaqueBounds(animated);
                        scheduled.animated = atlas == null || scheduled.animatedBounds != null;
                    }
                    boolean hasTranslucent = mesh.opaqueTrianglesSize() != mesh.triangleVertices();
                    if (classifyProjection) source.rememberProjectionMetadata(key, tile,
                            new ChunkTileSource.ProjectionMetadata(scheduled.bounds, scheduled.animated,
                                    hasTranslucent, scheduled.animatedBounds));
                    if (mode == GeometryMode.SHADER) {
                        var layers = mesh.partitionSolid();
                        scheduled.solid = layers.stationary() != null;
                        if (scheduled.solid) {
                            scheduled.solidMaterialStates = layers.stationary().solidMaterialStates();
                            scheduled.animated = atlas == null || scheduled.animated
                                    && layers.stationary().hasAnimatedOpaqueFaces(atlas);
                        }
                        return scheduled.solid ? layers : new DetailedGeometry.Layers(layers.live(), null);
                    }
                    if (mode == GeometryMode.PROJECTED && atlas != null && (scheduled.animated || hasTranslucent)) {
                        BitSet liveFaces = scheduled.animated
                                ? (animated == null ? mesh.animatedOpaqueFaces(atlas) : animated) : new BitSet();
                        var layers = mesh.partition(liveFaces);
                        if (layers.stationary() != null && layers.live() != null) {
                            scheduled.splitLive = true;
                            return layers;
                        }
                        if (layers.live() != null) return new DetailedGeometry.Layers(layers.live(), null);
                        return layers;
                    }
                    return new DetailedGeometry.Layers(mesh, null);
                } catch (java.io.IOException | RuntimeException | Error failure) {
                    mesh.close(); throw failure;
                }
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }, decoding);
    }

    private int uploadReady(Entry entry, String key, ChunkTiles.Tile tile, int batch, long deadline) {
        int transferred = 0;
        try {
            var layers = entry.work.join();
            if (layers == null) {
                if (!entry.hasDrawable()) entries.remove(key);
                else entry.work = null;
                return 0;
            }
            boolean keepExistingLive = entry.live != null;
            do {
                boolean live = !keepExistingLive && entry.preparedStationary != null;
                if (entry.upload == null)
                    entry.upload = prepareDetailedUpload(live ? layers.live() : layers.stationary());
                var progress = entry.upload.advance(batch - transferred);
                transferred += progress.bytes();
                var mesh = progress.complete();
                // The graphics backend may defer a batch while all of its staging buffers are in use.
                if (mesh == null) break;
                entry.upload.close();
                entry.upload = null;
                if (!live && !keepExistingLive && layers.live() != null) {
                    entry.preparedStationary = mesh;
                } else {
                    entry.work = null;
                    try { layers.close(); }
                    catch (RuntimeException | Error failure) { mesh.close(); throw failure; }
                    if (keepExistingLive) {
                        entry.gpu = mesh;
                        residentBytes += mesh.bytes();
                    } else {
                        entry.gpu = live ? entry.preparedStationary : mesh;
                        entry.live = live ? mesh : null;
                        entry.preparedStationary = null;
                        residentBytes += entry.gpuBytes();
                    }
                    break;
                }
            } while (transferred < batch && System.nanoTime() < deadline);
        } catch (RuntimeException | Error failure) {
            residentBytes -= entry.gpuBytes();
            entry.release();
            entry.work = null;
            if (failure instanceof Error critical) throw critical;
            entry.failed = true;
            LogUtils.getLogger().error("Cannot upload Earth map tile {},{}", tile.x(), tile.z(), failure);
        }
        return transferred;
    }

    private void discardOtherGeometryMode(GeometryMode mode) {
        if (geometryMode == mode) return;
        geometryMode = mode;
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.mode == mode || entry.gpu != null && (mode == GeometryMode.NORMAL
                    || mode == GeometryMode.PROJECTED && entry.live == null && !entry.animated)) continue;
            if (entry.hasDrawable()) residentBytes -= entry.gpuBytes();
            entry.release();
            iterator.remove();
        }
    }

    private boolean settledProjection(MapCamera camera, RenderTarget target, long now) {
        var old = projectionCamera;
        // Translation only moves the screen anchor of the same world-aligned images.
        // Treat the initial open (old == null) as already settled so projected disk reads begin on Frame 0.
        if (old == null) {
            projectionWidth = target.width;
            projectionHeight = target.height;
            projectionChanged = now - PROJECTION_SETTLE_NS;
        } else if (!camera.sameViewportProjection(old)
                || projectionWidth != target.width || projectionHeight != target.height) {
            projectionWidth = target.width;
            projectionHeight = target.height;
            projectionChanged = now;
        }
        projectionCamera = camera;
        return now - projectionChanged >= PROJECTION_SETTLE_NS;
    }

    private ProjectedTileIndex.Mesh projectionMesh(ChunkTiles.Tile tile, String key, int mask,
                                                   ChunkTileSource.ProjectionMetadata metadata, Entry entry) {
        boolean animated = metadata.animated() && (entry == null || entry.mode != GeometryMode.PROJECTED || entry.live == null);
        return new ProjectedTileIndex.Mesh(tile, key, mask, metadata.bounds(), animated);
    }

    private static Placed place(ChunkTiles.Tile tile, String key, int mask, Entry entry) {
        boolean animated = entry.animated && (entry.mode != GeometryMode.PROJECTED || entry.live == null);
        return new Placed(tile, key, mask, entry.gpu, entry.live, entry.solid, entry.bounds, animated,
                entry.solidMaterialStates);
    }

    private static DetailedGeometry.Bounds geometryBounds(ChunkTileSource source, ChunkTiles.Tile tile,
                                                           String key, Entry resident) {
        if (resident != null && resident.hasDrawable()) return resident.bounds;
        var metadata = source.projectionMetadata(tile, key);
        return metadata == null ? null : metadata.bounds();
    }

    private void retainDisplayedLayers(List<Placed> draws, int start, ChunkTiles.Tile tile, int mask, Set<String> active) {
        var previousLayers = displayedLayers.getOrDefault(tile, List.of());
        int covered = 0;
        for (int i = start; i < draws.size(); i++) covered |= draws.get(i).mask;
        // Commit a replacement per quadrant. An already drawn fragment stays live until its replacement is ready.
        if ((covered & mask) != mask) for (var previous : previousLayers) {
            int missing = previous.mask() & mask & ~covered;
            if (missing == 0) continue;
            var resident = entries.get(previous.key());
            if (resident == null || resident.gpu == null) continue;
            draws.add(place(tile, previous.key(), missing, resident));
            active.add(previous.key());
            covered |= missing;
            if ((covered & mask) == mask) break;
        }
        int count = draws.size() - start;
        if (count == 0) { displayedLayers.remove(tile); return; }
        if (count == previousLayers.size()) {
            int matching = 0;
            while (matching < count) {
                var draw = draws.get(start + matching);
                var previous = previousLayers.get(matching);
                if (draw.mask != previous.mask() || !draw.key.equals(previous.key())) break;
                matching++;
            }
            if (matching == count) return;
        }
        var layers = new ArrayList<DisplayedLayer>(count);
        for (int i = start; i < draws.size(); i++) {
            var layer = draws.get(i);
            layers.add(new DisplayedLayer(layer.key, layer.mask));
        }
        displayedLayers.put(tile, List.copyOf(layers));
    }

    private void addProjectedTile(List<Placed> draws, ChunkTileSource source, VisibleTile requested,
                                  boolean keepMesh) {
        var tile = requested.tile();
        String key = requested.key();
        int mask = requested.mask();
        // Reuse the exact baseProjectionMask snapshotted for planned/preflight so a concurrent tile
        // update cannot widen coverageMasks beyond what ProjectedTerrain actually verified.
        addProjectedLayer(draws, source, tile, key, mask & requested.baseProjectionMask(), keepMesh);
        for (var fragment : requested.fragments())
            addProjectedLayer(draws, source, tile, fragment.contentKey(), mask & fragment.mask(), keepMesh);
    }

    private void addProjectedLayer(List<Placed> draws, ChunkTileSource source, ChunkTiles.Tile tile,
                                   String key, int mask, boolean keepMesh) {
        if (mask == 0) return;
        var metadata = source.projectionMetadata(tile, key);
        if (metadata == null) return;
        Entry entry = keepMesh ? entries.get(key) : null;
        draws.add(entry != null && entry.gpu != null ? place(tile, key, mask, entry)
                : new Placed(tile, key, mask, null, entry != null ? entry.live : null, false, metadata.bounds(), false, null));
    }

    private void updateCoverage(MapCamera camera, List<Placed> drawn) {
        if (coverage == null) coverage = createCoverage(COVERAGE_SIZE);
        int x=Math.floorDiv((int)Math.floor(camera.x()),16)-COVERAGE_SIZE/2;
        int z=Math.floorDiv((int)Math.floor(camera.z()),16)-COVERAGE_SIZE/2;
        boolean moved = coverageX != x || coverageZ != z;
        if (!moved && coverageMasks.size() == drawn.size()) {
            boolean same = true;
            for (Placed placed : drawn) {
                if (!Objects.equals(coverageMasks.get(placed.tile), placed.mask)) { same = false; break; }
            }
            if (same) return;
        }
        var next = new HashMap<ChunkTiles.Tile, Integer>();
        for (Placed placed : drawn) next.merge(placed.tile, placed.mask, (a, b) -> a | b);
        boolean unchanged = coverageMasks.equals(next);
        if (unchanged && !moved) return;
        if (!unchanged) coverageRevision++;
        var pixels=coverage.getPixels();
        if (moved) {
            for (int cz=0;cz<COVERAGE_SIZE;cz++) for (int cx=0;cx<COVERAGE_SIZE;cx++) setPixel(pixels,cx,cz,0);
            for (var item : next.entrySet()) setCoverage(pixels, item.getKey(), item.getValue(), x, z);
        } else {
            for (var item : coverageMasks.entrySet()) {
                if (!next.containsKey(item.getKey())) setCoverage(pixels, item.getKey(), 0, x, z);
            }
            for (var item : next.entrySet()) {
                if (!Objects.equals(coverageMasks.get(item.getKey()), item.getValue()))
                    setCoverage(pixels, item.getKey(), item.getValue(), x, z);
            }
        }
        coverageX=x; coverageZ=z;
        coverageMasks.clear();
        coverageMasks.putAll(next);
        coverage.upload();
    }

    private static void setCoverage(com.mojang.blaze3d.platform.NativeImage pixels, ChunkTiles.Tile tile,
                                    int mask, int originX, int originZ) {
        for (int q = 0; q < 4; q++) {
            int x = tile.x() * 2 + (q & 1) - originX;
            int z = tile.z() * 2 + (q >> 1) - originZ;
            if (x >= 0 && x < COVERAGE_SIZE && z >= 0 && z < COVERAGE_SIZE)
                setPixel(pixels, x, z, (mask & (1 << q)) != 0 ? 0xFFFFFFFF : 0);
        }
    }

    private boolean trim(Set<String> active, Set<String> readyEvictable, Set<String> staticEvictable,
                         boolean retain, boolean cacheView) {
        if (retain && residentBytes <= offscreenLimit) return false;
        int retired = 0;
        long deadline = 0;
        long bytes = 0;
        for (var item : entries.entrySet())
            if (item.getValue().hasDrawable() && !active.contains(item.getKey())) bytes += item.getValue().gpuBytes();
        long limit = retain ? offscreenLimit : 0;
        // Evict truly offscreen tiles down to offscreenLimit. Do NOT evict active viewport READY or
        // STATIC_READY VBOs under normal VRAM occupancy: live visible geometry is needed immediately
        // on the first frame of wheel zoom or reverse pan (when cacheView flips false), and evicting
        // visible VBOs causes 1-2 frame coverage withdrawals and black/coarse flashes while re-decoding.
        var iterator = entries.entrySet().iterator();
        while (bytes > limit && iterator.hasNext() && retired < RETIRE_BATCH
                && (deadline == 0 || System.nanoTime() < deadline)) {
            var item = iterator.next();
            Entry entry = item.getValue();
            if (!entry.hasDrawable() || active.contains(item.getKey())) continue;
            if (deadline == 0) deadline = System.nanoTime() + RETIRE_BUDGET_NS;
            long removed = entry.gpuBytes();
            entry.release();
            iterator.remove();
            residentBytes -= removed;
            bytes -= removed;
            retired++;
        }
        // Only reclaim in-view pictured static VBOs under genuine extreme VRAM pressure.
        long emergencyCeiling = Math.max(offscreenLimit * 4L, 2048L << 20);
        if (cacheView && !readyEvictable.isEmpty() && residentBytes > emergencyCeiling) {
            for (String key : readyEvictable) {
                if (residentBytes <= emergencyCeiling || retired >= RETIRE_BATCH
                        || (deadline != 0 && System.nanoTime() >= deadline)) break;
                Entry entry = entries.get(key);
                if (entry == null || !entry.hasDrawable()) continue;
                if (deadline == 0) deadline = System.nanoTime() + RETIRE_BUDGET_NS;
                long removed = entry.gpuBytes();
                entry.release();
                entries.remove(key);
                residentBytes -= removed;
                retired++;
            }
        }
        if (cacheView && !staticEvictable.isEmpty() && residentBytes > emergencyCeiling) {
            for (String key : staticEvictable) {
                if (residentBytes <= emergencyCeiling || retired >= RETIRE_BATCH
                        || (deadline != 0 && System.nanoTime() >= deadline)) break;
                Entry entry = entries.get(key);
                if (entry == null || entry.gpu == null || !entry.splitLive || entry.live == null) continue;
                if (deadline == 0) deadline = System.nanoTime() + RETIRE_BUDGET_NS;
                long removed = entry.releaseStaticOnly();
                if (removed > 0) {
                    residentBytes -= removed;
                    retired++;
                }
            }
        }
        return retired != 0;
    }

    public void suspend() { frameTarget=null; frameDraws=List.of(); frameContraptionBlocks=List.of(); frameEdge=false; }
    public void resetSurface() {
        suspend();
        projectionCamera = null;
        resetProjectedTerrain();
        surfaceRenderer.reset();
        entries.values().forEach(Entry::release);
        entries.clear();
        displayedKeys.clear();
        displayedLayers.clear();
        coverageMasks.clear();
        coverageX = Integer.MIN_VALUE;
        coverageZ = Integer.MIN_VALUE;
        coverageRevision++;
        marks.clear(); claims.clear(); markPreview.clear(); addonOverlays.clear(); xaeroPlus.clear();
        residentBytes = 0;
    }
    public Statistics statistics() { return statistics; }
    public Surface visibleSurface() { return surfaceRenderer.surface(); }
    public long coverageRevision() { return coverageRevision; }

    public boolean hasDetailAt(int x, int z) {
        var tile = new ChunkTiles.Tile(Math.floorDiv(x, 32), Math.floorDiv(z, 32));
        int quarter = Math.floorMod(x, 32) / 16 + 2 * (Math.floorMod(z, 32) / 16);
        return (coverageMasks.getOrDefault(tile, 0) & (1 << quarter)) != 0;
    }

    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        resetSurface();
        decoding.shutdownNow();
        surfaceRenderer.close();
        closeAuxiliaryGeometry();
        if(coverage!=null) coverage.close();
        delegate.close();
    }

    private enum GeometryMode { NORMAL, PROJECTED, SHADER }
    private record VisibleTile(ChunkTiles.Tile tile, String key, int mask, int baseProjectionMask, Entry cached,
                               ChunkTileSource.ProjectionMetadata metadata, List<ChunkTileSource.Fragment> fragments,
                               boolean visiblePriority, boolean shadowOnly, double distanceSquared) { }


    private static final class Entry {
        int mask;
        long lastWanted;
        CompletableFuture<DetailedGeometry.Layers> work;
        GeometryUpload upload;
        KuiNativeTerrainRenderer.GpuMesh gpu, live, preparedStationary;
        DetailedGeometry.Bounds bounds, animatedBounds;
        BitSet solidMaterialStates;
        boolean animated;
        boolean solid;
        boolean splitLive;
        GeometryMode mode;
        boolean failed;
        volatile boolean started;
        volatile boolean abandoned;

        boolean hasDrawable() { return gpu != null || live != null; }
        long gpuBytes() { return (gpu == null ? 0 : gpu.bytes()) + (live == null ? 0 : live.bytes()); }

        long releaseStaticOnly() {
            if (gpu == null || !splitLive || live == null) return 0;
            long freed = gpu.bytes();
            gpu.close();
            gpu = null;
            return freed;
        }

        void release() {
            abandoned = true;
            if (upload != null) { upload.close(); upload = null; }
            if (preparedStationary != null) { preparedStationary.close(); preparedStationary = null; }
            if (gpu != null) { gpu.close(); gpu = null; }
            if (live != null) { live.close(); live = null; }
            if (work != null) work.thenAccept(data -> { if (data != null) data.close(); });
        }
    }
    private record Placed(ChunkTiles.Tile tile, String key, int mask, KuiNativeTerrainRenderer.GpuMesh mesh,
                          KuiNativeTerrainRenderer.GpuMesh live,
                          boolean solid,
                          DetailedGeometry.Bounds bounds, boolean animated, BitSet solidMaterialStates) { }

    private static DetailedGeometry.Bounds union(DetailedGeometry.Bounds a, DetailedGeometry.Bounds b) {
        return new DetailedGeometry.Bounds(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }
}
