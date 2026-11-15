package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Captures immutable map columns while preserving chunk-owner and stored-data boundaries. */
final class NativeChunkSource implements AutoCloseable {
    static final String CACHE_SUFFIX = NativeChunkIdentity.CACHE_SUFFIX + "-exposed-surface-v1";
    static final String MODEL_FORMAT = NativeChunkIdentity.MODEL_FORMAT + ":exposed-surface-v1";
    int minY() { return storage.minY(); }
    int maxY() { return storage.maxY(); }

    record Capture(ChunkMapSnapshot snapshot, boolean[] knownColumns) { }
    interface Section {
        BlockState getBlockState(int x, int y, int z);
        Holder<Biome> getNoiseBiome(int x, int y, int z);
    }
    record ChunkCopy(Section[] sections, int firstSectionY, int lowestBlock, int topExclusive) {
        Section section(int y) {
            int index = (y >> 4) - firstSectionY;
            return index >= 0 && index < sections.length ? sections[index] : null;
        }
    }
    private static final TileGrid GRID = new TileGrid(32, 0);
    private static final int HALO = 16;
    private static final int SNAPSHOT_SIZE = 32 + HALO * 2;
    private static final long MISSING_RETRY_NANOS = 5_000_000_000L;
    private final ClientLevel level;
    private final Executor conversion;
    private final Holder<Biome> fallbackBiome;
    private final ChunkStoragePlatform storage;
    private record ExposedKey(int x, int z, int ceiling) { }
    private record ChunkCache(ConcurrentHashMap<Long, CompletableFuture<ChunkCopy>> chunks,
                              ConcurrentHashMap<ExposedKey, CompletableFuture<ChunkCopy>> exposed,
                              ConcurrentHashMap<Long, Long> missingUntil) {
        ChunkCache() { this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>()); }
    }
    private volatile ChunkCache cache = new ChunkCache();
    private final AtomicBoolean closed = new AtomicBoolean();


    NativeChunkSource(ClientLevel level, Executor conversion) {
        this.level = level; this.conversion = conversion;
        storage = new ChunkStoragePlatform(level, conversion, closed);
        fallbackBiome = storage.fallbackBiome;
    }

    CompletableFuture<Capture> capture(ChunkTiles.Tile tile, int ceiling, java.util.function.BooleanSupplier cancelled) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Capture must start on the client thread");
        int originX = GRID.getCellMinX(tile.x()) - HALO, originZ = GRID.getCellMinY(tile.z()) - HALO;
        int minChunkX = Math.floorDiv(originX, 16), minChunkZ = Math.floorDiv(originZ, 16);
        int maxChunkX = Math.floorDiv(originX + SNAPSHOT_SIZE - 1, 16), maxChunkZ = Math.floorDiv(originZ + SNAPSHOT_SIZE - 1, 16);
        int width = maxChunkX - minChunkX + 1, depth = maxChunkZ - minChunkZ + 1;
        ChunkCache captureCache = cache;
        @SuppressWarnings("unchecked") CompletableFuture<ChunkCopy>[] copies = new CompletableFuture[width * depth];
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++)
            copies[z * width + x] = chunk(captureCache, minChunkX + x, minChunkZ + z, ceiling);
        int minY = minY(), maxY = Math.min(maxY(), ceiling);
        return CompletableFuture.allOf(copies).thenComposeAsync(ignored -> {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            ChunkCopy[] ready = Arrays.stream(copies).map(CompletableFuture::join).toArray(ChunkCopy[]::new);
            if (ceiling == Integer.MAX_VALUE) {
                return expandExposed(captureCache, ready, minChunkX, minChunkZ, width, depth, minY, maxY,
                        new HashSet<>(), cancelled);
            }
            return CompletableFuture.completedFuture(ready);
        }, conversion).thenApplyAsync(ready -> {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int[] bottom = new int[ready.length], topExclusive = new int[ready.length];
            int capturedMinY = maxY, capturedMaxY = minY;
            for (int i = 0; i < ready.length; i++) if (ready[i] != null) {
                bottom[i] = Math.max(minY, ready[i].lowestBlock());
                topExclusive[i] = Math.min(maxY, ready[i].topExclusive());
                if (topExclusive[i] <= bottom[i]) continue;
                capturedMinY = Math.min(capturedMinY, bottom[i]);
                capturedMaxY = Math.max(capturedMaxY, topExclusive[i]);
            }
            if (capturedMaxY <= capturedMinY) return null;
            var builder = ChunkMapSnapshot.builder(originX, originZ, SNAPSHOT_SIZE, SNAPSHOT_SIZE, 1, capturedMinY, capturedMaxY);
            boolean[] known = new boolean[SNAPSHOT_SIZE * SNAPSHOT_SIZE];
            boolean corePresent = false;
            for (int z = 0; z < SNAPSHOT_SIZE; z++) for (int x = 0; x < SNAPSHOT_SIZE; x++) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                int worldX = originX + x, worldZ = originZ + z;
                int index = (Math.floorDiv(worldZ, 16) - minChunkZ) * width + Math.floorDiv(worldX, 16) - minChunkX;
                ChunkCopy copy = ready[index];
                if (copy == null || topExclusive[index] <= bottom[index]) continue;
                known[z * SNAPSHOT_SIZE + x] = true;
                if (x >= HALO && x < HALO + 32 && z >= HALO && z < HALO + 32) corePresent = true;
                int columnMax = topExclusive[index];
                if (ceiling != Integer.MAX_VALUE) {
                    while (columnMax > minY) {
                        var section = copy.section(columnMax - 1);
                        if (section == null || !section.getBlockState(worldX & 15, (columnMax - 1) & 15, worldZ & 15).canOcclude()) break;
                        columnMax--;
                    }
                }
                int lower = bottom[index];
                BlockState previous = Blocks.AIR.defaultBlockState(); int start = lower, top = lower;
                for (int y = lower; y <= columnMax; y++) {
                    var section = copy.section(y);
                    BlockState state = y == columnMax || section == null ? Blocks.AIR.defaultBlockState()
                            : section.getBlockState(worldX & 15, y & 15, worldZ & 15);
                    if (state == previous) continue;
                    if (!previous.isAir()) { builder.addRun(x, z, start, y, previous); top = y - 1; }
                    previous = state; start = y;
                }
                var biomeSection = copy.section(top);
                Holder<Biome> biome = biomeSection == null ? fallbackBiome : biomeSection.getNoiseBiome(
                        (worldX & 15) >> 2, (top & 15) >> 2, (worldZ & 15) >> 2);
                builder.setBiome(x, z, biome, worldX, worldZ);
            }
            return corePresent ? new Capture(builder.build(), known) : null;
        }, conversion);
    }

    /**
     * Surface snapshots start near the heightmap. Follow air connected to the exterior
     * through those snapshots and fetch deeper sections only where that air reaches
     * the lower edge, so cave mouths and overhangs do not end at the section cut.
     */
    private CompletableFuture<ChunkCopy[]> expandExposed(ChunkCache captureCache, ChunkCopy[] copies, int minChunkX, int minChunkZ,
            int width, int depth, int minY, int maxY, Set<Integer> attempted,
            java.util.function.BooleanSupplier cancelled) {
        Set<Integer> exposed = exposedBottoms(copies, width, depth, minY, maxY, cancelled);
        exposed.removeAll(attempted);
        if (exposed.isEmpty()) return CompletableFuture.completedFuture(copies);
        var additions = new ArrayList<CompletableFuture<Void>>();
        var failed = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        for (int index : exposed) {
            ChunkCopy upper = copies[index];
            int x = minChunkX + index % width;
            int z = minChunkZ + index / width;
            int bottom = upper.lowestBlock();
            additions.add(exposedChunk(captureCache, x, z, bottom).thenAccept(lower -> {
                if (lower == null || lower.lowestBlock() >= bottom || lower.topExclusive() != bottom) {
                    failed.add(index);
                    return;
                }
                if (cancelled.getAsBoolean()) return;
                Section[] merged = upper.sections().clone();
                int section = lower.lowestBlock() >> 4;
                int length = (bottom >> 4) - section;
                System.arraycopy(lower.sections(), section - lower.firstSectionY(),
                        merged, section - upper.firstSectionY(), length);
                copies[index] = new ChunkCopy(merged, upper.firstSectionY(), lower.lowestBlock(), upper.topExclusive());
            }));
        }
        return CompletableFuture.allOf(additions.toArray(CompletableFuture[]::new))
                .thenComposeAsync(ignored -> {
                    if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    attempted.addAll(failed);
                    return expandExposed(captureCache, copies, minChunkX, minChunkZ, width, depth, minY, maxY, attempted, cancelled);
                }, conversion);
    }

    private CompletableFuture<ChunkCopy> exposedChunk(ChunkCache captureCache, int x, int z, int ceiling) {
        var exposed = captureCache.exposed();
        var key = new ExposedKey(x, z, ceiling);
        if (exposed.size() > 512) {
            for (var cached : exposed.entrySet()) {
                if (cached.getValue().isDone() && !cached.getKey().equals(key)) exposed.remove(cached.getKey(), cached.getValue());
                if (exposed.size() <= 512) break;
            }
        }
        var result = exposed.computeIfAbsent(key, ignored -> {
            var pending = new CompletableFuture<ChunkCopy>();
            Minecraft.getInstance().execute(() -> {
                if (closed.get()) { pending.complete(null); return; }
                int floor = ceiling - 16;
                ChunkCopy live = storage.loaded(x, z, floor, ceiling);
                if (live != null) pending.complete(live);
                else storage.read(x, z, floor, ceiling).whenComplete((copy, failure) -> {
                    if (failure != null) pending.completeExceptionally(failure);
                    else pending.complete(copy);
                });
            });
            return pending;
        });
        result.whenComplete((copy, failure) -> {
            if (failure != null || copy == null) exposed.remove(key, result);
        });
        return result;
    }

    private static Set<Integer> exposedBottoms(ChunkCopy[] copies, int chunksWide, int chunksDeep,
            int minY, int maxY, java.util.function.BooleanSupplier cancelled) {
        int width = chunksWide * 16, depth = chunksDeep * 16, plane = width * depth;
        int[] bottoms = new int[copies.length];
        int capturedBottom = maxY, capturedTop = minY;
        for (int i = 0; i < copies.length; i++) {
            if (copies[i] == null) continue;
            bottoms[i] = copies[i].lowestBlock();
            capturedBottom = Math.min(capturedBottom, bottoms[i]);
            capturedTop = Math.max(capturedTop, copies[i].topExclusive());
        }
        if (capturedTop <= capturedBottom) return new HashSet<>();
        minY = Math.max(minY, capturedBottom - 1);
        maxY = Math.min(maxY, capturedTop);
        int height = maxY - minY;
        int size = Math.multiplyExact(plane, height + 1);
        boolean[] visited = new boolean[size];
        int[] queue = new int[size];
        int head = 0, tail = 0;
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            int chunk = (z >> 4) * chunksWide + (x >> 4);
            if (copies[chunk] == null) continue;
            int cell = height * plane + z * width + x;
            visited[cell] = true;
            queue[tail++] = cell;
        }
        Set<Integer> exposed = new HashSet<>();
        while (head < tail) {
            if ((head & 4095) == 0 && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int cell = queue[head++];
            int y = cell / plane, horizontal = cell % plane;
            int z = horizontal / width, x = horizontal % width;
            for (int direction = 0; direction < 6; direction++) {
                int nx = x + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
                int nz = z + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
                int ny = y + (direction == 4 ? 1 : direction == 5 ? -1 : 0);
                if (nx < 0 || nx >= width || nz < 0 || nz >= depth || ny < 0 || ny > height) continue;
                int next = ny * plane + nz * width + nx;
                if (visited[next]) continue;
                visited[next] = true;
                int chunk = (nz >> 4) * chunksWide + (nx >> 4);
                ChunkCopy copy = copies[chunk];
                if (copy == null) continue;
                int blockY = minY + ny;
                if (blockY < bottoms[chunk]) {
                    if (blockY >= minY) exposed.add(chunk);
                    continue;
                }
                Section section = ny == height ? null : copy.section(blockY);
                if (section != null && section.getBlockState(nx & 15, blockY & 15, nz & 15).canOcclude()) continue;
                queue[tail++] = next;
            }
        }
        return exposed;
    }

    private CompletableFuture<ChunkCopy> chunk(ChunkCache captureCache, int x, int z, int ceiling) {
        var chunks = captureCache.chunks();
        var missingUntil = captureCache.missingUntil();
        long key = ChunkStoragePlatform.key(x, z);
        Long retry = missingUntil.get(key);
        if (retry != null) {
            if (System.nanoTime() < retry && !storage.isLoaded(x, z))
                return CompletableFuture.completedFuture(null);
            missingUntil.remove(key, retry);
        }
        if (chunks.size() > 512) {
            for (var cached : chunks.entrySet()) {
                if (cached.getValue().isDone() && cached.getKey() != key) chunks.remove(cached.getKey(), cached.getValue());
                if (chunks.size() <= 512) break;
            }
        }
        var result = chunks.computeIfAbsent(key, ignored -> {
            ChunkCopy live = storage.loaded(x, z, minY(), ceiling);
            if (live != null) return CompletableFuture.completedFuture(live);
            return storage.read(x, z, minY(), ceiling).exceptionally(failure -> {
                if (!closed.get()) LogUtils.getLogger().debug("Existing map chunk {},{} unavailable: {}", x, z, failure.toString());
                return null;
            });
        });
        result.whenComplete((copy, failure) -> {
            if (copy != null) return;
            chunks.computeIfPresent(key, (ignored, current) -> {
                if (current != result) return current;
                if (!closed.get()) {
                    if (missingUntil.size() >= 65536) {
                        var keys = missingUntil.keys();
                        if (keys.hasMoreElements()) missingUntil.remove(keys.nextElement());
                    }
                    missingUntil.put(key, System.nanoTime() + MISSING_RETRY_NANOS);
                }
                return null;
            });
        });
        return result;
    }

    void discardCeiling() {
        // In-flight callbacks retain their old cache and cannot install data in the new layer.
        cache = new ChunkCache();
    }

    void invalidate(int chunkX, int chunkZ) {
        long chunk = ChunkStoragePlatform.key(chunkX, chunkZ);
        cache.chunks().remove(chunk);
        cache.exposed().keySet().removeIf(key -> key.x() == chunkX && key.z() == chunkZ);
        cache.missingUntil().remove(chunk);
    }

    @Override public void close() {
        closed.set(true);
        cache.chunks().values().forEach(future -> future.cancel(false));
        cache.exposed().values().forEach(future -> future.cancel(false));
        cache.chunks().clear();
        cache.exposed().clear();
        cache.missingUntil().clear();
        storage.close();
    }
}
