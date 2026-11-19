package io.github.kltyton.xaeroearth.client.bridge;

import com.mojang.logging.LogUtils;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene.Surface;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTiles;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.TerrainDetail;
import io.github.kltyton.xaeroearth.client.integration.ftb.FtbClaimsOverlay;
import io.github.kltyton.xaeroearth.client.integration.xaero.highlight.PublishedHighlights;
import io.github.kltyton.xaeroearth.client.mapmark.MapMark;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import io.github.kltyton.xaeroearth.client.render.map.MapHighlightField;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Vector4f;
import net.minecraft.client.Minecraft;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;
import xaero.map.region.texture.LeafRegionTexture;
import xaero.map.region.texture.RegionTexture;

/** Copies the native visible map caches. Xaero retains loading, permissions and cave selection. */
public final class XaeroMapBridge implements AutoCloseable {
    private static final int TEXTURE_COPY_BATCH = 16;
    private static final int INITIAL_TEXTURE_COPY_BATCH = 32;
    private static final int INITIAL_INLINE_NORMALIZE_LIMIT = 24;
    private static final long INITIAL_INLINE_NORMALIZE_BUDGET_NS = 1_500_000L;
    private static final int INLINE_PREVIEW_MAX_CELLS = 131_072;
    private static final int TEXTURE_PROBE_BATCH = 256;
    private static final int HUD_SEED_LIMIT = 128;
    private static final int REGION_PROBE_BATCH = 32;
    private static final int INITIAL_REGION_PROBE_BATCH = 64;
    private static final long REGION_BUDGET_NS = 1_200_000L;
    private static final long INITIAL_REGION_BUDGET_NS = 2_500_000L;
    private static final long TEXTURE_BUDGET_NS = 1_200_000L;
    private static final long INITIAL_TEXTURE_BUDGET_NS = 3_000_000L;
    private static final long TEXTURE_RESCAN_NS = 250_000_000L;
    private static final long SURFACE_PUBLISH_NS = 50_000_000L;
    private static final int SURFACE_TEXELS = MapTextureReadback.SURFACE_TEXELS;
    private record Bounds(long minX, long minZ, long maxX, long maxZ) {
        long width() { return maxX - minX; }
        long depth() { return maxZ - minZ; }
        boolean contains(Bounds other) {
            return minX <= other.minX && minZ <= other.minZ && maxX >= other.maxX && maxZ >= other.maxZ;
        }
        boolean intersects(Key key) {
            return key.x() < maxX && key.z() < maxZ
                    && key.x() + 64L * key.step() > minX && key.z() + 64L * key.step() > minZ;
        }
        Bounds padded() {
            long padX = Math.max(64L, Math.min(256L, Math.floorDiv(width() / 4L + 31L, 32L) * 32L));
            long padZ = Math.max(64L, Math.min(256L, Math.floorDiv(depth() / 4L + 31L, 32L) * 32L));
            return new Bounds(Math.floorDiv(minX, 32) * 32 - padX, Math.floorDiv(minZ, 32) * 32 - padZ,
                    Math.floorDiv(maxX + 31, 32) * 32 + padX, Math.floorDiv(maxZ + 31, 32) * 32 + padZ);
        }
    }
    private record Scene(MapProcessor processor, String world, String dimension, String multiworld, int cave) { }
    private record Key(int level, int regionX, int regionZ, int textureX, int textureZ) {
        int step() { return 1 << level; }
        long x() { return (regionX * 512L + textureX * 64L) * step(); }
        long z() { return (regionZ * 512L + textureZ * 64L) * step(); }
        Key parent(int parentLevel) {
            long px = Math.floorDiv(x(), 64L << parentLevel), pz = Math.floorDiv(z(), 64L << parentLevel);
            return new Key(parentLevel, (int) Math.floorDiv(px, 8), (int) Math.floorDiv(pz, 8),
                    (int) Math.floorMod(px, 8), (int) Math.floorMod(pz, 8));
        }
    }
    private record Tile(Key key, int version, byte[] rgba, byte[] light, int[] heights, int[] unmapped,
                        boolean worldColors, WeakReference<RegionTexture<?>> identity,
                        List<PublishedHighlights.Chunk> highlights) { }
    private record Request(Key key, int version, int[] heights, byte[] rgba, boolean worldColors,
                           WeakReference<RegionTexture<?>> identity, List<PublishedHighlights.Chunk> highlights) { }
    private record PreparedTile(Tile tile, int minY, int maxY) { }
    private record HighlightView(long generation, long terrain, long detail, Bounds window) { }
    private record FrustumExtent(MapCamera camera, double low, double high,
                                 double minX, double minZ, double maxX, double maxZ) {
        Bounds at(MapCamera anchor) {
            return new Bounds((long) Math.floor(anchor.x() + minX), (long) Math.floor(anchor.z() + minZ),
                    (long) Math.ceil(anchor.x() + maxX) + 1, (long) Math.ceil(anchor.z() + maxZ) + 1);
        }
    }
    private record SurfaceWork(Bounds viewport, boolean fast, boolean preview, AtomicBoolean obsolete,
                               CompletableFuture<Surface> result) {
        void cancel() { obsolete.set(true); result.cancel(false); }
    }
    private record InlinePreview(long ticket, List<Tile> snapshot, Bounds viewport, long revision,
                                 double captureX, double captureZ, double captureScale, double focusRadius,
                                 int floor, boolean fast, boolean edgeField, AtomicBoolean obsolete) { }

    private static final class RegionScan {
        final Bounds bounds;
        final double focusX, focusZ, scale;
        final Set<Key> available = new HashSet<>();
        int level = 3;
        int minX, minZ, maxX, maxZ, centerX, centerZ, column, row;

        RegionScan(Bounds bounds, double focusX, double focusZ, double scale) {
            this.bounds = bounds;
            this.focusX = focusX;
            this.focusZ = focusZ;
            this.scale = scale;
            selectLevel();
        }

        private void selectLevel() {
            int span = 512 << level;
            minX = (int) Math.floorDiv(bounds.minX, span);
            maxX = (int) Math.floorDiv(bounds.maxX - 1, span);
            minZ = (int) Math.floorDiv(bounds.minZ, span);
            maxZ = (int) Math.floorDiv(bounds.maxZ - 1, span);
            centerX = Math.max(minX, Math.min(maxX, (int) Math.floor(focusX / span)));
            centerZ = Math.max(minZ, Math.min(maxZ, (int) Math.floor(focusZ / span)));
            column = row = 0;
        }

        void next(MapProcessor processor, int cave, List<Key> found) {
            int rx = centeredCoordinate(minX, maxX, centerX, column);
            int rz = centeredCoordinate(minZ, maxZ, centerZ, row);
            var region = processor.getLeveledRegion(cave, rx, rz, level);
            if (region != null) addKeys(region, found, bounds);
            if (++column <= maxX - minX) return;
            column = 0;
            if (++row <= maxZ - minZ) return;
            if (--level >= 0) selectLevel();
        }

        private static int centeredCoordinate(int min, int max, int center, int index) {
            int pairs = Math.min(center - min, max - center);
            if (index <= 2 * pairs) return center + ((index & 1) == 1 ? (index + 1) / 2 : -index / 2);
            return center + (max - center > center - min ? index - pairs : pairs - index);
        }
    }
    private final ExecutorService conversion = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Earth map cache conversion"); thread.setDaemon(true); return thread;
    });
    private final LinkedHashMap<Key, Tile> tiles = new LinkedHashMap<>(128, 0.75f, true);
    private final Set<Key> reading = new HashSet<>();
    private final Map<Key, CompletableFuture<PreparedTile>> preparing = new LinkedHashMap<>();
    private final ExecutorService tilePreparation = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 3)), task -> {
                Thread thread = new Thread(task, "Earth mapped tile preparation");
                thread.setDaemon(true);
                return thread;
            });
    private final MapTextureReadback readback = new MapTextureReadback();
    private final Set<Key> required = new HashSet<>();
    private final List<Key> copyPlan = new ArrayList<>();
    private RegionScan regionScan;
    private Scene scene;
    private long generation, lastPlan, lastPublish;
    private int scanCursor;
    private double lodX, lodZ, lodScale;
    private boolean fastTerrain;
    private boolean closed, dirty;
    private volatile Surface surface;
    private SurfaceWork publishing;
    private long surfaceRevision;
    private NativeTileSession models;
    private MapCamera modelCamera;
    private final Object visibleModelsLock = new Object();
    private final Map<ChunkTiles.Tile, Integer> visibleModels = new HashMap<>();
    private final Map<ChunkTiles.Tile, Integer> visibleModelsView = Collections.unmodifiableMap(visibleModels);
    private Map<ChunkTiles.Tile, Integer> visibleModelsSnapshot = Map.of();
    private boolean visibleModelsSnapshotDirty;
    private long modelRevision = -1, mappedRevision = -1, tileRevision;
    private float brightness = Float.NaN;
    private Bounds viewport, visibleViewport;
    private FrustumExtent frustumExtent;
    private Bounds modelWindow;
    private List<FtbClaimsOverlay.Claim> lastClaims = List.of();
    private List<MapMark> claimShapes = List.of();
    private long claimShapeRevision = -1;
    private HighlightView highlightView;
    private List<PublishedHighlights.Chunk> highlightChunks = List.of();
    private MapHighlightField highlightField;
    private int terrainMinY = Integer.MAX_VALUE, terrainMaxY = Integer.MIN_VALUE;

    /** Selects the scene before the caller constructs its current camera. */
    public boolean prepareFrame(GuiMap map) {
        return prepareFrame(map.getMapProcessor(), io.github.kltyton.xaeroearth.client.EarthClient.modelsFor(map), true);
    }

    public boolean prepareFrame(MapProcessor processor, NativeTileSession currentModels) {
        return prepareFrame(processor, currentModels, false);
    }

    private boolean prepareFrame(MapProcessor processor, NativeTileSession currentModels, boolean worldMap) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Read mapped terrain on the client thread");
        if (closed) return false;
        synchronized (processor.renderThreadPauseSync) {
            if (processor.isRenderingPaused() || processor.isWaitingForWorldUpdate()
                    || !processor.isMapWorldUsable() || processor.isCurrentMapLocked()) return false;
            if (worldMap) brightness = processor.getBrightness();
            Scene selected = new Scene(processor, processor.getCurrentWorldId(), processor.getCurrentDimId(),
                    processor.getCurrentMWId(), processor.getCurrentCaveLayer());
            if (!selected.equals(scene)) {
                discardPublication();
                scene = selected; generation++; tiles.clear(); reading.clear(); surface = null; dirty = true;
                cancelPreparedTiles();
                required.clear(); copyPlan.clear(); regionScan = null; scanCursor = 0; lastPlan = lastPublish = 0;
                modelRevision = mappedRevision = -1;
                models = null; modelCamera = null; clearVisibleModels();
                viewport = visibleViewport = null;
                frustumExtent = null;
                modelWindow = null;
                lastClaims = List.of(); claimShapes = List.of(); claimShapeRevision = -1;
                highlightView = null; highlightChunks = List.of(); highlightField = null;
                terrainMinY = Integer.MAX_VALUE; terrainMaxY = Integer.MIN_VALUE;
            }
            updateModels(currentModels);
        }
        return true;
    }

    /** Reads the native caches within the same camera frustum used by the GPU passes. */
    public boolean capture(GuiMap map, MapCamera camera, XaeroMapBridge hud) {
        return capture(map.getMapProcessor(), io.github.kltyton.xaeroearth.client.EarthClient.modelsFor(map), camera, true, hud);
    }

    public boolean capture(MapProcessor expected, NativeTileSession currentModels, MapCamera camera) {
        return capture(expected, currentModels, camera, false, null);
    }

    private boolean capture(MapProcessor expected, NativeTileSession currentModels, MapCamera camera,
                            boolean worldMap, XaeroMapBridge hud) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Read mapped terrain on the client thread");
        if (closed || scene == null || scene.processor != expected) return false;
        MapProcessor processor = scene.processor;
        InlinePreview inlinePreview = null;
        synchronized (processor.renderThreadPauseSync) {
            if (processor.isRenderingPaused() || !matches(scene, processor)) return false;
            readback.poll();
            Scene selected = scene;
            installPreparedTiles(selected);
            modelCamera = camera;
            visibleViewport = bounds(camera);
            Bounds nextViewport = visibleViewport == null ? null : visibleViewport.padded();
            boolean fast = TerrainDetail.fast();
            boolean viewChanged = fastTerrain != fast;
            if (viewChanged) { fastTerrain = fast; dirty = true; }
            if (nextViewport != null && (viewport == null || !viewport.contains(visibleViewport)
                    || nextViewport.width() * 2 < viewport.width() || nextViewport.depth() * 2 < viewport.depth())) {
                viewport = nextViewport;
                dirty = viewChanged = true;
            }
            if (tiles.isEmpty() && visibleViewport != null) seedFromHud(hud);
            double scaleBand = TerrainDetail.captureScale(camera.pixelsPerBlock());
            if (lodScale != scaleBand) {
                lodScale = scaleBand;
                lodX = camera.x(); lodZ = camera.z();
                dirty = viewChanged = true;
            } else if (Math.hypot(camera.x() - lodX, camera.z() - lodZ) >= 32) {
                // The in-flight scan still covers its padded window. A focus-only change
                // should not repeatedly discard all the texture reads while panning.
                lodX = camera.x(); lodZ = camera.z();
                dirty = true;
            }
            double captureX = lodX, captureZ = lodZ, captureScale = lodScale;
            long now = System.nanoTime();
            if (visibleViewport != null && (viewChanged
                    || regionScan == null && scanCursor >= copyPlan.size() && now - lastPlan >= TEXTURE_RESCAN_NS)) {
                regionScan = new RegionScan(viewport, captureX, captureZ, captureScale);
                copyPlan.clear();
                required.clear();
                scanCursor = 0;
                lastPlan = now;
            }
            if (visibleViewport != null && regionScan != null) discoverRegions(selected);
            if (visibleViewport != null && scanCursor < copyPlan.size()) copyVisible(selected, generation, processor, worldMap);
            if (publishing != null && (publishing.fast() != fast || visibleViewport == null
                    || !publishing.viewport().contains(visibleViewport))) discardPublication();
            finishPublish();
            updateModels(currentModels);
            List<Tile> inlineSnapshot = null;
            Bounds inlineViewport = null;
            long inlineRevision = 0;
            double inlineFocusRadius = 0;
            int inlineFloor = 0;
            boolean inlineFast = fast, inlineEdgeField = false;
            AtomicBoolean inlineObsolete = null;
            if (publishing == null && dirty && visibleViewport != null && !tiles.isEmpty()
                    && (surface == null || now - lastPublish >= SURFACE_PUBLISH_NS)) {
                boolean preview = surface == null;
                Bounds frameViewport = preview
                        ? new Bounds(Math.floorDiv(visibleViewport.minX, 32) * 32 - 32,
                                Math.floorDiv(visibleViewport.minZ, 32) * 32 - 32,
                                Math.floorDiv(visibleViewport.maxX + 31, 32) * 32 + 32,
                                Math.floorDiv(visibleViewport.maxZ + 31, 32) * 32 + 32)
                        : viewport;
                List<Tile> snapshot = new ArrayList<>();
                for (Tile tile : tiles.values())
                    if (frameViewport.intersects(tile.key))
                        snapshot.add(tile);
                if (!snapshot.isEmpty()) {
                    dirty = preview && !fast;
                    lastPublish = preview ? 0 : now;
                    // This immutable copy is the only map data consumed by the compositor.
                    long revision = ++surfaceRevision;
                    int floor = (int) MapHeightBounds.resolve(processor, models, terrainMinY, terrainMaxY).min();
                    boolean edgeField = worldMap && selected.cave == Integer.MAX_VALUE;
                    double focusRadius = TerrainDetail.radius(captureScale, Double.POSITIVE_INFINITY);
                    var obsolete = new AtomicBoolean();
                    if (preview && previewCells(frameViewport, captureScale) <= INLINE_PREVIEW_MAX_CELLS) {
                        inlineSnapshot = snapshot;
                        inlineViewport = frameViewport;
                        inlineRevision = revision;
                        inlineFocusRadius = focusRadius;
                        inlineFloor = floor;
                        inlineEdgeField = edgeField;
                        inlineObsolete = obsolete;
                    } else {
                        var work = CompletableFuture.supplyAsync(() -> composite(
                                snapshot, frameViewport, revision, captureX, captureZ, captureScale, focusRadius, floor,
                                preview, fast, edgeField, obsolete), conversion);
                        publishing = new SurfaceWork(frameViewport, fast, preview, obsolete, work);
                    }
                }
            }
            if (inlineSnapshot != null) {
                // Capture fields before releasing processor.renderThreadPauseSync below.
                inlinePreview = new InlinePreview(generation, inlineSnapshot, inlineViewport, inlineRevision,
                        captureX, captureZ, captureScale, inlineFocusRadius, inlineFloor, inlineFast,
                        inlineEdgeField, inlineObsolete);
            }
        }
        if (inlinePreview != null) {
            Surface built = composite(inlinePreview.snapshot(), inlinePreview.viewport(), inlinePreview.revision(),
                    inlinePreview.captureX(), inlinePreview.captureZ(), inlinePreview.captureScale(),
                    inlinePreview.focusRadius(), inlinePreview.floor(), true, inlinePreview.fast(),
                    inlinePreview.edgeField(), inlinePreview.obsolete());
            if (!closed && generation == inlinePreview.ticket() && surface == null) surface = built;
        }
        return true;
    }

    private void discoverRegions(Scene selected) {
        var scan = regionScan;
        var found = new ArrayList<Key>();
        boolean initial = surface == null;
        int batch = initial ? INITIAL_REGION_PROBE_BATCH : REGION_PROBE_BATCH;
        long deadline = System.nanoTime() + (initial ? INITIAL_REGION_BUDGET_NS : REGION_BUDGET_NS);
        for (int i = 0; scan.level >= 0 && i < batch && (i == 0 || System.nanoTime() < deadline); i++) {
            scan.next(selected.processor, selected.cave, found);
        }
        scan.available.addAll(found);
        double focusRadius = TerrainDetail.radius(scan.scale, Double.POSITIVE_INFINITY);
        found.removeIf(key -> !wantedLod(key, scan.focusX, scan.focusZ, scan.scale, focusRadius, scan.available)
                || !required.add(key));
        found.sort(Comparator.comparing((Key key) -> !visibleViewport.intersects(key))
                .thenComparing(key -> tiles.containsKey(key))
                .thenComparing(Comparator.comparingInt(Key::level).reversed())
                .thenComparingDouble(key -> Math.hypot(key.x() + 32 * key.step() - scan.focusX,
                        key.z() + 32 * key.step() - scan.focusZ)));
        copyPlan.addAll(found);
        if (scan.level < 0) regionScan = null;
    }

    private void seedFromHud(XaeroMapBridge hud) {
        if (hud == null || !scene.equals(hud.scene) || hud.closed) return;
        for (Tile tile : hud.tiles.values()) {
            if (!tile.worldColors || !visibleViewport.intersects(tile.key)) continue;
            var texture = tile.identity.get();
            if (texture == null || !texture.isUploaded() || texture.shouldUpload()
                    || texture.getTextureVersion() != tile.version) continue;
            tiles.put(tile.key, tile);
            if (tiles.size() == HUD_SEED_LIMIT) break;
        }
        if (tiles.isEmpty()) return;
        terrainMinY = Math.min(terrainMinY, hud.terrainMinY);
        terrainMaxY = Math.max(terrainMaxY, hud.terrainMaxY);
        tileRevision++;
        dirty = true;
    }

    private boolean wantedLod(Key key, double x, double z, double scale, double focusRadius, Set<Key> available) {
        int preferred = preferredLod(key, x, z, scale, focusRadius);
        if (key.level >= preferred) return true;
        for (int level = preferred; level <= 3; level++) {
            Key parent = key.parent(level);
            if (!available.contains(parent)) continue;
            Tile tile = tiles.get(parent);
            if (tile == null) continue;
            int step = parent.step();
            int x0 = (int) ((key.x() - parent.x()) / step);
            int z0 = (int) ((key.z() - parent.z()) / step);
            int width = (64 * key.step() + step - 1) / step;
            int x1 = Math.min(64, x0 + width), z1 = Math.min(64, z0 + width);
            int[] sums = tile.unmapped;
            if (sums[z1 * 65 + x1] - sums[z1 * 65 + x0]
                    - sums[z0 * 65 + x1] + sums[z0 * 65 + x0] == 0) return false;
        }
        return true;
    }

    private static int preferredLod(Key key, double x, double z, double scale, double focusRadius) {
        double distance = Math.hypot(key.x() + 32 * key.step() - x, key.z() + 32 * key.step() - z);
        return TerrainDetail.mappedLevel(scale, distance, focusRadius);
    }

    private Bounds bounds(MapCamera camera) {
        var height = MapHeightBounds.resolve(scene.processor, models, terrainMinY, terrainMaxY);
        double minY = height.min(), maxY = height.max();
        if (terrainMinY != Integer.MAX_VALUE) minY = Math.min(minY, terrainMinY);
        if (terrainMaxY != Integer.MIN_VALUE) maxY = Math.max(maxY, terrainMaxY);
        minY -= camera.y(); maxY -= camera.y();
        var retained = frustumExtent;
        if (retained != null && retained.low() == minY && retained.high() == maxY
                && camera.sameViewportProjection(retained.camera())) return retained.at(camera);
        var inverse = camera.viewportProjectionMatrix().mul(camera.viewMatrix()).invert();
        Vector4f[] corners = new Vector4f[8];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = inverse.transform(new Vector4f((i & 1) == 0 ? -1 : 1,
                    (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1, 1));
            corners[i].div(corners[i].w);
        }
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        // The clipped frustum's X/Z extrema lie on its twelve edges or their height-plane intersections.
        for (int i = 0; i < corners.length; i++) for (int axis = 1; axis <= 4; axis <<= 1) {
            if ((i & axis) != 0) continue;
            Vector4f a = corners[i], b = corners[i | axis];
            double start = 0, end = 1, dy = b.y - a.y;
            if (dy == 0) {
                if (a.y < minY || a.y > maxY) continue;
            } else {
                double first = (minY - a.y) / dy, last = (maxY - a.y) / dy;
                start = Math.max(0, Math.min(first, last));
                end = Math.min(1, Math.max(first, last));
                if (start > end) continue;
            }
            double x0 = a.x + (b.x - a.x) * start, x1 = a.x + (b.x - a.x) * end;
            double z0 = a.z + (b.z - a.z) * start, z1 = a.z + (b.z - a.z) * end;
            minX = Math.min(minX, Math.min(x0, x1)); maxX = Math.max(maxX, Math.max(x0, x1));
            minZ = Math.min(minZ, Math.min(z0, z1)); maxZ = Math.max(maxZ, Math.max(z0, z1));
        }
        if (!Double.isFinite(minX)) return null;
        frustumExtent = new FrustumExtent(camera, minY, maxY, minX, minZ, maxX, maxZ);
        return frustumExtent.at(camera);
    }

    private void updateModels(NativeTileSession current) {
        synchronized (visibleModelsLock) { updateModelsLocked(current); }
    }

    private void updateModelsLocked(NativeTileSession current) {
        if (models != current) {
            models = current; clearVisibleModels(); modelRevision = -1; mappedRevision = -1;
        }
        if (models == null || viewport == null) {
            return;
        }
        long currentRevision = models.revision();
        boolean sameMap = mappedRevision == tileRevision && viewport.equals(modelWindow);
        if (modelRevision == currentRevision && sameMap) return;
        if (sameMap) {
            var changed = models.changedSince(modelRevision);
            if (changed != null) {
                for (var tile : changed) {
                    int mask = visibleModelMask(tile);
                    int previous = visibleModels.getOrDefault(tile, 0);
                    if (mask == previous) continue;
                    if (mask == 0) visibleModels.remove(tile); else visibleModels.put(tile, mask);
                    visibleModelsSnapshotDirty = true;
                }
                modelRevision = currentRevision;
                return;
            }
        }
        modelWindow = viewport;
        var visible = new HashMap<ChunkTiles.Tile, Integer>();
        var known = models.versionedTiles();
        long left = Math.floorDiv(viewport.minX, 32);
        long right = Math.floorDiv(viewport.maxX, 32);
        long top = Math.floorDiv(viewport.minZ, 32);
        long bottom = Math.floorDiv(viewport.maxZ, 32);
        // For a close view over a large pregenerated save, visit the viewport rather than
        // walking every cached tile each time the padded window crosses a chunk boundary.
        if (left >= Integer.MIN_VALUE && right <= Integer.MAX_VALUE && top >= Integer.MIN_VALUE
                && bottom <= Integer.MAX_VALUE && right - left + 1 <= known.size() / (bottom - top + 1)) {
            for (long z = top; z <= bottom; z++) for (long x = left; x <= right; x++)
                addVisibleModel(visible, new ChunkTiles.Tile((int) x, (int) z));
        } else {
            for (var tile : known) addVisibleModel(visible, tile);
        }
        if (!visibleModels.equals(visible)) {
            visibleModels.clear(); visibleModels.putAll(visible); visibleModelsSnapshotDirty = true;
        }
        modelRevision = currentRevision; mappedRevision = tileRevision;
    }

    private void addVisibleModel(Map<ChunkTiles.Tile, Integer> visible, ChunkTiles.Tile tile) {
        int mask = visibleModelMask(tile);
        if (mask != 0) visible.put(tile, mask);
    }

    private void clearVisibleModels() {
        synchronized (visibleModelsLock) {
            visibleModels.clear(); visibleModelsSnapshot = Map.of(); visibleModelsSnapshotDirty = false;
        }
    }

    private int visibleModelMask(ChunkTiles.Tile tile) {
        if (!models.versionedTiles().contains(tile)) return 0;
        int minX = tile.x() * 32, minZ = tile.z() * 32;
        if (minX > modelWindow.maxX || minZ > modelWindow.maxZ
                || minX + 32 <= modelWindow.minX || minZ + 32 <= modelWindow.minZ) return 0;
        int mask = 0, available = models.availableMask(tile);
        for (int q = 0; q < 4; q++) {
            if ((available & (1 << q)) == 0) continue;
            int x0 = (q & 1) * 16, z0 = (q >> 1) * 16;
            if (mappedQuarter(minX + x0, minZ + z0)) mask |= 1 << q;
        }
        return mask;
    }

    private static void addKeys(LeveledRegion<?> region, List<Key> keys, Bounds viewport) {
        if (!region.hasTextures()) return;
        int level = region.getLevel(), rx = region.getRegionX(), rz = region.getRegionZ();
        int tileSpan = 64 << level;
        long originX = 8L * rx * tileSpan, originZ = 8L * rz * tileSpan;
        int minX = (int) Math.max(0, Math.floorDiv(viewport.minX - originX, tileSpan));
        int maxX = (int) Math.min(7, Math.floorDiv(viewport.maxX - 1 - originX, tileSpan));
        int minZ = (int) Math.max(0, Math.floorDiv(viewport.minZ - originZ, tileSpan));
        int maxZ = (int) Math.min(7, Math.floorDiv(viewport.maxZ - 1 - originZ, tileSpan));
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            var texture = region.getTexture(x, z);
            if (texture != null && MapTextureReadback.exists(texture)) keys.add(new Key(level, rx, rz, x, z));
        }
    }

    private void copyVisible(Scene selected, long ticket, MapProcessor current, boolean worldMap) {
        List<Request> result = new ArrayList<>();
        boolean initial = surface == null;
        int copyBatch = initial ? INITIAL_TEXTURE_COPY_BATCH : TEXTURE_COPY_BATCH;
        long deadline = System.nanoTime() + (initial ? INITIAL_TEXTURE_BUDGET_NS : TEXTURE_BUDGET_NS);
        try {
            if (current != selected.processor || !matches(selected, current)) return;
            int limit = Math.min(copyPlan.size(), scanCursor + TEXTURE_PROBE_BATCH);
            int first = scanCursor;
            while (scanCursor < limit && result.size() + reading.size() + preparing.size() < copyBatch
                    && (scanCursor == first || System.nanoTime() < deadline)) {
                Key key = copyPlan.get(scanCursor++);
                if (reading.contains(key) || preparing.containsKey(key)) continue;
                LeveledRegion<?> region = current.getLeveledRegion(selected.cave, key.regionX, key.regionZ, key.level);
                if (region == null || !region.hasTextures()) continue;
                if (region instanceof MapRegion leaf) {
                    // Texture uploads hold the region before its writer pause lock.
                    synchronized (leaf) {
                        synchronized (leaf.writerThreadPauseSync) {
                            copyTexture(key, region.getTexture(key.textureX, key.textureZ), result, worldMap);
                        }
                    }
                } else synchronized (region) { copyTexture(key, region.getTexture(key.textureX, key.textureZ), result, worldMap); }
            }
        } finally {
            if (!closed && ticket == generation && selected.equals(scene) && matches(selected, selected.processor)) {
                int inlineCount = 0;
                long inlineDeadline = surface == null ? System.nanoTime() + INITIAL_INLINE_NORMALIZE_BUDGET_NS : 0L;
                boolean inlineInstalled = false;
                for (Request request : result) {
                    if (surface == null && request.rgba != null && inlineCount < INITIAL_INLINE_NORMALIZE_LIMIT
                            && (inlineCount == 0 || System.nanoTime() < inlineDeadline)) {
                        if (installInline(selected, request)) {
                            inlineInstalled = true;
                            inlineCount++;
                            continue;
                        }
                    }
                    installOrRead(selected, ticket, request);
                }
                if (inlineInstalled) {
                    tileRevision++;
                    dirty = true;
                }
            }
        }
    }

    private void copyTexture(Key key, RegionTexture<?> texture, List<Request> result, boolean worldMap) {
        if (texture == null || texture.shouldUpload() || !texture.isUploaded()) return;
        int version = texture.getTextureVersion();
        Tile cached = tiles.get(key);
        if (cached != null && cached.version == version && cached.identity.get() == texture) return;
        int[] heights = new int[4096];
        boolean any = false;
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            int height = texture.getHeight(x, z);
            int top = texture.getTopHeight(x, z);
            if (top != 32767 && (height == 32767 || top > height)) height = top;
            heights[z * 64 + x] = height;
            any |= height != 32767;
        }
        if (!any) {
            result.add(new Request(key, version, heights, new byte[16384], true, new WeakReference<>(texture), List.of()));
            return;
        }
        // The leaf CPU buffer predates Xaero's registered chunk highlighters
        // (e.g. Open Parties and Claims). The published world-map texture has them.
        boolean hasHighlights = texture instanceof LeafRegionTexture leaf && leaf.getTileChunk().hasHighlights();
        boolean highlighted = worldMap && hasHighlights && MapTextureReadback.canRead(texture);
        byte[] rgba = highlighted ? null : MapTextureReadback.colors(texture);
        var overlays = hasHighlights && texture instanceof PublishedHighlights.Texture published
                ? published.earth$publishedHighlights() : List.<PublishedHighlights.Chunk>of();
        result.add(new Request(key, version, heights, rgba, highlighted || !hasHighlights,
                new WeakReference<>(texture), overlays));
    }

    private boolean installInline(Scene selected, Request request) {
        Tile cached = tiles.get(request.key);
        if (cached != null && cached.version == request.version && cached.identity.get() == request.identity.get()
                || reading.contains(request.key) || preparing.containsKey(request.key)) return false;
        if (currentTexture(selected, request.key, request.identity, request.version) == null) return false;
        PreparedTile prepared = normalizeTile(request, request.rgba);
        terrainMinY = Math.min(terrainMinY, prepared.minY());
        terrainMaxY = Math.max(terrainMaxY, prepared.maxY());
        tiles.put(prepared.tile().key, prepared.tile());
        return true;
    }

    private static long previewCells(Bounds viewport, double scale) {
        int step = 1 << TerrainDetail.mappedLevel(scale, 0, 32);
        while (((viewport.maxX - viewport.minX + step - 1) / step) * ((viewport.maxZ - viewport.minZ + step - 1) / step) > SURFACE_TEXELS)
            step <<= 1;
        step <<= 1;
        long width = (viewport.maxX - Math.floorDiv(viewport.minX, step) * step + step - 1) / step;
        long depth = (viewport.maxZ - Math.floorDiv(viewport.minZ, step) * step + step - 1) / step;
        return width * depth;
    }

    private void installOrRead(Scene selected, long ticket, Request request) {
        Tile cached = tiles.get(request.key);
        if (cached != null && cached.version == request.version && cached.identity.get() == request.identity.get()
                || reading.contains(request.key) || preparing.containsKey(request.key)) return;
        MapProcessor processor = selected.processor;
        synchronized (processor.renderThreadPauseSync) {
            if (!matches(selected, processor) || processor.isRenderingPaused()) return;
            var texture = currentTexture(selected, request.key, request.identity, request.version);
            if (texture == null) return;
            if (request.rgba != null) { prepareTile(request, request.rgba); return; }
            if (!MapTextureReadback.canRead(texture)) return;
            reading.add(request.key);
            readback.read(texture, rgba -> {
                synchronized (processor.renderThreadPauseSync) {
                    if (!closed && ticket == generation && selected.equals(scene) && matches(selected, processor)
                            && !processor.isRenderingPaused()
                            && currentTexture(selected, request.key, request.identity, request.version) != null)
                        prepareTile(request, rgba);
                }
            }, () -> { if (ticket == generation) reading.remove(request.key); });
        }
    }

    private static RegionTexture<?> currentTexture(Scene selected, Key key,
            WeakReference<RegionTexture<?>> identity, int version) {
        var region = selected.processor.getLeveledRegion(selected.cave, key.regionX, key.regionZ, key.level);
        var texture = region == null || !region.hasTextures() ? null : region.getTexture(key.textureX, key.textureZ);
        return texture != null && texture == identity.get() && !texture.shouldUpload()
                && texture.isUploaded() && texture.getTextureVersion() == version ? texture : null;
    }

    private void prepareTile(Request request, byte[] rgba) {
        // The render thread has already copied or read back all native data. Workers
        // only own the arrays, never the region, its pooled buffer or its GPU texture.
        preparing.put(request.key, CompletableFuture.supplyAsync(() -> normalizeTile(request, rgba), tilePreparation));
    }

    private void cancelPreparedTiles() {
        for (var work : preparing.values()) work.cancel(false);
        preparing.clear();
    }

    private static PreparedTile normalizeTile(Request request, byte[] rgba) {
        byte[] light = new byte[request.heights.length];
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        // Native leaf alpha stores block light; only an all-zero texel is unmapped.
        for (int i = 0; i < request.heights.length; i++) {
            int pixel = i * 4;
            light[i] = rgba[pixel + 3];
            boolean mapped = (rgba[pixel] | rgba[pixel + 1] | rgba[pixel + 2] | rgba[pixel + 3]) != 0;
            rgba[pixel + 3] = (byte) (mapped && request.heights[i] != 32767 ? 255 : 0);
            if (rgba[pixel + 3] != 0) {
                minY = Math.min(minY, request.heights[i]);
                maxY = Math.max(maxY, request.heights[i] + 1);
            }
        }
        int[] unmapped = new int[65 * 65];
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            int value = rgba[(z * 64 + x) * 4 + 3] == 0 ? 1 : 0;
            int index = (z + 1) * 65 + x + 1;
            unmapped[index] = value + unmapped[index - 1] + unmapped[index - 65] - unmapped[index - 66];
        }
        var tile = new Tile(request.key, request.version, rgba, light, request.heights, unmapped,
                request.worldColors, request.identity, request.highlights);
        return new PreparedTile(tile, minY, maxY);
    }

    private void installPreparedTiles(Scene selected) {
        var iterator = preparing.values().iterator();
        boolean changed = false;
        while (iterator.hasNext()) {
            var work = iterator.next();
            if (!work.isDone()) continue;
            iterator.remove();
            PreparedTile prepared;
            try { prepared = work.join(); }
            catch (CompletionException failure) {
                LogUtils.getLogger().warn("Cannot prepare Earth map texture", failure.getCause());
                continue;
            }
            Tile tile = prepared.tile();
            if (currentTexture(selected, tile.key, tile.identity, tile.version) == null) continue;
            terrainMinY = Math.min(terrainMinY, prepared.minY());
            terrainMaxY = Math.max(terrainMaxY, prepared.maxY());
            tiles.put(tile.key, tile);
            changed = true;
        }
        if (!changed) return;
        tileRevision++;
        if (regionScan == null) {
            while (tiles.size() > Math.max(1024, required.size() + 16)) tiles.remove(tiles.keySet().iterator().next());
        }
        dirty = true;
    }

    private static boolean matches(Scene selected, MapProcessor processor) {
        return processor.isMapWorldUsable() && !processor.isCurrentMapLocked() && !processor.isWaitingForWorldUpdate()
                && Objects.equals(selected.world, processor.getCurrentWorldId())
                && Objects.equals(selected.dimension, processor.getCurrentDimId())
                && Objects.equals(selected.multiworld, processor.getCurrentMWId())
                && selected.cave == processor.getCurrentCaveLayer();
    }

    private static Surface composite(List<Tile> tiles, Bounds viewport, long revision,
                                     double focusX, double focusZ, double scale, double focusRadius, int dimensionFloor,
                                     boolean preview, boolean fast, boolean edgeField, AtomicBoolean obsolete) {
        checkPublication(obsolete);
        long minX = viewport.minX, minZ = viewport.minZ, maxX = viewport.maxX, maxZ = viewport.maxZ;
        int step = 1 << TerrainDetail.mappedLevel(scale, 0, 32);
        while (((maxX - minX + step - 1) / step) * ((maxZ - minZ + step - 1) / step) > SURFACE_TEXELS) step <<= 1;
        if (preview || fast) {
            // Keep the samples used for the first visible transfer small. Use the
            // same source tiles and bounds for the refined surface that follows.
            step <<= 1;
        }
        minX = Math.floorDiv(minX, step) * step; minZ = Math.floorDiv(minZ, step) * step;
        int width = (int) ((maxX - minX + step - 1) / step), depth = (int) ((maxZ - minZ + step - 1) / step);
        byte[] rgba = new byte[width * depth * 4]; int[] heights = new int[width * depth];
        byte[] blockLight = new byte[width * depth];
        byte[] sourceSteps = new byte[width * depth];
        tiles.sort(Comparator.comparingInt((Tile tile) -> tile.key.level).reversed());
        for (Tile tile : tiles) {
            checkPublication(obsolete);
            boolean preferred = tile.key.level >= preferredLod(tile.key, focusX, focusZ, scale, focusRadius);
            int firstX = Math.max(0, (int) ((tile.key.x() - minX) / step));
            int firstZ = Math.max(0, (int) ((tile.key.z() - minZ) / step));
            int endX = Math.min(width, (int) ((tile.key.x() + 64L * tile.key.step() - minX + step - 1) / step));
            int endZ = Math.min(depth, (int) ((tile.key.z() + 64L * tile.key.step() - minZ + step - 1) / step));
            for (int z = firstZ; z < endZ; z++) for (int x = firstX; x < endX; x++) {
                int tx = (int) ((minX + x * (long) step - tile.key.x()) / tile.key.step());
                int tz = (int) ((minZ + z * (long) step - tile.key.z()) / tile.key.step());
                if (tx < 0 || tz < 0 || tx >= 64 || tz >= 64) continue;
                int source = tz * 64 + tx, destination = z * width + x;
                if (tile.rgba[source * 4 + 3] == 0) continue;
                if (!preferred && rgba[destination * 4 + 3] != 0) continue;
                System.arraycopy(tile.rgba, source * 4, rgba, destination * 4, 4);
                blockLight[destination] = tile.light[source];
                heights[destination] = tile.heights[source];
                // The field is also a mapped-cell sentinel. Preserve a positive
                // value when a very wide view needs a sample step above 127.
                sourceSteps[destination] = (byte) Math.min(127, Math.max(step, tile.key.step()));
            }
        }
        if (!preview) heights = filterDistantHeights(heights, sourceSteps, width, depth, step, obsolete);
        var outline = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var joins = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            if (x == 0) checkPublication(obsolete);
            int cell = z * width + x;
            if (rgba[cell * 4 + 3] == 0) continue;
            int edge = cell * 4;
            // The padded viewport is a crop, not an unexplored map boundary.
            if (x > 0 && rgba[(cell - 1) * 4 + 3] == 0) outline.add(edge);
            if (z + 1 < depth && rgba[(cell + width) * 4 + 3] == 0) outline.add(edge + 1);
            if (x + 1 < width && rgba[(cell + 1) * 4 + 3] == 0) outline.add(edge + 2);
            if (z > 0 && rgba[(cell - width) * 4 + 3] == 0) outline.add(edge + 3);
            if (step <= 16) {
                // Store mapped edges that can become a near/far boundary as detailed meshes arrive.
                if (x + 1 < width && sourceSteps[cell + 1] != 0 && (minX + (x + 1L) * step) % 16 == 0)
                    joins.add(edge + 2);
                if (z + 1 < depth && sourceSteps[cell + width] != 0 && (minZ + (z + 1L) * step) % 16 == 0)
                    joins.add(edge + 1);
            }
        }
        var patches = io.github.kltyton.xaeroearth.client.chunkmap.SurfacePatches.build(width, depth, step, heights, sourceSteps);
        checkPublication(obsolete);
        var boundary = edgeField ? io.github.kltyton.xaeroearth.client.chunkmap.ExploredBoundary.distances(rgba, width, depth, step)
                : new byte[0];
        checkPublication(obsolete);
        return new Surface(minX, minZ, width, depth, step, rgba, heights, blockLight, patches, outline.toIntArray(), joins.toIntArray(),
                boundary, dimensionFloor, revision);
    }

    private static int[] filterDistantHeights(int[] heights, byte[] sourceSteps, int width, int depth, int step,
                                              AtomicBoolean obsolete) {
        int[] result = heights.clone();
        int[] neighbours = new int[9];
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            if (x == 0) checkPublication(obsolete);
            int index = z * width + x;
            int suppliedStep = Byte.toUnsignedInt(sourceSteps[index]);
            if (suppliedStep <= 1) continue;
            int stride = Math.max(1, suppliedStep / step);
            int center = heights[index];
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            int n = 0;
            for (int dz = -stride; dz <= stride; dz += stride) {
                int sz = z + dz;
                if (sz < 0 || sz >= depth) continue;
                int row = sz * width;
                for (int dx = -stride; dx <= stride; dx += stride) {
                    int sx = x + dx;
                    if (sx < 0 || sx >= width) continue;
                    int sample = row + sx;
                    if (sourceSteps[sample] != 0) {
                        int h = heights[sample];
                        neighbours[n++] = h;
                        if (h < min) min = h;
                        if (h > max) max = h;
                    }
                }
            }
            if (n < 5 || (center >= min - 2 && center <= max + 2 && max - min <= 2)
                    || (min >= center - 2 && max <= center + 2)) continue;
            java.util.Arrays.sort(neighbours, 0, n);
            int median = neighbours[n / 2];
            int deviation = center - median;
            if (Math.abs(deviation) > 2)
                result[index] = median + Math.round(deviation * 0.35F);
        }
        return result;
    }

    private void finishPublish() {
        if (publishing == null || !publishing.result().isDone()) return;
        try {
            surface = publishing.result().join();
            dirty |= publishing.preview() && !publishing.fast();
        } catch (java.util.concurrent.CompletionException exception) {
            dirty = true;
            LogUtils.getLogger().error("Cannot composite mapped Xaero terrain", exception.getCause());
        } finally { publishing = null; }
    }

    private void discardPublication() {
        if (publishing == null) return;
        publishing.cancel();
        publishing = null;
        dirty = true;
    }

    private static void checkPublication(AtomicBoolean obsolete) {
        if (obsolete.get()) throw new CancellationException();
    }

    public Surface surface() { return surface; }
    public long terrainRevision() { return tileRevision; }
    public double minPickHeight() {
        return Math.min(terrainMinY, MapHeightBounds.resolve(scene.processor, models, terrainMinY, terrainMaxY).min()) - 1;
    }
    public double maxPickHeight() {
        return Math.max(terrainMaxY, MapHeightBounds.resolve(scene.processor, models, terrainMinY, terrainMaxY).max()) + 1;
    }
    public NativeTileSession models() { return models; }
    public Map<ChunkTiles.Tile, Integer> visibleModels() {
        synchronized (visibleModelsLock) {
            if (visibleModelsSnapshotDirty) {
                visibleModelsSnapshot = Map.copyOf(visibleModels);
                visibleModelsSnapshotDirty = false;
            }
            return visibleModelsSnapshot;
        }
    }

    /** Client-thread view for immediate rendering without copying the whole coverage map. */
    public Map<ChunkTiles.Tile, Integer> visibleModelsView() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Visible tile traversal must use the client thread");
        return visibleModelsView;
    }
    public long sceneKey() { return generation; }
    public double presentationUnit() { return 1; }
    public float brightness() { return brightness; }

    public MapHighlightField nativeHighlights(NativeTerrainScene renderer) {
        var window = viewport;
        if (window == null || closed) return null;
        var key = new HighlightView(generation, tileRevision, renderer.coverageRevision(), window);
        if (key.equals(highlightView)) return highlightField;
        highlightView = key;
        var chunks = new ArrayList<PublishedHighlights.Chunk>();
        for (var tile : tiles.values()) {
            if (tile.highlights.isEmpty() || !window.intersects(tile.key)) continue;
            for (var chunk : tile.highlights) {
                // The coarse world texture already carries Xaero's color. Add it only where
                // a detailed model replaced that texture, or when the copied color was untinted.
                if (!tile.worldColors || renderer.hasDetailAt(chunk.x() * 16, chunk.z() * 16)) chunks.add(chunk);
            }
        }
        chunks.sort(Comparator.comparingInt(PublishedHighlights.Chunk::x).thenComparingInt(PublishedHighlights.Chunk::z));
        if (!highlightChunks.equals(chunks)) {
            highlightChunks = List.copyOf(chunks);
            highlightField = MapHighlightField.buildPublished(chunks);
        }
        return highlightField;
    }

    public List<MapMark> claims(MapScope scope) {
        var claims = FtbClaimsOverlay.forMap(scope);
        if (claims.isEmpty()) return List.of();
        if (lastClaims == claims && claimShapeRevision == tileRevision) return claimShapes;
        var owners = new java.util.HashMap<Long, java.util.UUID>();
        for (var claim : claims) owners.put(chunkKey(claim.x(), claim.z()), claim.owner());
        var projected = new ArrayList<MapMark>();
        for (var claim : claims) {
            int x = claim.x() * 16, z = claim.z() * 16;
            var vertices = new ArrayList<net.minecraft.world.phys.Vec3>(4);
            for (int i = 0; i < 4; i++) {
                double cx = x + (i == 0 || i == 3 ? 0.125 : 15.875);
                double cz = z + (i < 2 ? 0.125 : 15.875);
                int height = mappedHeightAt(cx, cz, Integer.MIN_VALUE, false);
                if (height == Integer.MIN_VALUE) break;
                vertices.add(new net.minecraft.world.phys.Vec3(cx, height + 1, cz));
            }
            if (vertices.size() != 4) continue;
            int borders = 0;
            if (!claim.owner().equals(owners.get(chunkKey(claim.x(), claim.z() - 1)))) borders |= 1;
            if (!claim.owner().equals(owners.get(chunkKey(claim.x() + 1, claim.z())))) borders |= 2;
            if (!claim.owner().equals(owners.get(chunkKey(claim.x(), claim.z() + 1)))) borders |= 4;
            if (!claim.owner().equals(owners.get(chunkKey(claim.x() - 1, claim.z())))) borders |= 8;
            projected.add(new MapMark(new java.util.UUID(0x465442L, chunkKey(claim.x(), claim.z())),
                    MapMark.Kind.CLAIM, vertices, "", claim.rgb() | borders << 24));
        }
        lastClaims = claims; claimShapeRevision = tileRevision;
        return claimShapes = List.copyOf(projected);
    }

    private static long chunkKey(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }

    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> addonDimension() {
        var selected = scene;
        if (selected == null || !matches(selected, selected.processor)
                || selected.processor.isConsideringNetherFairPlay()) return null;
        var world = selected.processor.getMapWorld();
        return world == null ? null : world.getCurrentDimensionId();
    }

    public int heightAt(double x, double z, int unknown) {
        return heightAt(x, z, unknown, false);
    }

    /** Refine a visible surface drawing against the loaded terrain, without requesting chunks. */
    public double drawingGroundY(int x, int z, double renderedTop) {
        var view = scene;
        var world = net.minecraft.client.Minecraft.getInstance().level;
        if (view == null || world == null || view.processor.getWorld() != world
                || view.processor.getCurrentCaveLayer() != Integer.MAX_VALUE
                || !world.dimension().equals(addonDimension())) return renderedTop;
        var chunk = world.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return renderedTop;
        int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos(x, y, z);
        for (int depth = 0; depth < 48; depth++) {
            var state = chunk.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) return Math.min(renderedTop, pos.getY() + 1.0);
            if (!state.is(net.minecraft.tags.BlockTags.LEAVES) && !state.is(net.minecraft.tags.BlockTags.LOGS)) {
                var shape = state.getCollisionShape(chunk, pos);
                if (!shape.isEmpty())
                    return Math.min(renderedTop, pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y));
            }
            pos.setY(pos.getY() - 1);
        }
        return renderedTop;
    }

    private int heightAt(double x, double z, int unknown, boolean leafOnly) {
        int worldX = (int) Math.floor(x), worldZ = (int) Math.floor(z);
        var tile = new ChunkTiles.Tile(Math.floorDiv(worldX, 32), Math.floorDiv(worldZ, 32));
        int quadrant = (Math.floorMod(worldZ, 32) / 16) * 2 + Math.floorMod(worldX, 32) / 16;
        int mask;
        synchronized (visibleModelsLock) { mask = visibleModels.getOrDefault(tile, 0); }
        if (!leafOnly && models != null && (mask & (1 << quadrant)) != 0) {
            boolean near = modelCamera == null || TerrainDetail.intersects(modelCamera.x(), modelCamera.z(),
                    TerrainDetail.provisionalRadius(modelCamera.pixelsPerBlock(), Double.POSITIVE_INFINITY) + 12,
                    tile.x() * 32.0, tile.z() * 32.0, 32);
            if (near || (models.validatedMask(tile) & (1 << quadrant)) != 0) return models.heightAt(x, z, unknown);
        }
        return mappedHeightAt(x, z, unknown, leafOnly);
    }

    private int mappedHeightAt(double x, double z, int unknown, boolean leafOnly) {
        for (int level = 0; level <= (leafOnly ? 0 : 3); level++) {
            int step = 1 << level;
            long tileX = (long) Math.floor(x / (64 * step)), tileZ = (long) Math.floor(z / (64 * step));
            Key key = new Key(level, (int) Math.floorDiv(tileX, 8), (int) Math.floorDiv(tileZ, 8),
                    (int) Math.floorMod(tileX, 8), (int) Math.floorMod(tileZ, 8));
            Tile tile = tiles.get(key);
            if (tile == null) continue;
            int index = (int) ((z - key.z()) / step) * 64 + (int) ((x - key.x()) / step);
            if (tile.heights[index] == 32767 || tile.rgba[index * 4 + 3] == 0) continue;
            return tile.heights[index];
        }
        return unknown;
    }

    private boolean mappedQuarter(int x, int z) {
        for (int level = 0; level <= 3; level++) {
            int step = 1 << level;
            int tx = Math.floorDiv(x, 64 * step), tz = Math.floorDiv(z, 64 * step);
            Key key = new Key(level, Math.floorDiv(tx, 8), Math.floorDiv(tz, 8), Math.floorMod(tx, 8), Math.floorMod(tz, 8));
            Tile tile = tiles.get(key);
            if (tile == null) continue;
            int x0 = (int) ((x - key.x()) / step), z0 = (int) ((z - key.z()) / step);
            int x1 = x0 + 16 / step, z1 = z0 + 16 / step;
            int[] sums = tile.unmapped;
            if (sums[z1 * 65 + x1] - sums[z1 * 65 + x0] - sums[z0 * 65 + x1] + sums[z0 * 65 + x0] == 0) return true;
        }
        return false;
    }

    @Override public void close() {
        if (closed) return;
        readback.close();
        closed = true; generation++; tiles.clear(); reading.clear();
        cancelPreparedTiles();
        required.clear(); copyPlan.clear(); regionScan = null;
        surface = null; models = null; modelCamera = null; clearVisibleModels();
        highlightView = null; highlightChunks = List.of(); highlightField = null;
        discardPublication();
        conversion.shutdownNow();
        tilePreparation.shutdownNow();
    }
}
