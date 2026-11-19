package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTiles;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Maintains orthographic opaque image tiles and their asynchronous disk transfers. */
public final class ProjectedTerrain<I, A, U> implements AutoCloseable {
    private static final int MAX_IMAGES = 192, MAX_CACHED_VIEWS = 4, MAX_READS = 64, MAX_ALLOCATIONS = 4, MAX_PENDING_STORES = 6;
    private static final int MAX_UPLOADS_PER_FRAME = 16, MAX_BAKES_PER_FRAME = 6;
    private static final String OPAQUE_RASTER_REVISION = "|leaf-cutout-v2";
    private static final long UPLOAD_BUDGET_NANOS = 3_500_000L;
    private static final long BAKE_BUDGET_NANOS = 3_000_000L;
    private final ScheduledExecutorService io = Executors.newScheduledThreadPool(
            Math.max(4, Math.min(6, Runtime.getRuntime().availableProcessors() / 2)), task -> {
        Thread thread = new Thread(task, "Earth projected tile storage");
        thread.setDaemon(true);
        return thread;
    });
    private final ProjectedTerrainBackend<I, A, U> backend;
    private final ProjectedTileIndex index = new ProjectedTileIndex();
    private final LinkedHashMap<ProjectedGrid.View, LinkedHashMap<ProjectedGrid.Cell, Entry>> viewCaches =
            new LinkedHashMap<>(8, 0.75f, true);
    private LinkedHashMap<ProjectedGrid.Cell, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);
    private ProjectedTileStore store;
    private Path directory;
    private String scopeKey, resourceKey;
    private ProjectedGrid.View view;
    private int allocations;
    private final AtomicInteger pendingStores = new AtomicInteger();
    private PreflightPreparation pendingPreparation;
    private long viewCacheHits, diskUploads, gpuBakes, depthRejections, compositedCells;

    private record Prepared(ProjectedGrid grid, List<ProjectedGrid.Cell> stationary,
                            List<ProjectedGrid.Cell> liveOnly, Set<ProjectedGrid.Cell> active,
                            boolean uploadBudgetSpent) { }
    private record PreflightPreparation(RenderTarget target, MapCamera camera, ChunkTileSource source, MapScope scope,
                                        List<ProjectedTileIndex.Mesh> meshes, Prepared prepared) { }
    private record OrderedDraw(int order, KuiNativeTerrainRenderer.Draw draw) { }
    private record Picture<I>(I image, Map<ProjectedTileIndex.Source, Integer> contributors,
                              ProjectedGrid.Depth depth, double x, double y, double z) {
        ProjectedTerrainBackend.Draw<I> draw(ProjectedGrid.Cell cell) {
            return new ProjectedTerrainBackend.Draw<>(cell, image, x, y, z);
        }
    }

    public ProjectedTerrain(ProjectedTerrainBackend<I, A, U> backend) { this.backend = Objects.requireNonNull(backend); }

    private final class Entry {
        final String slot;
        final ProjectedTileIndex.DiskKey diskKey;
        final Map<ProjectedTileIndex.Source, Integer> contributors;
        final boolean canAppend;
        long revision;
        final ProjectedTileStore.WriteTicket ticket;
        CompletableFuture<java.util.Optional<ProjectedTileStore.Image>> read;
        CompletableFuture<A> allocation;
        CompletableFuture<U> upload;
        Picture<I> image, retained;
        double x, y, z;
        boolean missing, transfer, needsPersist;
        volatile boolean valid = true;
        Entry(String slot, ProjectedTileIndex.DiskKey diskKey, long revision, ProjectedTileStore.WriteTicket ticket,
              Map<ProjectedTileIndex.Source, Integer> contributors, Picture<I> retained, boolean canAppend) {
            this.slot = slot; this.diskKey = diskKey; this.revision = revision; this.ticket = ticket;
            this.contributors = contributors; this.retained = retained; this.canAppend = canAppend;
        }

        Picture<I> displayed() {
            return image == null ? retained : image;
        }

        void install(I texture, ProjectedGrid.Depth depth) {
            image = new Picture<>(texture, contributors, depth, x, y, z);
            releaseRetained(this);
        }

        Picture<I> takeDisplayed() {
            Picture<I> picture = displayed();
            image = null; retained = null;
            return picture;
        }
    }

    /** Starts bounded image reads before deciding whether static meshes need to be loaded. */
    public ProjectionPreflight preflight(RenderTarget target, MapCamera camera, List<ProjectedTileIndex.Mesh> meshes,
                                          ChunkTileSource source, MapScope scope) {
        var prepared = prepare(target, camera, meshes, source, scope);
        pendingPreparation = prepared == null ? null : new PreflightPreparation(
                target, camera, source, scope, meshes, prepared);
        if (prepared == null) return ProjectionPreflight.NONE;
        var flags = new HashMap<ChunkTiles.Tile, Integer>();
        for (var mesh : meshes) {
            var metadata = source.projectionMetadata(mesh.tile(), mesh.contentKey());
            if (metadata == null || mesh.animated()) {
                flags.put(mesh.tile(), 0);
                continue;
            }
            boolean interior = !index.touchesViewportEdge(mesh.source());
            int required;
            if (metadata.animated() || metadata.translucent()) {
                required = interior ? ProjectionPreflight.STATIC_READY
                        : ProjectionPreflight.STATIC & ~(ProjectionPreflight.READY | ProjectionPreflight.STATIC_READY);
            } else {
                required = interior ? ProjectionPreflight.STATIC
                        : ProjectionPreflight.STATIC & ~(ProjectionPreflight.READY | ProjectionPreflight.STATIC_READY);
            }
            flags.merge(mesh.tile(), required, (old, next) -> old & next);
        }
        for (var cell : prepared.liveOnly())
            for (var member : index.members(cell)) flags.remove(member.tile());
        for (var cell : prepared.stationary()) {
            Entry entry = entries.get(cell);
            if (entry.image != null) {
                if (fitsCurrentDepth(entry.image, prepared.grid(), camera)) continue;
                depthRejections++;
            }
            var retained = entry.image == null ? entry.retained : null;
            boolean usable = retained != null && fitsCurrentDepth(retained, prepared.grid(), camera);
            for (var contributor : entry.contributors.entrySet()) {
                var member = contributor.getKey();
                int current = flags.getOrDefault(member.tile(), 0);
                if (current == 0) continue;
                int requiredMask = contributor.getValue();
                boolean drawn = usable && (retained.contributors().getOrDefault(member, 0) & requiredMask) == requiredMask;
                if (drawn && entry.canAppend) continue;
                int next = current & ~(ProjectionPreflight.READY | ProjectionPreflight.STATIC_READY);
                if (!drawn) next &= ~ProjectionPreflight.DISPLAYABLE;
                // A cell outside the bounded read queue must not hold up its live geometry.
                if (entry.read == null && entry.upload == null) next &= ~ProjectionPreflight.READING;
                if (next != current) flags.put(member.tile(), next);
            }
        }
        return new ProjectionPreflight(flags);
    }

    /** Composites available static cells while missing and animated geometry remains live. */
    public ProjectedDrawCoverage draw(RenderTarget target, Object depth, MapCamera camera,
                        List<KuiNativeTerrainRenderer.Draw> draws, List<ProjectedTileIndex.Mesh> meshes,
                        ChunkTileSource source, MapScope scope, float brightness) {
        var pending = pendingPreparation;
        pendingPreparation = null;
        var prepared = pending != null && pending.target() == target && pending.camera() == camera
                && pending.source() == source && Objects.equals(pending.scope(), scope)
                && pending.meshes() == meshes
                ? pending.prepared() : prepare(target, camera, meshes, source, scope);
        if (prepared == null) return ProjectedDrawCoverage.NONE;
        var grid = prepared.grid();
        var stationary = prepared.stationary();
        var active = prepared.active();
        if (!stationary.isEmpty()) backend.beginCompositeFrame();
        var drawBySource = new HashMap<ProjectedTileIndex.Source, OrderedDraw>(Math.max(16, meshes.size() * 2));
        for (int i = 0; i < meshes.size(); i++) {
            var draw = draws.get(i);
            if (draw != null) drawBySource.put(meshes.get(i).source(), new OrderedDraw(i, draw));
        }
        // Prioritize reusing disk pictures. Bake up to a small per-frame budget so viewport
        // updates and pans settle into cached pictures quickly without stalling frames.
        if (!prepared.uploadBudgetSpent()) {
            int baked = 0;
            long bakeDeadline = System.nanoTime() + BAKE_BUDGET_NANOS;
            for (var cell : stationary) {
                if (baked >= MAX_BAKES_PER_FRAME || (baked > 0 && System.nanoTime() >= bakeDeadline)) break;
                Entry entry = entries.get(cell);
                boolean staleDepth = entry.image != null && !fitsCurrentDepth(entry.image, grid, camera);
                if (entry.image == null && entry.missing || staleDepth) {
                    var base = staleDepth ? null : appendBase(entry, grid, camera);
                    var cellDraws = cellDraws(entry, base, drawBySource);
                    if (cellDraws == null) continue;
                    if (staleDepth) {
                        backend.closeImage(entry.image.image());
                        entry.image = null;
                    }
                    entry.x = camera.x(); entry.y = camera.y(); entry.z = camera.z();
                    entry.install(backend.bake(grid, cell, cellDraws, base == null ? null : base.draw(cell)),
                            index.depthRange(cell));
                    if (!staleDepth) entry.needsPersist = true;
                    gpuBakes++;
                    baked++;
                }
            }
        }
        for (Entry entry : entries.values()) {
            if (entry.needsPersist && entry.allocation == null && allocations < MAX_ALLOCATIONS) {
                entry.allocation = CompletableFuture.supplyAsync(
                        () -> backend.allocate(entry.x, entry.y, entry.z), io);
                allocations++;
            }
            submitReadback(entry);
        }
        trim(active);
        var shown = new HashMap<ProjectedGrid.Cell, Map<ProjectedTileIndex.Source, Integer>>(Math.max(16, stationary.size() * 2));
        var images = new ArrayList<ProjectedTerrainBackend.Draw<I>>(stationary.size());
        for (var cell : stationary) {
            Entry entry = entries.get(cell);
            var picture = entry.displayed();
            if (picture == null || !fitsCurrentDepth(picture, grid, camera)) continue;
            shown.put(cell, picture.contributors());
            images.add(picture.draw(cell));
        }
        if (images.isEmpty()) return ProjectedDrawCoverage.NONE;
        compositedCells += images.size();
        backend.composite(target, depth, grid, brightness, images);
        var clips = new IdentityHashMap<KuiNativeTerrainRenderer.Draw, List<ProjectedDrawCoverage.Clip>>();
        index.missingPictureCells(shown, drawBySource.keySet(), grid::clip).forEach((sourceKey, missing) ->
                clips.put(drawBySource.get(sourceKey).draw(), missing));
        return new ProjectedDrawCoverage(clips);
    }

    private Prepared prepare(RenderTarget target, MapCamera camera, List<ProjectedTileIndex.Mesh> meshes,
                             ChunkTileSource source, MapScope scope) {
        if (camera.mode().equals("street") || source == null || scope == null || meshes.isEmpty()) return null;
        Path nextDirectory = source.projectedCacheDirectory();
        String nextResources = source.projectedResourceKey();
        if (nextDirectory == null || nextResources == null) return null;
        // Leaf alpha is adjusted when decoding geometry, so the original mesh key stays
        // stable. Earlier pictures may still contain the old, sparser canopies.
        nextResources += OPAQUE_RASTER_REVISION + backend.cacheVariant();
        String nextScope = scopePart(scope.world()) + scopePart(scope.dimension()) + scopePart(scope.subworld()) + scope.layer();
        var grid = new ProjectedGrid(camera, target.width, target.height);
        if (!nextDirectory.equals(directory) || !nextScope.equals(scopeKey)
                || !nextResources.equals(resourceKey)) {
            clear();
            directory = nextDirectory; scopeKey = nextScope; resourceKey = nextResources; view = grid.view();
            store = new ProjectedTileStore(directory, io);
            entries = new LinkedHashMap<>(128, 0.75f, true);
            viewCaches.put(view, entries);
        } else if (!grid.view().equals(view)) {
            switchView(grid.view());
        }
        // An unsplit animated mesh still draws live with depth. It cannot invalidate the
        // static picture underneath it when it enters an already pictured cell.
        index.update(grid, meshes);
        var visible = grid.visible();
        var active = new HashSet<ProjectedGrid.Cell>();
        var stationary = new ArrayList<ProjectedGrid.Cell>();
        var liveOnly = new ArrayList<ProjectedGrid.Cell>();
        for (long y = visible.minY(); y <= visible.maxY(); y++) for (long x = visible.minX(); x <= visible.maxX(); x++) {
            var cell = new ProjectedGrid.Cell(x, y);
            if (!index.hasContributors(cell)) continue;
            active.add(cell);
            if (!index.containsDepth(cell, grid, camera.x(), camera.y(), camera.z())) {
                liveOnly.add(cell);
                continue;
            }
            stationary.add(cell);
        }
        double centerX = target.width * 0.5, centerY = target.height * 0.5;
        stationary.sort(Comparator.comparingDouble(cell -> {
            double dx = grid.cellScreenX(cell.x()) + ProjectedGrid.TILE_PIXELS * 0.5 - centerX;
            double dy = grid.cellScreenY(cell.y()) + ProjectedGrid.TILE_PIXELS * 0.5 - centerY;
            return dx * dx + dy * dy;
        }));
        int reads = (int) entries.values().stream().filter(entry -> entry.read != null || entry.upload != null).count();
        for (var cell : stationary) {
            long revision = index.revision(cell);
            Entry entry = entries.get(cell);
            Picture<I> retained = null;
            if (entry != null && entry.revision != revision) {
                if (entry.contributors.equals(index.contributorMasks(cell))) entry.revision = revision;
                else {
                    if (entry.read != null || entry.upload != null) reads--;
                    retained = entry.takeDisplayed();
                    discard(entry);
                    entry = null;
                }
            }
            if (entry == null) {
                String slot = index.slot(cell, scopeKey);
                var diskKey = index.diskKey(cell, scopeKey, resourceKey);
                var members = index.contributorMasks(cell);
                // Keep a previous picture ONLY when every retained contributor still exists with the
                // same content key and a subset of its current quadrants, and its depth stays valid.
                // Never retain removed or modified geometry whose stale depth would occlude new terrain.
                if (retained != null && (retained.depth() == null
                        || !isSubsetOf(retained.contributors(), members)
                        || !ProjectedGrid.containsDepth(retained.depth(),
                                grid.depthTranslation(retained.x(), retained.y(), retained.z())))) {
                    backend.closeImage(retained.image());
                    retained = null;
                }
                boolean appendable = retained != null
                        && members.entrySet().containsAll(retained.contributors().entrySet())
                        && canAppend(grid, retained.contributors().keySet(), members.keySet());
                entry = new Entry(slot, diskKey, revision, store.reserve(slot), members, retained, appendable);
                entries.put(cell, entry);
            }
            if (entry.image == null && entry.read == null && entry.upload == null && !entry.missing && reads < MAX_READS) {
                entry.read = store.read(entry.slot, entry.diskKey);
                reads++;
            }
        }
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            if (!active.contains(item.getKey()) && item.getValue().image == null && item.getValue().retained == null) {
                discard(item.getValue()); iterator.remove();
            }
        }
        int uploaded = 0;
        long uploadDeadline = 0;
        for (var cell : stationary) {
            Entry entry = entries.get(cell);
            if (entry.read != null && entry.read.isDone()) {
                var read = entry.read; entry.read = null;
                try {
                    var image = read.join();
                    if (image.isEmpty()) entry.missing = true;
                    else {
                        var data = image.get();
                        if (!index.containsDepth(entry.contributors.keySet(), grid, data.cameraX(), data.cameraY(), data.cameraZ())) {
                            // A bake made with a clipped contributor has no pixels to recover
                            // when a later pan puts that surface back inside the depth range.
                            data.close();
                            entry.missing = true;
                            continue;
                        }
                        entry.x = data.cameraX(); entry.y = data.cameraY(); entry.z = data.cameraZ();
                        try { entry.upload = CompletableFuture.supplyAsync(() -> backend.prepareUpload(data), io); }
                        catch (RuntimeException | Error failure) { data.close(); throw failure; }
                    }
                } catch (CompletionException failure) {
                    entry.missing = true;
                    LogUtils.getLogger().warn("Cannot read projected map tile", failure.getCause());
                }
            }
            if (entry.upload == null || !entry.upload.isDone() || uploaded >= MAX_UPLOADS_PER_FRAME
                    || uploadDeadline != 0 && System.nanoTime() >= uploadDeadline) continue;
            if (uploadDeadline == 0) uploadDeadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
            var upload = entry.upload; entry.upload = null;
            try {
                U data = upload.join();
                try { entry.install(backend.upload(data), index.depthRange(cell)); }
                finally { backend.closeUpload(data); }
                entry.ticket.close(); entry.transfer = true;
                diskUploads++;
                uploaded++;
            } catch (CompletionException failure) {
                entry.missing = true;
                LogUtils.getLogger().warn("Cannot prepare projected map tile", failure.getCause());
            }
        }
        return new Prepared(grid, stationary, liveOnly, active,
                uploadDeadline != 0 && System.nanoTime() >= uploadDeadline);
    }

    private void submitReadback(Entry entry) {
        if (entry.allocation == null || !entry.allocation.isDone() || entry.transfer
                || pendingStores.get() >= MAX_PENDING_STORES) return;
        A allocation;
        try { allocation = entry.allocation.join(); }
        catch (CompletionException failure) {
            entry.allocation = null; allocations--; entry.needsPersist = false; entry.ticket.close();
            LogUtils.getLogger().warn("Cannot allocate projected tile transfer", failure.getCause());
            return;
        }
        pendingStores.incrementAndGet();
        Optional<CompletableFuture<ProjectedTileStore.Image>> transfer;
        try { transfer = backend.readback(entry.image.image(), allocation, io); }
        catch (RuntimeException | Error failure) { pendingStores.decrementAndGet(); throw failure; }
        if (transfer.isEmpty()) { pendingStores.decrementAndGet(); return; }
        entry.allocation = null; allocations--; entry.needsPersist = false; entry.transfer = true;
        ProjectedTileStore owner = store;
        transfer.get().whenComplete((data, failure) -> {
            if (failure != null) {
                entry.ticket.close();
                pendingStores.decrementAndGet();
                if (entry.valid || !(failure instanceof CancellationException))
                    LogUtils.getLogger().warn("Cannot read projected map tile from the GPU", failure);
                return;
            }
            if (!entry.valid) { data.close(); entry.ticket.close(); pendingStores.decrementAndGet(); return; }
            try {
                owner.write(entry.ticket, entry.diskKey.fingerprint(), data).whenComplete((ignored, writeFailure) -> {
                    data.close();
                    pendingStores.decrementAndGet();
                    if (writeFailure != null) LogUtils.getLogger().warn("Cannot write projected map tile", writeFailure);
                });
            } catch (RuntimeException rejected) {
                data.close(); entry.ticket.close(); pendingStores.decrementAndGet();
                LogUtils.getLogger().warn("Cannot schedule projected map tile write", rejected);
            } catch (Error writeError) {
                data.close(); entry.ticket.close(); pendingStores.decrementAndGet(); throw writeError;
            }
        });
    }

    private List<KuiNativeTerrainRenderer.Draw> cellDraws(Entry entry, Picture<I> base,
            Map<ProjectedTileIndex.Source, OrderedDraw> draws) {
        var contributors = new ArrayList<OrderedDraw>();
        for (var source : entry.contributors.keySet()) {
            if (base != null && base.contributors().containsKey(source)) continue;
            var draw = draws.get(source);
            if (draw == null) return null;
            contributors.add(draw);
        }
        // Match the draw order of the world pass at equal depth.
        contributors.sort(Comparator.comparingInt(OrderedDraw::order));
        return contributors.stream().map(OrderedDraw::draw).toList();
    }

    private Picture<I> appendBase(Entry entry, ProjectedGrid grid, MapCamera camera) {
        return entry.canAppend && entry.retained != null && fitsCurrentDepth(entry.retained, grid, camera)
                ? entry.retained : null;
    }

    private boolean fitsCurrentDepth(Picture<I> picture, ProjectedGrid grid, MapCamera camera) {
        if (picture.depth() != null) {
            return ProjectedGrid.containsDepth(picture.depth(), grid.depthTranslation(picture.x(), picture.y(), picture.z()))
                    && ProjectedGrid.containsDepth(picture.depth(), grid.depthTranslation(camera.x(), camera.y(), camera.z()));
        }
        return index.containsDepth(picture.contributors().keySet(), grid, picture.x(), picture.y(), picture.z())
                && index.containsDepth(picture.contributors().keySet(), grid, camera.x(), camera.y(), camera.z());
    }

    private static boolean isSubsetOf(Map<ProjectedTileIndex.Source, Integer> previous,
                                      Map<ProjectedTileIndex.Source, Integer> current) {
        if (previous.size() > current.size()) return false;
        for (var entry : previous.entrySet()) {
            Integer nextMask = current.get(entry.getKey());
            if (nextMask == null || (entry.getValue() & ~nextMask) != 0) return false;
        }
        return true;
    }

    /** Preserve the equal-depth draw ordering of a full rasterization. */
    private static boolean canAppend(ProjectedGrid grid, Set<ProjectedTileIndex.Source> previous,
                                     Set<ProjectedTileIndex.Source> current) {
        double last = Double.NEGATIVE_INFINITY;
        for (var member : previous) last = Math.max(last, grid.drawOrder(member.tile()));
        for (var member : current)
            if (!previous.contains(member) && grid.drawOrder(member.tile()) <= last + 1e-5) return false;
        return true;
    }

    private void switchView(ProjectedGrid.View nextView) {
        if (view != null) {
            var it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Entry entry = it.next().getValue();
                if (entry.image == null) {
                    discard(entry);
                    it.remove();
                    continue;
                }
                entry.ticket.close();
                if (entry.read != null && !entry.read.cancel(false)) {
                    try { entry.read.join().ifPresent(ProjectedTileStore.Image::close); }
                    catch (CompletionException ignored) { }
                }
                entry.read = null;
                if (entry.upload != null) { entry.upload.thenAccept(backend::closeUpload); entry.upload = null; }
                if (entry.allocation != null) {
                    allocations--;
                    entry.allocation.thenAccept(backend::closeAllocation);
                    entry.allocation = null;
                }
                entry.needsPersist = false;
                releaseRetained(entry);
            }
            if (entries.isEmpty()) viewCaches.remove(view);
            else viewCaches.put(view, entries);
        }
        view = nextView;
        var cached = viewCaches.get(nextView);
        if (cached != null) {
            entries = cached;
            if (!cached.isEmpty()) viewCacheHits++;
        } else {
            entries = new LinkedHashMap<>(128, 0.75f, true);
            viewCaches.put(nextView, entries);
        }
        var viewIterator = viewCaches.entrySet().iterator();
        while (viewCaches.size() > MAX_CACHED_VIEWS && viewIterator.hasNext()) {
            var oldest = viewIterator.next();
            if (oldest.getValue() == entries) continue;
            oldest.getValue().values().forEach(this::discard);
            oldest.getValue().clear();
            viewIterator.remove();
        }
    }

    private void trim(Set<ProjectedGrid.Cell> active) {
        int total = 0;
        for (var bucket : viewCaches.values()) total += bucket.size();
        if (total <= MAX_IMAGES) return;
        // Evict parked zoom/angle views first so the active viewport keeps its full offscreen cache.
        var viewIterator = viewCaches.entrySet().iterator();
        while (total > MAX_IMAGES && viewIterator.hasNext()) {
            var bucketEntry = viewIterator.next();
            var bucket = bucketEntry.getValue();
            if (bucket == entries) continue;
            var entryIterator = bucket.entrySet().iterator();
            while (total > MAX_IMAGES && entryIterator.hasNext()) {
                discard(entryIterator.next().getValue());
                entryIterator.remove();
                total--;
            }
            if (bucket.isEmpty()) viewIterator.remove();
        }
        var iterator = entries.entrySet().iterator();
        while (total > MAX_IMAGES && iterator.hasNext()) {
            var entry = iterator.next();
            if (active.contains(entry.getKey())) continue;
            discard(entry.getValue()); iterator.remove();
            total--;
        }
    }

    private void discard(Entry entry) {
        entry.valid = false;
        entry.ticket.close();
        if (entry.read != null && !entry.read.cancel(false)) {
            try { entry.read.join().ifPresent(ProjectedTileStore.Image::close); }
            catch (CompletionException ignored) { /* The discarded read has no consumer. */ }
        }
        if (entry.allocation != null) {
            allocations--;
            entry.allocation.thenAccept(backend::closeAllocation);
            entry.allocation = null;
        }
        if (entry.upload != null) { entry.upload.thenAccept(backend::closeUpload); entry.upload = null; }
        if (entry.image != null) backend.closeImage(entry.image.image());
        releaseRetained(entry);
    }

    private void releaseRetained(Entry entry) {
        if (entry.retained == null) return;
        backend.closeImage(entry.retained.image());
        entry.retained = null;
    }

    private void clear() {
        for (var bucket : viewCaches.values()) {
            bucket.values().forEach(this::discard);
            bucket.clear();
        }
        viewCaches.clear();
        entries.values().forEach(this::discard);
        entries.clear();
    }

    public record Diagnostics(int cachedViews, int residentImages, long viewCacheHits,
                              long diskUploads, long gpuBakes, long depthRejections, long compositedCells) { }

    public Diagnostics diagnostics() {
        int images = 0;
        for (var bucket : viewCaches.values()) {
            for (var entry : bucket.values()) if (entry.image != null) images++;
        }
        return new Diagnostics(viewCaches.size(), images, viewCacheHits, diskUploads, gpuBakes,
                depthRejections, compositedCells);
    }

    public void reset() { pendingPreparation = null; clear(); index.reset(); view = null; }
    public void poll() { backend.poll(); }

    private static String scopePart(String value) { return value.length() + ":" + value; }

    @Override public void close() {
        reset();
        backend.close(); io.shutdown();
    }
}
