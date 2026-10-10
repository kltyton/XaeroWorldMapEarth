package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import com.mojang.logging.LogUtils;
import java.util.Arrays;
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
    static final String CACHE_SUFFIX = NativeChunkIdentity.CACHE_SUFFIX;
    static final String MODEL_FORMAT = NativeChunkIdentity.MODEL_FORMAT;
    int minY() { return storage.minY(); }
    int maxY() { return storage.maxY(); }

    record Capture(ChunkMapSnapshot snapshot, boolean[] knownColumns) { }
    interface Section {
        BlockState getBlockState(int x, int y, int z);
        Holder<Biome> getNoiseBiome(int x, int y, int z);
    }
    record ChunkCopy(Section[] sections, int firstSectionY) {
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
    private final ConcurrentHashMap<Long, CompletableFuture<ChunkCopy>> chunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> missingUntil = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();


    NativeChunkSource(ClientLevel level, Executor conversion) {
        this.level = level; this.conversion = conversion;
        storage = new ChunkStoragePlatform(level, conversion, closed);
        fallbackBiome = storage.fallbackBiome;
    }

    CompletableFuture<Capture> capture(ChunkTiles.Tile tile, int ceiling) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Capture must start on the client thread");
        int originX = GRID.getCellMinX(tile.x()) - HALO, originZ = GRID.getCellMinY(tile.z()) - HALO;
        int minChunkX = Math.floorDiv(originX, 16), minChunkZ = Math.floorDiv(originZ, 16);
        int maxChunkX = Math.floorDiv(originX + SNAPSHOT_SIZE - 1, 16), maxChunkZ = Math.floorDiv(originZ + SNAPSHOT_SIZE - 1, 16);
        int width = maxChunkX - minChunkX + 1, depth = maxChunkZ - minChunkZ + 1;
        @SuppressWarnings("unchecked") CompletableFuture<ChunkCopy>[] copies = new CompletableFuture[width * depth];
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++)
            copies[z * width + x] = chunk(minChunkX + x, minChunkZ + z);
        int minY = minY(), maxY = Math.min(maxY(), ceiling);
        return CompletableFuture.allOf(copies).thenApplyAsync(ignored -> {
            if (closed.get()) throw new java.util.concurrent.CancellationException();
            ChunkCopy[] ready = Arrays.stream(copies).map(CompletableFuture::join).toArray(ChunkCopy[]::new);
            if (maxY <= minY) return null;
            var builder = ChunkMapSnapshot.builder(originX, originZ, SNAPSHOT_SIZE, SNAPSHOT_SIZE, 1, minY, maxY);
            boolean[] known = new boolean[SNAPSHOT_SIZE * SNAPSHOT_SIZE];
            boolean corePresent = false;
            for (int z = 0; z < SNAPSHOT_SIZE; z++) for (int x = 0; x < SNAPSHOT_SIZE; x++) {
                if (closed.get()) throw new java.util.concurrent.CancellationException();
                int worldX = originX + x, worldZ = originZ + z;
                ChunkCopy copy = ready[(Math.floorDiv(worldZ, 16) - minChunkZ) * width + Math.floorDiv(worldX, 16) - minChunkX];
                if (copy == null) continue;
                known[z * SNAPSHOT_SIZE + x] = true;
                if (x >= HALO && x < HALO + 32 && z >= HALO && z < HALO + 32) corePresent = true;
                int columnMax = maxY;
                if (ceiling != Integer.MAX_VALUE) {
                    while (columnMax > minY) {
                        var section = copy.section(columnMax - 1);
                        if (section == null || !section.getBlockState(worldX & 15, (columnMax - 1) & 15, worldZ & 15).canOcclude()) break;
                        columnMax--;
                    }
                }
                BlockState previous = Blocks.AIR.defaultBlockState(); int start = minY, top = minY;
                for (int y = minY; y <= columnMax; y++) {
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

    private CompletableFuture<ChunkCopy> chunk(int x, int z) {
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
            ChunkCopy live = storage.loaded(x, z);
            if (live != null) return CompletableFuture.completedFuture(live);
            return storage.read(x, z).exceptionally(failure -> {
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

    void invalidate(int chunkX, int chunkZ) {
        long key = ChunkStoragePlatform.key(chunkX, chunkZ);
        chunks.remove(key); missingUntil.remove(key);
    }

    @Override public void close() {
        closed.set(true); chunks.values().forEach(future -> future.cancel(false)); chunks.clear(); missingUntil.clear(); storage.close();
    }
}
