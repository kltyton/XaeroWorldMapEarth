package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.logging.LogUtils;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.AnimatedAtlas;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Maintains reusable HiRes tiles while the world is active, independently of opening a map. */
public final class NativeTileSession implements AutoCloseable, ChunkTileSource {
    public record Options(boolean prewarm, boolean persistentCache) {
        public static final Options DEFAULT = new Options(false, false);
        public static final Options WORLD_MAP = new Options(true, true);
    }
    private static final TileGrid GRID = new TileGrid(32, 0);
    private static final ConcurrentHashMap<BlockState, byte[]> STATE_KEYS = new ConcurrentHashMap<>();
    private static final long MEMORY_LIMIT = Math.min(512L << 20, Runtime.getRuntime().maxMemory() / 4);
    private static final long PENDING_WRITE_LIMIT = Math.max(16L << 20, MEMORY_LIMIT / 8);
    private static final int CHANGE_HISTORY_LIMIT = 8192;
    private static final class Entry {
        long sequence, nextAttempt;
        CompletableFuture<?> work;
        CompletableFuture<CacheLookup> preload;
        AtomicBoolean obsolete = new AtomicBoolean();
        boolean dirty = true;
        boolean preloadTried;
        int rank, misses, validatedMask;
        Pending queued;
    }
    private record Pending(ChunkTiles.Tile tile, Entry entry, long due, int rank) { }
    private static final class PendingWrite { int bytes; }
    private final ActiveMapResources resources;
    private final Path projectedCacheDirectory;

    @Override public AnimatedAtlas animatedAtlas() {
        return resources.animatedAtlas();
    }
    @Override public Path projectedCacheDirectory() { return projectedCacheDirectory; }
    @Override public String projectedResourceKey() {
        String resource = resources.fingerprint(), material = materialKey;
        return resource == null || material == null ? null : resource + ":" + material;
    }
    private volatile String materialKey;
    private record MaterialState(int id, BlockState state) { }
    private final ExecutorService conversion;
    private final ExecutorService cacheReads;
    private final ExecutorService cacheWrites;
    private final NativeChunkSource source;
    private final CompletableFuture<NativeTileCache> cache;
    private final Map<ChunkTiles.Tile, Entry> entries = new HashMap<>();
    private final TreeSet<Pending> pending = new TreeSet<>(Comparator.comparingLong(Pending::due).thenComparingInt(Pending::rank));
    private final ArrayDeque<ChunkTiles.Tile> preloads = new ArrayDeque<>();
    private record StoredTile(NativeTileCache cache, String key, int[] heights, byte[] model, int mask) { }
    private final ConcurrentHashMap<ChunkTiles.Tile, StoredTile> ready = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkTiles.Tile, List<StoredTile>> previousLayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkTiles.Tile, List<Fragment>> fragmentRefs = new ConcurrentHashMap<>();
    private record LayerIndex(ChunkTiles.Tile tile, int ceiling) { }
    private final Map<LayerIndex, CompletableFuture<Void>> layerWrites = new HashMap<>();
    private final ConcurrentHashMap<String, ProjectionMetadata> projectionMetadata = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkTiles.Tile, Long> versions = new ConcurrentHashMap<>();
    private final Set<ChunkTiles.Tile> versionedTiles = Collections.unmodifiableSet(versions.keySet());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong revision = new AtomicLong();
    private record TileChange(long revision, ChunkTiles.Tile tile) { }
    private final ArrayDeque<TileChange> changes = new ArrayDeque<>();
    private final int parallelism;
    private final Options options;
    private final int minY, maxY;
    private int ceiling = Integer.MAX_VALUE, inFlight, persisting, preloadsInFlight;
    private int biomeBlendRadius = -1;
    private long residentBytes, lastSchedule, cacheHits, bakedTiles;
    private long pendingWriteBytes;
    private double focusX, focusZ;
    private record TileBounds(int minX, int maxX, int minZ, int maxZ) { }
    private TileBounds scheduledBounds;
    private java.util.Set<ChunkTiles.Tile> scheduledTiles = java.util.Set.of();

    public NativeTileSession(ClientLevel level, ActiveMapResources resources, Path cacheRoot,
                                    String worldKey, int parallelism) {
        this(level, resources, cacheRoot, worldKey, parallelism, Options.DEFAULT);
    }

    public NativeTileSession(ClientLevel level, ActiveMapResources resources, Path cacheRoot,
                                    String worldKey, int parallelism, Options options) {
        this.resources = resources; this.parallelism = parallelism;
        this.options = options;
        projectedCacheDirectory = options.persistentCache() ? cacheRoot.resolve("native-models")
                .resolve(hash(worldKey.getBytes(StandardCharsets.UTF_8))) : null;
        conversion = Executors.newFixedThreadPool(parallelism, task -> {
            Thread thread = new Thread(task, "KUI native tile data");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
        cacheReads = options.persistentCache() ? Executors.newFixedThreadPool(Math.max(4, Math.min(8, parallelism)), task -> {
            Thread thread = new Thread(task, "Earth cached tile reader");
            thread.setDaemon(true);
            return thread;
        }) : null;
        cacheWrites = options.persistentCache() ? Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Earth cached tile writer");
            thread.setDaemon(true);
            return thread;
        }) : null;
        source = new NativeChunkSource(level, conversion);
        minY = source.minY(); maxY = source.maxY();
        var materialStates = new ArrayList<MaterialState>();
        if (options.persistentCache()) {
            if (!Minecraft.getInstance().isSameThread())
                throw new IllegalStateException("Capture map material IDs on the client thread");
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) materialStates.add(new MaterialState(Block.getId(state), state));
        }
        CompletableFuture<String> materials;
        if (options.persistentCache()) {
            materials = CompletableFuture.supplyAsync(() -> {
                String key = materialFingerprint(materialStates);
                materialKey = key;
                return key;
            }, conversion);
        } else {
            materialKey = "";
            materials = CompletableFuture.completedFuture("");
        }
        cache = resources.renderer().thenCombineAsync(materials, (renderer, material) -> {
            if (!options.persistentCache()) return null;
            var storage = new NativeTileCache(projectedCacheDirectory.resolve(
                    resources.fingerprint() + "-" + material + NativeChunkSource.CACHE_SUFFIX));
            storage.openLayerSession();
            return storage;
        }, conversion);
    }

    /** Main-thread scheduling only; snapshot conversion, disk cache and meshing stay asynchronous. */
    public void tick(double centerX, double centerZ, double radius, int requestedCeiling) {
        tick(centerX, centerZ, radius, requestedCeiling, true);
    }

    public void tick(double centerX, double centerZ, double radius, int requestedCeiling, boolean visible) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Tile scheduling must use the client thread");
        if (closed.get() || resources.isClosed()) return;
        if (!visible && !options.prewarm()) return;
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(radius)) {
            source.pumpLiveCopies(focusX, focusZ);
            return;
        }
        focusX = centerX; focusZ = centerZ;
        int currentBlendRadius = BiomeBlendOptions.radius();
        if (biomeBlendRadius != currentBlendRadius) {
            biomeBlendRadius = currentBlendRadius;
            entries.forEach((tile, entry) -> {
                entry.dirty = true; entry.validatedMask = 0; entry.nextAttempt = 0; entry.misses = 0;
                if (scheduledTiles.contains(tile)) schedule(tile, entry);
            });
        }
        if (ceiling != requestedCeiling) {
            ceiling = requestedCeiling;
            source.discardCeiling();
            entries.values().forEach(entry -> entry.obsolete.set(true));
            entries.clear(); pending.clear(); preloads.clear(); scheduledBounds = null;
            scheduledTiles = java.util.Set.of();
            ready.clear(); previousLayers.clear(); fragmentRefs.clear(); versions.clear(); residentBytes = 0; recordChange(null);
        }
        if (radius <= 0) {
            if (scheduledBounds == null && scheduledTiles.isEmpty()) return;
            for (Pending previous : pending) previous.entry().queued = null;
            pending.clear();
            preloads.clear();
            scheduledBounds = null;
            scheduledTiles = java.util.Set.of();
            cancelOutsideFocus();
            source.discardLiveCopies();
            return;
        }
        long now = System.nanoTime();
        int jobLimit = visible ? Math.max(8, parallelism * 3) : 1;
        int minX = GRID.getCellX((int) Math.floor(centerX - radius)), maxX = GRID.getCellX((int) Math.ceil(centerX + radius));
        int minZ = GRID.getCellY((int) Math.floor(centerZ - radius)), maxZ = GRID.getCellY((int) Math.ceil(centerZ + radius));
        var bounds = new TileBounds(minX, maxX, minZ, maxZ);
        if (!bounds.equals(scheduledBounds)) {
            var next = new ArrayList<ChunkTiles.Tile>();
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                if (TerrainDetail.intersects(centerX, centerZ, radius + 32, GRID.getCellMinX(x), GRID.getCellMinY(z), 32))
                    next.add(new ChunkTiles.Tile(x, z));
            }
            next.sort(Comparator.comparingDouble(tile -> Math.hypot(GRID.getCellMinX(tile.x()) + 16 - centerX,
                    GRID.getCellMinY(tile.z()) + 16 - centerZ)));
            for (Pending previous : pending) previous.entry().queued = null;
            pending.clear(); preloads.clear(); scheduledBounds = bounds;
            scheduledTiles = java.util.Set.copyOf(next);
            cancelOutsideFocus();
            source.discardLiveCopiesOutside(centerX, centerZ, radius);
            int rank = 0;
            for (var tile : next) {
                Entry entry = entries.computeIfAbsent(tile, ignored -> new Entry());
                entry.rank = rank++;
                schedule(tile, entry);
                if (cacheReads != null && !entry.preloadTried && entry.preload == null && !ready.containsKey(tile))
                    preloads.addLast(tile);
            }
        }
        lastSchedule = now;
        pumpPreloads();
        int started = 0;
        long scheduleDeadline = System.nanoTime() + 1_500_000L;
        // Maintain CPU work while completed packets wait on the disk writer. The total
        // number of jobs and the observed bytes waiting on disk still bound the queue.
        int totalJobLimit = visible ? jobLimit * 2 : jobLimit;
        while (started < jobLimit && inFlight - persisting < jobLimit && inFlight < totalJobLimit
                && pendingWriteBytes < PENDING_WRITE_LIMIT && !pending.isEmpty()
                && (started == 0 || System.nanoTime() < scheduleDeadline)) {
            Pending task = pending.first();
            if (task.due() > now) break;
            pending.pollFirst(); task.entry().queued = null;
            if (task.entry().work != null || ready.containsKey(task.tile()) && !task.entry().dirty) continue;
            started++;
            start(task.tile(), task.entry());
        }
        source.pumpLiveCopies(centerX, centerZ);
    }

    private void schedule(ChunkTiles.Tile tile, Entry entry) {
        if (entry.queued != null) { pending.remove(entry.queued); entry.queued = null; }
        if (closed.get() || resources.isClosed() || !scheduledTiles.contains(tile)
                || entry.work != null || !entry.dirty && ready.containsKey(tile)) return;
        entry.queued = new Pending(tile, entry, entry.nextAttempt, entry.rank);
        pending.add(entry.queued);
    }

    public boolean usesCeiling(int requestedCeiling) { return ceiling == requestedCeiling; }

    private void pumpPreloads() {
        if (cacheReads == null || closed.get() || resources.isClosed()) return;
        int preloadLimit = Math.max(48, parallelism * 8);
        while (preloadsInFlight < preloadLimit && !preloads.isEmpty()) {
            var tile = preloads.removeFirst();
            Entry entry = entries.get(tile);
            if (entry == null || entry.preloadTried || entry.preload != null || entry.work != null || ready.containsKey(tile)) continue;
            preload(tile, entry, ceiling);
        }
    }

    private void preload(ChunkTiles.Tile tile, Entry entry, int cut) {
        entry.preloadTried = true;
        preloadsInFlight++;
        CompletableFuture<CacheLookup> lookup = cache.thenApplyAsync(disk -> {
            return closed.get() ? new CacheLookup(disk, List.of()) : readCachedLayers(disk, tile, cut);
        }, cacheReads);
        entry.preload = lookup;
        lookup.whenComplete((cached, failure) -> Minecraft.getInstance().execute(() -> {
            preloadsInFlight--;
            if (entry.preload == lookup) entry.preload = null;
            if (closed.get() || resources.isClosed() || ceiling != cut) return;
            if (entries.get(tile) == entry) {
                if (!scheduledTiles.contains(tile)) {
                    entry.preloadTried = false;
                } else if (failure != null) {
                    entry.preloadTried = false;
                    LogUtils.getLogger().debug("Cached model tile {},{} unavailable: {}", tile.x(), tile.z(), failure.toString());
                } else if (cached.latest() != null && !ready.containsKey(tile)) {
                    putCached(tile, cached.layers());
                }
            }
            pumpPreloads();
        }));
    }

    private void cancelOutsideFocus() {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            Entry entry = item.getValue();
            if (scheduledTiles.contains(item.getKey())) continue;
            if (entry.queued != null) { pending.remove(entry.queued); entry.queued = null; }
            if (entry.work != null) {
                entry.obsolete.set(true);
                entry.work = null;
            }
            // Client packets and integrated-server edits invalidate retained entries even
            // offscreen. Keep each clean validation as long as its published model is retained.
            if (!entry.dirty && entry.validatedMask == 15 && ready.containsKey(item.getKey())) continue;
            // Keep the in-flight budget until the abandoned work actually yields.
            entry.validatedMask = 0;
            iterator.remove();
        }
    }

    private void start(ChunkTiles.Tile tile, Entry entry) {
        AtomicBoolean obsolete = new AtomicBoolean(); entry.obsolete = obsolete;
        entry.dirty = false;
        long sequence = ++entry.sequence; inFlight++;
        int cut = ceiling;
        var cancelled = (java.util.function.BooleanSupplier) () -> closed.get() || resources.isClosed() || obsolete.get();
        CompletableFuture<CacheLookup> lookup;
        if (entry.preload != null) {
            lookup = entry.preload;
        } else if (options.persistentCache() && !entry.preloadTried) {
            entry.preloadTried = true;
            lookup = cache.thenApplyAsync(disk -> {
                return cancelled.getAsBoolean() ? new CacheLookup(disk, List.of()) : readCachedLayers(disk, tile, cut);
            }, cacheReads);
            lookup.thenAccept(cached -> Minecraft.getInstance().execute(() -> {
                if (cached.latest() != null && !cancelled.getAsBoolean() && entry.sequence == sequence
                        && entries.get(tile) == entry && !ready.containsKey(tile)) putCached(tile, cached.layers());
            }));
        } else {
            lookup = cache.thenApply(disk -> new CacheLookup(disk, List.of()));
        }
        var write = new PendingWrite();
        var publish = (java.util.function.Consumer<TileResult>) preview -> Minecraft.getInstance().execute(() -> {
            if (closed.get()) return;
            var draft = preview.tile();
            write.bytes = Math.max(1, draft.model().length);
            pendingWriteBytes += write.bytes;
            persisting++;
            if (cancelled.getAsBoolean() || entry.sequence != sequence || entries.get(tile) != entry) return;
            putCached(tile, preview.cached());
            if (!entry.dirty || !ready.containsKey(tile)) putReady(tile, draft);
            if (!entry.dirty) entry.validatedMask = draft.mask();
        });
        CompletableFuture<TileResult> work = source.capture(tile, cut, cancelled)
                .thenCombineAsync(lookup, (input, cached) -> matchCache(tile, input, cached, cancelled),
                        cacheReads == null ? conversion : cacheReads)
                .thenCompose(matched -> {
                    if (matched == null || cancelled.getAsBoolean()) return CompletableFuture.completedFuture(null);
                    if (matched.hit() != null) {
                        synchronized (this) { cacheHits++; }
                        return CompletableFuture.completedFuture(new TileResult(matched.hit(), false, matched.cached().layers()));
                    }
                    return buildTile(tile, matched, cancelled, publish);
                });
        entry.work = work;
        work.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            inFlight--;
            if (write.bytes != 0) {
                pendingWriteBytes -= write.bytes;
                persisting--;
            }
            if (closed.get() || entry.sequence != sequence || obsolete.get() || entries.get(tile) != entry) return;
            entry.work = null;
            boolean changedDuringWork = entry.dirty;
            boolean retry = failure != null || result == null || result.incomplete() || result.tile().mask() != 15;
            if (failure != null) {
                if (!(failure instanceof java.util.concurrent.CancellationException))
                    LogUtils.getLogger().debug("Native model tile {},{} unavailable: {}", tile.x(), tile.z(), failure.toString());
            } else if (result != null) {
                putCached(tile, result.cached());
                // A completed job may describe the world before a block update. Keep the
                // last published geometry until its successor has been captured and baked.
                var visible = ready.get(tile);
                if (!changedDuringWork || visible == null || visible.key().equals(result.tile().key()))
                    putReady(tile, result.tile());
                if (!changedDuringWork) entry.validatedMask = result.tile().mask();
            }
            entry.dirty = changedDuringWork || retry;
            if (retry && !changedDuringWork) {
                entry.misses = Math.min(6, entry.misses + 1);
                entry.nextAttempt = System.nanoTime() + Math.min(30L, 1L << (entry.misses - 1)) * 1_000_000_000L;
            } else {
                entry.misses = 0;
                entry.nextAttempt = 0;
            }
            schedule(tile, entry);
        }));
    }

    private CacheMatch matchCache(ChunkTiles.Tile tile, NativeChunkSource.CaptureInput input, CacheLookup cached,
                                  java.util.function.BooleanSupplier cancelled) {
        if (input == null || cancelled.getAsBoolean()) return null;
        String key = input.key();
        var reusable = cached.latest();
        if (reusable == null || !key.equals(reusable.key())) reusable = ready.get(tile);
        if (reusable != null && key.equals(reusable.key())) return new CacheMatch(input, cached, reusable);
        var disk = cached.disk();
        var hit = disk == null ? null : disk.readMetadata(key, tile).orElse(null);
        if (hit != null) loadProjectionMetadata(disk, tile, key);
        return new CacheMatch(input, cached, hit == null ? null : store(disk, new NativeTileCache.Hit(key, hit)));
    }

    private CompletableFuture<TileResult> buildTile(ChunkTiles.Tile tile, CacheMatch matched,
            java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<TileResult> publish) {
        var cached = matched.cached();
        var disk = cached.disk();
        String key = matched.input().key();
        return CompletableFuture.supplyAsync(() -> {
            if (cancelled.getAsBoolean()) return null;
            var capture = matched.input().materialize().get();
            if (capture == null || cancelled.getAsBoolean()) return null;
            String legacyKey = disk != null && (cached.latest() != null || ready.containsKey(tile))
                    ? fingerprint(capture, tile) : null;
            return new Prepared(capture, legacyKey);
        }, conversion).thenCompose(prepared -> {
            if (prepared == null || cancelled.getAsBoolean()) return CompletableFuture.completedFuture(null);
            // Reuse an older snapshot-keyed packet when the current snapshot matches it.
            CompletableFuture<ChunkTiles.NativeTile> legacy = prepared.legacyKey() == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.supplyAsync(() -> {
                        if (cancelled.getAsBoolean()) return null;
                        String oldKey = prepared.legacyKey();
                        var model = disk.read(oldKey, tile).orElse(null);
                        if (model != null) disk.readProjectionMetadata(oldKey, tile).ifPresent(metadata -> {
                            if (!cancelled.getAsBoolean()) projectionMetadata.putIfAbsent(key, metadata);
                        });
                        return model;
                    }, cacheReads);
            return legacy.thenCompose(existing -> {
                if (cancelled.getAsBoolean()) return CompletableFuture.completedFuture(null);
                if (existing != null) {
                    synchronized (this) { cacheHits++; }
                    return publishAndPersist(existing, key, cached, cancelled, publish);
                }
                var capture = prepared.capture();
                return resources.renderer().thenCompose(renderer -> renderer.renderDetailedTile(
                                capture.snapshot(), tile, capture.knownColumns(), capture.tint(), cancelled))
                        .thenCompose(rendered -> {
                            synchronized (this) { bakedTiles++; }
                            return publishAndPersist(rendered, key, cached, cancelled, publish);
                        });
            });
        });
    }

    private CompletableFuture<TileResult> publishAndPersist(ChunkTiles.NativeTile model, String key, CacheLookup cached,
            java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<TileResult> publish) {
        if (cancelled.getAsBoolean()) return CompletableFuture.completedFuture(null);
        var draft = store(null, new NativeTileCache.Hit(key, model));
        var preview = new TileResult(draft, false, cached.layers());
        if (cached.disk() == null) return CompletableFuture.completedFuture(preview);
        // A complete packet can be uploaded now. Disk writes keep a slot in the
        // total job limit and give the CPU slot back once the packet is published.
        publish.accept(preview);
        return CompletableFuture.supplyAsync(() -> {
            if (cancelled.getAsBoolean()) return null;
            var disk = cached.disk();
            var stored = disk.writeUnpublished(key, model)
                    ? new StoredTile(disk, key, draft.heights(), draft.model(), draft.mask()) : draft;
            return new TileResult(stored, false, cached.layers());
        }, cacheWrites);
    }

    private void putReady(ChunkTiles.Tile tile, StoredTile result) {
            var existing = ready.get(tile);
            if (result.mask() == 0) return;
            if (existing != null && existing.key().equals(result.key())) {
                if (existing.cache() == null && result.cache() != null) {
                    ready.put(tile, result);
                    residentBytes += result.model().length - existing.model().length;
                    var metadata = projectionMetadata.get(result.key());
                    if (metadata != null) persistProjectionMetadata(tile, result.key(), result.cache(), metadata);
                    rememberLayers(tile, result, previousLayers.getOrDefault(tile, List.of()), ceiling);
                }
                return;
            }
            var oldLayers = new ArrayList<StoredTile>();
            if (existing != null) oldLayers.add(existing);
            oldLayers.addAll(previousLayers.getOrDefault(tile, List.of()));
            int retained = result.mask();
            var nextLayers = new ArrayList<StoredTile>();
            for (var old : oldLayers) {
                int mask = old.mask() & ~retained;
                if (mask == 0) continue;
                retained |= mask;
                nextLayers.add(new StoredTile(old.cache(), old.key(), old.heights(),
                        old.cache() == null ? old.model() : new byte[0], mask));
            }
            var previous = ready.put(tile, result);
            if (previous != null) residentBytes -= previous.model().length + 4096L;
            for (var old : previousLayers.getOrDefault(tile, List.of())) residentBytes -= old.model().length + 4096L;
            setPreviousLayers(tile, nextLayers);
            for (var layer : nextLayers) residentBytes += layer.model().length + 4096L;
            residentBytes += result.model().length + 4096L;
            long updated = recordChange(tile);
            if (result.mask() != 0 && (result.model().length > 0 || result.cache() != null))
                versions.put(tile, updated);
            else versions.remove(tile);
            if (residentBytes > MEMORY_LIMIT) evictResidentTiles();
            if (result.cache() != null) rememberLayers(tile, result, nextLayers, ceiling);
    }

    private double focusDistanceSquared(ChunkTiles.Tile tile) {
        double dx = GRID.getCellMinX(tile.x()) + 16 - focusX;
        double dz = GRID.getCellMinY(tile.z()) + 16 - focusZ;
        return dx * dx + dz * dz;
    }

    private void evictResidentTiles() {
        var withModel = new ArrayList<Map.Entry<ChunkTiles.Tile, StoredTile>>();
        for (var item : ready.entrySet()) {
            if (item.getValue().model().length > 0) withModel.add(item);
        }
        if (!withModel.isEmpty()) {
            withModel.sort((a, b) -> Double.compare(focusDistanceSquared(b.getKey()), focusDistanceSquared(a.getKey())));
            for (var farthest : withModel) {
                if (residentBytes <= MEMORY_LIMIT) return;
                var tileKey = farthest.getKey();
                var stored = ready.get(tileKey);
                if (stored == null || stored.model().length == 0) continue;
                if (stored.cache() != null) {
                    ready.put(tileKey, new StoredTile(stored.cache(), stored.key(), stored.heights(), new byte[0], stored.mask()));
                    residentBytes -= stored.model().length;
                } else {
                    ready.remove(tileKey); versions.remove(tileKey);
                    projectionMetadata.remove(stored.key());
                    residentBytes -= stored.model().length + 4096L; recordChange(tileKey);
                    for (var layer : previousLayers.getOrDefault(tileKey, List.of())) {
                        projectionMetadata.remove(layer.key());
                        residentBytes -= layer.model().length + 4096L;
                    }
                    setPreviousLayers(tileKey, List.of());
                    Entry evicted = entries.get(tileKey);
                    if (evicted != null) schedule(tileKey, evicted);
                }
            }
        }
        if (residentBytes <= MEMORY_LIMIT) return;
        // Metadata-only models still hold height arrays. Evict only inactive tiles whose
        // complete layer set is safely on disk and has no index write in progress.
        var metadataOnly = new ArrayList<Map.Entry<ChunkTiles.Tile, StoredTile>>();
        for (var item : ready.entrySet()) {
            var tileKey = item.getKey();
            var stored = item.getValue();
            if (scheduledTiles.contains(tileKey) || stored.cache() == null
                    || layerWrites.containsKey(new LayerIndex(tileKey, ceiling))) continue;
            boolean allCached = true;
            for (var layer : previousLayers.getOrDefault(tileKey, List.of())) {
                if (layer.cache() == null) { allCached = false; break; }
            }
            if (allCached) metadataOnly.add(item);
        }
        metadataOnly.sort((a, b) -> Double.compare(focusDistanceSquared(b.getKey()), focusDistanceSquared(a.getKey())));
        for (var farthest : metadataOnly) {
            if (residentBytes <= MEMORY_LIMIT) return;
            var tileKey = farthest.getKey();
            var stored = ready.remove(tileKey);
            if (stored == null) continue;
            versions.remove(tileKey);
            entries.remove(tileKey);
            projectionMetadata.remove(stored.key());
            residentBytes -= stored.model().length + 4096L;
            for (var layer : previousLayers.getOrDefault(tileKey, List.of())) {
                projectionMetadata.remove(layer.key());
                residentBytes -= layer.model().length + 4096L;
            }
            setPreviousLayers(tileKey, List.of());
            recordChange(tileKey);
        }
    }

    private void rememberLayers(ChunkTiles.Tile tile, StoredTile newest, List<StoredTile> older, int cut) {
        var layers = new ArrayList<NativeTileCache.LayerHit>();
        layers.add(cacheLayer(tile, newest));
        for (var layer : older) layers.add(cacheLayer(tile, layer));
        var index = new LayerIndex(tile, cut);
        var prior = layerWrites.getOrDefault(index, CompletableFuture.completedFuture(null));
        var ticket = newest.cache().reserveLayers(tile, cut);
        var work = prior.handle((ignored, failure) -> null)
                .thenRunAsync(() -> {
                    try {
                        // A retained layer can still be memory-only after an earlier disk failure.
                        // Publish its index only after the referenced model has been persisted.
                        for (int i = 0; i < older.size(); i++) {
                            StoredTile layer = older.get(i);
                            if (layer.cache() == null
                                    && !newest.cache().writeUnpublished(layer.key(), layers.get(i + 1).hit().tile())) return;
                        }
                        newest.cache().rememberLayers(ticket, tile, cut, layers);
                    }
                    finally { ticket.close(); }
                }, cacheWrites);
        work.whenComplete((ignored, failure) -> ticket.close());
        layerWrites.put(index, work);
        work.whenComplete((ignored, failure) -> Minecraft.getInstance().execute(() -> {
            if (failure != null) LogUtils.getLogger().warn("Cannot publish native tile layers for {}", tile, failure);
            layerWrites.remove(index, work);
        }));
    }

    private static NativeTileCache.LayerHit cacheLayer(ChunkTiles.Tile tile, StoredTile layer) {
        return new NativeTileCache.LayerHit(new NativeTileCache.Hit(layer.key(),
                new ChunkTiles.NativeTile(tile, layer.model(), layer.heights())), layer.mask());
    }

    private record CacheLookup(NativeTileCache disk, List<StoredTile> layers) {
        StoredTile latest() { return layers.isEmpty() ? null : layers.get(0); }
    }
    private record TileResult(StoredTile tile, boolean incomplete, List<StoredTile> cached) { }
    private record CacheMatch(NativeChunkSource.CaptureInput input, CacheLookup cached, StoredTile hit) { }
    private record Prepared(NativeChunkSource.Capture capture, String legacyKey) { }

    private static StoredTile store(NativeTileCache cache, NativeTileCache.Hit hit) {
        var tile = hit.tile();
        return new StoredTile(cache, hit.key(), tile.heights(), tile.model(), availableMask(tile.heights()));
    }

    private CacheLookup readCachedLayers(NativeTileCache disk, ChunkTiles.Tile tile, int cut) {
        var layers = new ArrayList<StoredTile>();
        for (var layer : disk.readLatestLayersMetadata(tile, cut)) {
            var value = store(disk, layer.hit());
            layers.add(new StoredTile(disk, value.key(), value.heights(), value.model(), layer.mask()));
            loadProjectionMetadata(disk, tile, value.key());
        }
        return new CacheLookup(disk, List.copyOf(layers));
    }

    private void putCached(ChunkTiles.Tile tile, List<StoredTile> layers) {
        if (layers.isEmpty() || ready.containsKey(tile)) return;
        ready.put(tile, layers.get(0));
        if (layers.size() > 1) setPreviousLayers(tile, layers.subList(1, layers.size()));
        residentBytes += layers.size() * 4096L;
        versions.put(tile, recordChange(tile));
    }

    /** Called by the HTTP worker; released mesh bytes remain available from their validated disk cache. */
    public ChunkTiles.NativeTile tile(ChunkTiles.Tile tile) {
        var stored = ready.get(tile);
        if (stored == null) return null;
        if (stored.model().length > 0)
            return new ChunkTiles.NativeTile(tile, stored.model().clone(), stored.heights());
        if (stored.cache() == null) return new ChunkTiles.NativeTile(tile, new byte[0], stored.heights());
        var cached = stored.cache().read(stored.key(), tile);
        if (cached.isPresent()) return cached.get();
        invalidateMissing(tile, stored);
        return null;
    }

    @Override public Geometry geometry(ChunkTiles.Tile tile, String expectedKey) throws java.io.IOException {
        if (closed.get()) return null;
        var stored = storedLayer(tile, expectedKey);
        if (stored == null) return null;
        if (stored.model().length > 0)
            return new Geometry(io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry.decode(stored.model()),
                    availableMask(stored.heights()));
        if (stored.cache() == null) return null;
        var geometry = stored.cache().readGeometry(stored.key(), tile);
        if (geometry.isPresent()) return new Geometry(geometry.get(), availableMask(stored.heights()));
        invalidateMissing(tile, stored);
        return null;
    }

    private void loadProjectionMetadata(NativeTileCache disk, ChunkTiles.Tile tile, String key) {
        disk.readProjectionMetadata(key, tile).ifPresent(metadata -> {
            if (!closed.get()) projectionMetadata.put(key, metadata);
        });
    }

    @Override public ProjectionMetadata projectionMetadata(ChunkTiles.Tile tile, String key) {
        return projectionMetadata.get(key);
    }

    @Override public int projectionMask(ChunkTiles.Tile tile, String key) {
        var stored = storedLayer(tile, key);
        return stored == null ? 0 : stored.mask();
    }

    @Override public void rememberProjectionMetadata(String key, ChunkTiles.Tile tile, ProjectionMetadata metadata) {
        var stored = storedLayer(tile, key);
        if (closed.get() || stored == null) return;
        var previous = projectionMetadata.put(key, metadata);
        if (stored.cache() != null && !metadata.equals(previous))
            persistProjectionMetadata(tile, key, stored.cache(), metadata);
    }

    private void persistProjectionMetadata(ChunkTiles.Tile tile, String key, NativeTileCache disk, ProjectionMetadata metadata) {
        try {
            cacheWrites.execute(() -> disk.writeProjectionMetadata(key, tile, metadata));
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (!closed.get()) LogUtils.getLogger().warn("Cannot schedule projection metadata for {}", tile, stopped);
        }
    }

    private void invalidateMissing(ChunkTiles.Tile tile, StoredTile stored) {
        Minecraft.getInstance().execute(() -> {
            if (closed.get()) return;
            if (ready.get(tile) == stored) {
                var remaining = previousLayers.getOrDefault(tile, List.of());
                if (remaining.isEmpty()) {
                    ready.remove(tile);
                    versions.remove(tile);
                } else {
                    // A missing packet invalidates only its own quadrants. Older independent
                    // fragments can still cover the unaffected parts of this tile.
                    ready.put(tile, remaining.get(0));
                    remaining = remaining.subList(1, remaining.size());
                }
                setPreviousLayers(tile, remaining);
                residentBytes -= stored.model().length + 4096L;
            } else {
                var old = previousLayers.getOrDefault(tile, List.of());
                if (!old.contains(stored)) return;
                var remaining = new ArrayList<>(old);
                remaining.remove(stored);
                setPreviousLayers(tile, remaining);
                residentBytes -= stored.model().length + 4096L;
            }
            projectionMetadata.remove(stored.key());
            long updated = recordChange(tile);
            if (ready.containsKey(tile)) versions.put(tile, updated);
            Entry entry = entries.get(tile);
            if (entry != null) {
                entry.dirty = true;
                entry.validatedMask = 0;
                entry.nextAttempt = 0;
                schedule(tile, entry);
            }
        });
    }

    public String contentKey(ChunkTiles.Tile tile) { var stored = ready.get(tile); return stored == null ? null : stored.key(); }
    @Override public int validatedMask(ChunkTiles.Tile tile) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Tile validation state is client-thread only");
        Entry entry = entries.get(tile);
        return entry == null || !ready.containsKey(tile) ? 0 : entry.validatedMask;
    }
    public int availableMask(ChunkTiles.Tile tile) {
        var stored = ready.get(tile);
        int mask = stored == null ? 0 : stored.mask();
        for (var layer : previousLayers.getOrDefault(tile, List.of())) mask |= layer.mask();
        return mask;
    }

    @Override public List<Fragment> fragments(ChunkTiles.Tile tile) {
        return fragmentRefs.getOrDefault(tile, List.of());
    }

    private void setPreviousLayers(ChunkTiles.Tile tile, List<StoredTile> layers) {
        if (layers.isEmpty()) {
            previousLayers.remove(tile);
            fragmentRefs.remove(tile);
            return;
        }
        previousLayers.put(tile, List.copyOf(layers));
        fragmentRefs.put(tile, layers.stream().map(layer -> new Fragment(layer.key(), layer.mask())).toList());
    }

    @Override public boolean contains(ChunkTiles.Tile tile, String key) { return storedLayer(tile, key) != null; }

    private StoredTile storedLayer(ChunkTiles.Tile tile, String key) {
        var newest = ready.get(tile);
        if (newest != null && newest.key().equals(key)) return newest;
        for (var layer : previousLayers.getOrDefault(tile, List.of()))
            if (layer.key().equals(key)) return layer;
        return null;
    }

    public byte[] compressedModel(ChunkTiles.Tile tile, String expectedKey) {
        var stored = ready.get(tile);
        if (stored == null || !stored.key().equals(expectedKey)) return null;
        if (stored.model().length > 0) return encode(stored.model());
        return stored.cache() == null ? null : stored.cache().read(stored.key(), tile).map(model -> encode(model.model())).orElse(null);
    }

    static int availableMask(int[] heights) {
        int mask = 0;
        for (int q = 0; q < 4; q++) {
            boolean known = true;
            int x0 = (q & 1) * 16, z0 = (q >> 1) * 16;
            for (int z = z0; z < z0 + 16 && known; z++) for (int x = x0; x < x0 + 16; x++)
                if (heights[z * 32 + x] == 32767) { known = false; break; }
            if (known) mask |= 1 << q;
        }
        return mask;
    }

    private static byte[] encode(byte[] raw) {
        if (raw.length == 0) return raw;
        try (var output = new java.io.ByteArrayOutputStream(); var gzip = new java.util.zip.GZIPOutputStream(output) {
            { def.setLevel(java.util.zip.Deflater.BEST_SPEED); }
        }) {
            gzip.write(raw); gzip.finish(); return output.toByteArray();
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    public Map<ChunkTiles.Tile, Long> versions() { return Map.copyOf(versions); }

    private long recordChange(ChunkTiles.Tile tile) {
        long updated = revision.incrementAndGet();
        changes.addLast(new TileChange(updated, tile));
        if (changes.size() > CHANGE_HISTORY_LIMIT) changes.removeFirst();
        return updated;
    }

    /** Returns null when a reset or an expired history requires a full traversal. */
    public List<ChunkTiles.Tile> changedSince(long previous) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Tile traversal must use the client thread");
        long current = revision.get();
        if (previous == current) return List.of();
        if (previous < 0 || previous > current || changes.isEmpty() || previous < changes.getFirst().revision() - 1) return null;
        var updated = new HashSet<ChunkTiles.Tile>();
        var iterator = changes.descendingIterator();
        while (iterator.hasNext()) {
            var change = iterator.next();
            if (change.revision() <= previous) break;
            if (change.tile() == null) return null;
            updated.add(change.tile());
        }
        return List.copyOf(updated);
    }
    /** Live, read-only tile keys for client-thread scene traversal. */
    public Set<ChunkTiles.Tile> versionedTiles() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Tile traversal must use the client thread");
        return versionedTiles;
    }
    public Options options() { return options; }
    @Override public boolean persistentCache() { return options.persistentCache(); }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    @Override public Holder<Biome> cachedBiome(BlockPos position) {
        return closed.get() ? null : source.cachedBiome(position);
    }
    public long revision() { return revision.get(); }
    public synchronized String stats() { return "ready=" + ready.size() + " jobs=" + inFlight + " cache=" + cacheHits + " baked=" + bakedTiles; }

    public int heightAt(double x, double z, int unknown) {
        int worldX = (int) Math.floor(x), worldZ = (int) Math.floor(z);
        var tile = new ChunkTiles.Tile(GRID.getCellX(worldX), GRID.getCellY(worldZ));
        int localX = GRID.getLocalX(worldX), localZ = GRID.getLocalY(worldZ);
        int bit = 1 << ((localX / 16) + 2 * (localZ / 16));
        var model = ready.get(tile);
        if (model == null || (model.mask() & bit) == 0) {
            model = null;
            for (var layer : previousLayers.getOrDefault(tile, List.of())) {
                if ((layer.mask() & bit) != 0) { model = layer; break; }
            }
        }
        if (model == null) return unknown;
        int value = model.heights()[localZ * 32 + localX];
        return value == 32767 ? unknown : value;
    }

    public void invalidate(int chunkX, int chunkZ) {
        if (closed.get()) return;
        source.invalidate(chunkX, chunkZ);
        int minX = GRID.getCellX(chunkX * 16 - 16), maxX = GRID.getCellX(chunkX * 16 + 31);
        int minZ = GRID.getCellY(chunkZ * 16 - 16), maxZ = GRID.getCellY(chunkZ * 16 + 31);
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            var tile = new ChunkTiles.Tile(x, z);
            Entry entry = entries.get(tile);
            // Finish the detached snapshot already in flight; coalesce subsequent changes into one refresh.
            if (entry != null) {
                entry.dirty = true; entry.validatedMask = 0; entry.nextAttempt = 0; entry.misses = 0;
                schedule(tile, entry);
            }
        }
    }

    private static String fingerprint(NativeChunkSource.Capture capture, ChunkTiles.Tile tile) {
        var snapshot = capture.snapshot();
        MessageDigest digest = digest();
        try (var out = new DataOutputStream(new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest), 32768))) {
            out.writeUTF(NativeChunkSource.MODEL_FORMAT); out.writeInt(tile.x()); out.writeInt(tile.z());
            out.write(capture.tint().fingerprint());
            out.writeInt(snapshot.originX()); out.writeInt(snapshot.originZ());
            out.writeInt(snapshot.width()); out.writeInt(snapshot.depth());
            out.writeInt(snapshot.minY()); out.writeInt(snapshot.maxY());
            for (int z = 0; z < snapshot.depth(); z++) for (int x = 0; x < snapshot.width(); x++) {
                boolean known = capture.knownColumns()[z * snapshot.width() + x];
                out.writeBoolean(known);
                if (!known) continue;
                var column = snapshot.column(x, z); out.writeInt(column.size());
                for (var run : column) {
                    out.writeInt(run.fromY()); out.writeInt(run.toY());
                    byte[] state = STATE_KEYS.computeIfAbsent(run.state(), value -> value.toString().getBytes(StandardCharsets.UTF_8));
                    out.writeInt(state.length); out.write(state);
                }
                var biome = snapshot.biome(x, z);
                out.writeUTF(biome.namespace()); out.writeUTF(biome.path()); out.writeFloat(biome.temperature());
                out.writeInt(biome.water()); out.writeInt(biome.grass()); out.writeInt(biome.foliage());
            }
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String materialFingerprint(List<MaterialState> states) {
        var digest = digest();
        try (var out = new DataOutputStream(new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest), 32768))) {
            out.writeUTF("map-block-state-ids-v1"); out.writeInt(states.size());
            for (var state : states) {
                out.writeInt(state.id());
                byte[] description = state.state().toString().getBytes(StandardCharsets.UTF_8);
                out.writeInt(description.length); out.write(description);
            }
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(byte[] value) { return HexFormat.of().formatHex(digest().digest(value)); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        entries.values().forEach(entry -> { entry.obsolete.set(true); if (entry.work != null) entry.work.cancel(false); });
        entries.clear(); pending.clear(); preloads.clear(); ready.clear(); previousLayers.clear(); fragmentRefs.clear(); versions.clear(); changes.clear();
        projectionMetadata.clear(); source.close(); conversion.shutdown();
        if (cacheReads == null) return;
        var writes = CompletableFuture.allOf(layerWrites.values().toArray(CompletableFuture[]::new));
        layerWrites.clear();
        CompletableFuture.runAsync(() -> {
            boolean interrupted = false;
            try {
                interrupted = awaitRetired(conversion);
                cacheReads.shutdown();
                interrupted |= awaitRetired(cacheReads);
                try { writes.join(); }
                catch (java.util.concurrent.CompletionException failure) {
                    LogUtils.getLogger().debug("Native tile index writes did not all finish successfully: {}", failure.toString());
                }
                cacheWrites.shutdown();
                interrupted |= awaitRetired(cacheWrites);
                cache.whenComplete((storage, failure) -> { if (storage != null) storage.closeLayerSession(); });
            } finally {
                cacheReads.shutdown();
                cacheWrites.shutdown();
                if (interrupted) Thread.currentThread().interrupt();
            }
        });
    }

    private static boolean awaitRetired(ExecutorService workers) {
        boolean interrupted = false;
        for (;;) {
            try {
                if (workers.awaitTermination(1, java.util.concurrent.TimeUnit.DAYS)) return interrupted;
            } catch (InterruptedException failure) { interrupted = true; }
        }
    }
}
