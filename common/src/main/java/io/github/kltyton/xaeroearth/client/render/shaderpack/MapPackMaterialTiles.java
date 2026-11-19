package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.Draw;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.GpuMesh;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedGrid;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileIndex;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileStore;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;

/** Tracks which solid meshes are represented in each reusable unlit shader pack tile. */
final class MapPackMaterialTiles implements AutoCloseable {
    private static final int BASE_IMAGES = (int) ((192L << 20) / MapPackMaterialTileTextures.TILE_BYTES);
    private static final int MAX_IMAGES = (int) ((576L << 20) / MapPackMaterialTileTextures.TILE_BYTES);
    private static final int MAX_CACHED_VIEWS = 3;
    private static final int MAX_READS = 16;
    private static final class Storage {
        private static final java.util.concurrent.Executor IO = Executors.newScheduledThreadPool(4, task -> {
            var thread = new Thread(task, "Earth shader-pack tile storage");
            thread.setDaemon(true);
            return thread;
        });
    }
    private final MapPackMaterialTileTextures textures = new MapPackMaterialTileTextures();
    private final MapPackMaterialTileTransfer transfer = new MapPackMaterialTileTransfer();
    private final ProjectedTileIndex index = new ProjectedTileIndex();
    private final LinkedHashMap<ProjectedGrid.View, LinkedHashMap<ProjectedGrid.Cell, Entry>> viewCaches =
            new LinkedHashMap<>(6, 0.75f, true);
    private LinkedHashMap<ProjectedGrid.Cell, Entry> resident = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<ProjectedGrid.Cell, Read> reads = new HashMap<>();
    private ProjectedGrid.View view;
    private Path directory;
    private String scopeKey, resources;
    private String materialKey = "";
    private ProjectedTileStore store;
    private BitSet wavingStates;

    private static final class Entry {
        final Map<ProjectedTileIndex.Source, Integer> contributors;
        final MapPackMaterialTileTextures.Tile image;
        String pendingSlot, pendingFingerprint;

        Entry(Map<ProjectedTileIndex.Source, Integer> contributors, MapPackMaterialTileTextures.Tile image) {
            this.contributors = contributors;
            this.image = image;
        }
    }
    private record Read(long revision, String slot, ProjectedTileIndex.DiskKey key,
                        CompletableFuture<Optional<ProjectedTileStore.Image>> result) { }
    record Picture(ProjectedGrid.Cell cell, MapPackMaterialTileTextures.Tile image) { }
    record Frame(ProjectedGrid grid, List<Picture> pictures, Map<Draw, List<ProjectedGrid.Cell>> uncovered) { }

    @FunctionalInterface
    interface Recorder {
        void record(int framebuffer, ProjectedGrid grid, ProjectedGrid.Cell cell, List<Draw> draws);
    }

    void scope(ChunkTileSource source, MapScope scope, String profile) {
        Path nextDirectory = source == null ? null : source.projectedCacheDirectory();
        String resource = source == null ? null : source.projectedResourceKey();
        String nextResources = profile == null || resource == null ? null : profile + "\n" + materialKey + "\n" + resource;
        String nextScope = scope == null ? null : scopePart(scope.world()) + scopePart(scope.dimension())
                + scopePart(scope.subworld()) + scope.layer();
        if (Objects.equals(nextDirectory, directory) && Objects.equals(nextScope, scopeKey)
                && Objects.equals(nextResources, resources)) return;
        clear();
        transfer.close();
        directory = nextDirectory;
        scopeKey = nextScope;
        resources = nextResources;
        store = directory == null || scopeKey == null || resources == null ? null
                : new ProjectedTileStore(directory.resolve("pack-material"), Storage.IO);
    }

    void materials(IntBuffer ids, int stride, IntPredicate waving) {
        materialKey = materialFingerprint(ids);
        wavingStates = null;
        if (waving == null) return;
        wavingStates = new BitSet(ids.remaining() / stride);
        for (int i = ids.position(); i < ids.limit(); i += stride) {
            int material = (short) ids.get(i);
            if (waving.test(material))
                wavingStates.set((i - ids.position()) / stride);
        }
    }

    private static String materialFingerprint(IntBuffer ids) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = ByteBuffer.allocate(4096);
            while (ids.hasRemaining()) {
                var copy = ids.duplicate();
                int words = Math.min(copy.remaining(), bytes.capacity() / Integer.BYTES);
                copy.limit(copy.position() + words);
                bytes.asIntBuffer().put(copy);
                digest.update(bytes.array(), 0, words * Integer.BYTES);
                ids = ids.duplicate();
                ids.position(ids.position() + words);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Shader pack material IDs require SHA-256", failure);
        }
    }

    Frame prepare(MapCamera camera, int width, int height, List<Draw> solids, List<Draw> sources,
                  List<ProjectedTileIndex.Mesh> bounds, boolean reverseDepth, Recorder recorder) {
        var grid = new ProjectedGrid(camera, width, height);
        if (!grid.view().equals(view)) {
            switchView(grid.view());
        }
        long transferStarted = System.nanoTime();
        transfer.poll();
        long readbackNanos = System.nanoTime() - transferStarted;
        Map<GpuMesh, Draw> eligible = new IdentityHashMap<>();
        for (Draw solid : solids) eligible.put(solid.mesh(), solid);
        var selected = new ArrayList<ProjectedTileIndex.Mesh>();
        var drawBySource = new HashMap<ProjectedTileIndex.Source, Draw>();
        for (int i = 0; i < bounds.size(); i++) {
            var source = sources.get(i);
            if (source == null) continue;
            var drawn = eligible.get(source.mesh());
            var mesh = bounds.get(i);
            if (drawn == null || mesh.animated() || mesh.mask() != drawn.chunkMask()
                    || wavingStates != null && (mesh.solidMaterialStates() == null
                    || mesh.solidMaterialStates().intersects(wavingStates))) continue;
            selected.add(mesh);
            drawBySource.put(mesh.source(), drawn);
        }
        index.update(grid, selected);
        var cells = visibleCells(grid, width, height);
        var visible = new HashSet<>(cells);
        // A 4K viewport can need 160 cells including partially visible edge tiles.
        // Keep the small-window budget unless the current viewport actually needs more.
        int imageLimit = Math.max(BASE_IMAGES, Math.min(cells.size(), MAX_IMAGES));
        reclaim(visible, imageLimit);
        reads.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey()) && entry.getValue().revision() == index.revision(entry.getKey())) return false;
            discard(entry.getValue());
            return true;
        });
        var shown = new HashMap<ProjectedGrid.Cell, Map<ProjectedTileIndex.Source, Integer>>();
        var pictures = new ArrayList<Picture>(cells.size());
        long transferDeadline = System.nanoTime() + Math.max(0, 2_000_000L - readbackNanos);
        int recorded = 0;
        for (var cell : cells) {
            var contributors = index.contributorMasks(cell);
            var entry = resident.get(cell);
            boolean sameContributors = entry != null && contributors == entry.contributors;
            boolean subset = entry != null && (sameContributors || contributors.entrySet().containsAll(entry.contributors.entrySet()));
            boolean usable = entry != null && subset && containsDepth(entry, grid, camera);
            boolean current = usable && (sameContributors || entry.contributors.size() == contributors.size());
            if (!current && recorded < 2 && (recorded == 0 || System.nanoTime() < transferDeadline)
                    && !contributors.isEmpty()
                    && index.containsDepth(cell, grid, camera.x(), camera.y(), camera.z())) {
                if (entry == null) reclaim(visible, imageLimit - 1);
                var next = entry == null && resident.size() >= imageLimit ? null
                        : prepareReplacement(camera, grid, cell, contributors, solids, drawBySource, reverseDepth, recorder,
                                System.nanoTime() < transferDeadline);
                if (next != null) {
                    if (entry != null) entry.image.close();
                    resident.put(cell, next);
                    entry = next;
                    recorded++;
                    usable = current = subset = true;
                }
            }
            if (entry != null && !subset && !current) {
                resident.remove(cell);
                entry.image.close();
                continue;
            }
            if (!usable) continue;
            shown.put(cell, entry.contributors);
            pictures.add(new Picture(cell, entry.image));
        }
        if (recorded == 0 || System.nanoTime() < transferDeadline) flushWrite(reverseDepth);
        var uncovered = new IdentityHashMap<Draw, List<ProjectedGrid.Cell>>();
        index.missingPictureCells(shown, drawBySource.keySet()).forEach((source, missing) ->
                uncovered.put(drawBySource.get(source), missing));
        return new Frame(grid, pictures, uncovered);
    }

    private boolean containsDepth(Entry entry, ProjectedGrid grid, MapCamera camera) {
        var members = entry.contributors.keySet();
        return index.containsDepth(members, grid, entry.image.x(), entry.image.y(), entry.image.z())
                && index.containsDepth(members, grid, camera.x(), camera.y(), camera.z());
    }

    private Entry prepareReplacement(MapCamera camera, ProjectedGrid grid, ProjectedGrid.Cell cell,
                                     Map<ProjectedTileIndex.Source, Integer> contributors, List<Draw> solids,
                                     Map<ProjectedTileIndex.Source, Draw> drawBySource, boolean reverseDepth, Recorder recorder,
                                     boolean transferAvailable) {
        Read read = reads.get(cell);
        if (store != null && read == null) {
            if (reads.size() < MAX_READS) {
                String slot = index.slot(cell, scopeKey);
                // Legacy shader stage reuse could save shaded G-buffer values in the material planes.
                var key = index.diskKey(cell, scopeKey, "material-v3\n" + reverseDepth + "\n" + resources);
                reads.put(cell, new Read(index.revision(cell), slot, key, store.read(slot, key)));
            }
            return null;
        }
        if (!transferAvailable || read != null && !read.result().isDone()) return null;
        reads.remove(cell);
        try (var disk = read == null ? null : readResult(read)) {
            var loaded = restore(disk, cell, grid, contributors, reverseDepth);
            if (loaded != null) return loaded;
            var sourcesForCell = MapPackDraws.identitySet();
            for (var source : contributors.keySet()) sourcesForCell.add(drawBySource.get(source));
            // Match the live submission order for coplanar faces.
            var order = solids.stream().filter(sourcesForCell::contains).toList();
            var captured = capture(camera, cell, grid, contributors, order, reverseDepth, recorder);
            if (read != null) {
                captured.pendingSlot = read.slot();
                captured.pendingFingerprint = read.key().fingerprint();
            }
            return captured;
        }
    }

    private static String scopePart(String part) { return part.length() + ":" + part; }

    private Entry capture(MapCamera camera, ProjectedGrid.Cell cell, ProjectedGrid grid,
                          Map<ProjectedTileIndex.Source, Integer> contributors, List<Draw> order,
                          boolean reverseDepth, Recorder recorder) {
        var tile = textures.allocate(camera.x(), camera.y(), camera.z());
        boolean installed = false;
        try {
            textures.record(tile, reverseDepth, framebuffer -> recorder.record(framebuffer, grid, cell, order));
            installed = true;
            return new Entry(contributors, tile);
        } finally { if (!installed) tile.close(); }
    }

    private Entry restore(ProjectedTileStore.Image image, ProjectedGrid.Cell cell, ProjectedGrid grid,
                          Map<ProjectedTileIndex.Source, Integer> contributors, boolean reverseDepth) {
        if (image == null || image.colorFormat() != ProjectedTileStore.Image.ColorFormat.MATERIAL_RGBA16F
                || image.reverseDepth() != reverseDepth
                || !index.containsDepth(cell, grid, image.cameraX(), image.cameraY(), image.cameraZ())) return null;
        var tile = textures.allocate(image.cameraX(), image.cameraY(), image.cameraZ());
        boolean installed = false;
        try {
            MapPackMaterialTileTransfer.upload(tile, image);
            installed = true;
            return new Entry(contributors, tile);
        } finally { if (!installed) tile.close(); }
    }

    private void flushWrite(boolean reverseDepth) {
        if (store == null) return;
        for (var entry : resident.values()) {
            if (entry.pendingSlot == null) continue;
            try {
                if (!transfer.submit(entry.image, reverseDepth, store, entry.pendingSlot, entry.pendingFingerprint)) return;
            } catch (RuntimeException failure) {
                LogUtils.getLogger().warn("Cannot queue shader-pack material tile readback", failure);
            }
            entry.pendingSlot = entry.pendingFingerprint = null;
            return;
        }
    }

    private static ProjectedTileStore.Image readResult(Read read) {
        try { return read.result().join().orElse(null); }
        catch (java.util.concurrent.CompletionException failure) {
            LogUtils.getLogger().debug("Cannot load shader-pack material tile", failure);
            return null;
        }
    }

    private static void discard(Read read) {
        if (!read.result().cancel(false)) {
            read.result().thenAccept(image -> image.ifPresent(ProjectedTileStore.Image::close));
        }
    }

    static boolean scissor(ProjectedGrid grid, ProjectedGrid.Cell cell, MapPackDepthMapping raster) {
        var clip = grid.clip(cell, raster.xScale(), raster.yScale(), raster.xOffset(), raster.yOffset());
        if (clip == null) return false;
        org.lwjgl.opengl.GL11.glScissor(clip.x(), clip.y(), clip.width(), clip.height());
        return true;
    }

    private List<ProjectedGrid.Cell> visibleCells(ProjectedGrid grid, int width, int height) {
        var visible = grid.visible();
        var cells = new ArrayList<ProjectedGrid.Cell>();
        for (long y = visible.minY(); y <= visible.maxY(); y++) for (long x = visible.minX(); x <= visible.maxX(); x++) {
            var cell = new ProjectedGrid.Cell(x, y);
            if (index.hasContributors(cell)) cells.add(cell);
        }
        cells.sort(Comparator.comparingDouble(cell -> {
            double x = grid.cellScreenX(cell.x()) + (ProjectedGrid.TILE_PIXELS - width) * 0.5;
            double y = grid.cellScreenY(cell.y()) + (ProjectedGrid.TILE_PIXELS - height) * 0.5;
            return x * x + y * y;
        }));
        return cells;
    }

    private void switchView(ProjectedGrid.View nextView) {
        reads.values().forEach(MapPackMaterialTiles::discard);
        reads.clear();
        if (view != null) {
            for (var entry : resident.values()) {
                entry.pendingSlot = entry.pendingFingerprint = null;
            }
            if (resident.isEmpty()) viewCaches.remove(view);
            else viewCaches.put(view, resident);
        }
        view = nextView;
        var cached = viewCaches.get(nextView);
        if (cached != null) {
            resident = cached;
        } else {
            resident = new LinkedHashMap<>(64, 0.75f, true);
            viewCaches.put(nextView, resident);
        }
        var it = viewCaches.entrySet().iterator();
        while (viewCaches.size() > MAX_CACHED_VIEWS && it.hasNext()) {
            var oldest = it.next();
            if (oldest.getValue() == resident) continue;
            oldest.getValue().values().forEach(tile -> tile.image.close());
            oldest.getValue().clear();
            it.remove();
        }
    }

    private void reclaim(Set<ProjectedGrid.Cell> visible, int limit) {
        int total = 0;
        for (var bucket : viewCaches.values()) total += bucket.size();
        if (total <= limit) return;
        var viewIterator = viewCaches.entrySet().iterator();
        while (total > limit && viewIterator.hasNext()) {
            var bucket = viewIterator.next().getValue();
            if (bucket == resident) continue;
            var it = bucket.entrySet().iterator();
            while (total > limit && it.hasNext()) {
                it.next().getValue().image.close();
                it.remove();
                total--;
            }
            if (bucket.isEmpty()) viewIterator.remove();
        }
        var iterator = resident.entrySet().iterator();
        while (total > limit && iterator.hasNext()) {
            var slot = iterator.next();
            if (visible.contains(slot.getKey())) continue;
            slot.getValue().image.close();
            iterator.remove();
            total--;
        }
    }

    void clear() {
        for (var bucket : viewCaches.values()) {
            bucket.values().forEach(tile -> tile.image.close());
            bucket.clear();
        }
        viewCaches.clear();
        resident.values().forEach(tile -> tile.image.close());
        resident.clear();
        reads.values().forEach(MapPackMaterialTiles::discard);
        reads.clear();
        view = null;
    }

    @Override public void close() {
        clear();
        transfer.close();
        textures.close();
    }
}
