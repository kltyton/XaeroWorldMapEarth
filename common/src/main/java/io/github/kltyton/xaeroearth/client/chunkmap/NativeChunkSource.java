package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import io.github.kltyton.xaeroearth.client.chunkmap.biome.BiomeTintSnapshot;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Captures immutable map columns while preserving chunk-owner and stored-data boundaries. */
final class NativeChunkSource implements AutoCloseable {
    static final String CACHE_SUFFIX = NativeChunkIdentity.CACHE_SUFFIX + "-exposed-surface-v2-face-material-biome-blend-v1";
    static final String MODEL_FORMAT = NativeChunkIdentity.MODEL_FORMAT + ":exposed-surface-v2:detail-face-packet-v2:biome-blend-v1";
    int minY() { return storage.minY(); }
    int maxY() { return storage.maxY(); }

    record Capture(ChunkMapSnapshot snapshot, boolean[] knownColumns, BiomeTintSnapshot tint) { }
    record CaptureInput(String key, Supplier<Capture> materialize) { }
    interface Section {
        BlockState getBlockState(int x, int y, int z);
        Holder<Biome> getNoiseBiome(int x, int y, int z);
        default boolean occludes(int x, int y, int z) { return getBlockState(x, y, z).canOcclude(); }
        default int highestOccluder(int x, int z, int bottom, int top) {
            for (int y = top; y >= bottom; y--) if (occludes(x, y, z)) return y;
            return -1;
        }
    }
    @FunctionalInterface interface BiomeGetter { Holder<Biome> get(int x, int y, int z); }

    /** Copies the tiny biome palette without copying block states outside the terrain capture. */
    @SuppressWarnings("unchecked")
    static Section copyBiomes(BiomeGetter source) {
        Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[64];
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            biomes[(y << 4) | (z << 2) | x] = source.get(x, y, z);
        return new Section() {
            @Override public BlockState getBlockState(int x, int y, int z) {
                throw new IllegalStateException("Biome-only section has no block data");
            }
            @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
                return biomes[(y << 4) | (z << 2) | x];
            }
        };
    }
    /** The same detached section is shared by overlapping tile captures. */
    private static final class OcclusionSection implements Section {
        private final Section source;
        private volatile short[] opaque;

        private OcclusionSection(Section source) { this.source = source; }
        @Override public BlockState getBlockState(int x, int y, int z) { return source.getBlockState(x, y, z); }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) { return source.getNoiseBiome(x, y, z); }

        @Override public boolean occludes(int x, int y, int z) {
            short[] bits = opaque;
            if (bits == null) bits = buildOcclusion();
            return (bits[(z << 4) | x] & (1 << y)) != 0;
        }

        @Override public int highestOccluder(int x, int z, int bottom, int top) {
            short[] bits = opaque;
            if (bits == null) bits = buildOcclusion();
            int mask = (1 << (top + 1)) - (1 << bottom);
            return 31 - Integer.numberOfLeadingZeros(Short.toUnsignedInt(bits[(z << 4) | x]) & mask);
        }

        private synchronized short[] buildOcclusion() {
            if (opaque != null) return opaque;
            short[] bits = new short[256];
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!source.getBlockState(x, y, z).canOcclude()) continue;
                bits[(z << 4) | x] |= (short) (1 << y);
            }
            opaque = bits;
            return bits;
        }
    }
    record ChunkCopy(Section[] sections, int firstSectionY, int lowestBlock, int topExclusive) {
        ChunkCopy {
            for (int i = 0; i < sections.length; i++) {
                if (sections[i] != null && !(sections[i] instanceof OcclusionSection))
                    sections[i] = new OcclusionSection(sections[i]);
            }
        }
        Section section(int y) {
            if (y < lowestBlock || y >= topExclusive) return null;
            return biomeSection(y);
        }
        Section biomeSection(int y) {
            int index = (y >> 4) - firstSectionY;
            return index >= 0 && index < sections.length ? sections[index] : null;
        }
    }
    private static final TileGrid GRID = new TileGrid(32, 0);
    private static final int HALO = 16;
    private static final int SNAPSHOT_SIZE = 32 + HALO * 2;
    private static final long MISSING_RETRY_NANOS = 5_000_000_000L;
    private static final long LIVE_COPY_BUDGET_NANOS = 2_000_000L;
    private static final int MAX_LIVE_COPIES_PER_TICK = 16;
    private static final ThreadLocal<byte[]> FINGERPRINT_BUFFER = ThreadLocal.withInitial(() -> new byte[16384]);
    private final ClientLevel level;
    private final Executor conversion;
    private final Holder<Biome> fallbackBiome;
    private final ConcurrentHashMap<Holder<Biome>, byte[]> biomeDefinitions = new ConcurrentHashMap<>();
    private final WeakHashMap<ChunkCopy, CompletableFuture<byte[]>> biomeChunkFingerprints = new WeakHashMap<>();
    private final WeakHashMap<ChunkCopy, CompletableFuture<byte[]>> blockChunkFingerprints = new WeakHashMap<>();
    private final ChunkStoragePlatform storage;
    private record ExposedKey(int x, int z, int ceiling) { }
    private static final class RecentChunks<K> {
        private final LinkedHashMap<K, Boolean> order = new LinkedHashMap<>(128, 0.75f, true);

        synchronized void used(ConcurrentHashMap<K, CompletableFuture<ChunkCopy>> chunks, K key,
                               CompletableFuture<ChunkCopy> requested) {
            if (chunks.get(key) != requested) return;
            order.put(key, Boolean.TRUE);
            if (chunks.size() <= 512) return;
            var entries = order.keySet().iterator();
            while (chunks.size() > 512 && entries.hasNext()) {
                K candidate = entries.next();
                var work = chunks.get(candidate);
                if (work == null) entries.remove();
                else if (!candidate.equals(key) && work.isDone() && chunks.remove(candidate, work)) entries.remove();
            }
        }

        synchronized void forgetIfAbsent(ConcurrentHashMap<K, CompletableFuture<ChunkCopy>> chunks, K key) {
            if (!chunks.containsKey(key)) order.remove(key);
        }
        synchronized void removeFailed(ConcurrentHashMap<K, CompletableFuture<ChunkCopy>> chunks, K key,
                                       CompletableFuture<ChunkCopy> work) {
            if (chunks.remove(key, work)) order.remove(key);
        }
        synchronized void invalidate(ConcurrentHashMap<K, CompletableFuture<ChunkCopy>> chunks, K key) {
            chunks.remove(key);
            order.remove(key);
        }
        synchronized void invalidate(ConcurrentHashMap<K, CompletableFuture<ChunkCopy>> chunks,
                                     int first, int end, java.util.function.IntFunction<K> keyAt) {
            if (chunks.isEmpty()) return;
            for (int i = first; i < end; i++) {
                K key = keyAt.apply(i);
                chunks.remove(key);
                order.remove(key);
            }
        }
        synchronized void clear() { order.clear(); }
    }
    private record ChunkCache(ConcurrentHashMap<Long, CompletableFuture<ChunkCopy>> chunks,
                              ConcurrentHashMap<ExposedKey, CompletableFuture<ChunkCopy>> exposed,
                              ConcurrentHashMap<Long, Long> missingUntil,
                              RecentChunks<Long> chunkOrder, RecentChunks<ExposedKey> exposedOrder,
                              SurfaceExposureMemo surfaceExposure) {
        ChunkCache() { this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new RecentChunks<>(), new RecentChunks<>(), new SurfaceExposureMemo()); }
    }
    private volatile ChunkCache cache = new ChunkCache();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayDeque<LiveCopy> liveCopies = new ArrayDeque<>();

    private record LiveCopy(int x, int z, int floor, int ceiling, CompletableFuture<ChunkCopy> result) { }


    NativeChunkSource(ClientLevel level, Executor conversion) {
        this.level = level; this.conversion = conversion;
        storage = new ChunkStoragePlatform(level, conversion, closed);
        fallbackBiome = storage.fallbackBiome;
    }

    /** Bounds client-thread snapshot work while leaving server and disk reads asynchronous. */
    void pumpLiveCopies(double focusX, double focusZ) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Chunk copies must use the client thread");
        long deadline = System.nanoTime() + LIVE_COPY_BUDGET_NANOS;
        int processed = 0;
        while (processed < MAX_LIVE_COPIES_PER_TICK && !liveCopies.isEmpty()) {
            if (processed > 0 && System.nanoTime() >= deadline) break;
            LiveCopy nearestCopy = liveCopies.getFirst();
            double nearest = Double.POSITIVE_INFINITY;
            for (var candidate : liveCopies) {
                double dx = candidate.x() * 16.0 + 8 - focusX;
                double dz = candidate.z() * 16.0 + 8 - focusZ;
                double distance = dx * dx + dz * dz;
                if (distance < nearest) { nearest = distance; nearestCopy = candidate; }
            }
            var request = nearestCopy;
            liveCopies.remove(request);
            if (request.result().isDone()) continue;
            try {
                ChunkCopy live = storage.loaded(request.x(), request.z(), request.floor(), request.ceiling());
                if (live != null) request.result().complete(live);
                else storage.read(request.x(), request.z(), request.floor(), request.ceiling())
                        .whenComplete((copy, failure) -> {
                            if (failure == null) request.result().complete(copy);
                            else request.result().completeExceptionally(failure);
                        });
            } catch (RuntimeException | Error failure) {
                request.result().completeExceptionally(failure);
                if (failure instanceof Error error) throw error;
            }
            processed++;
        }
    }

    private CompletableFuture<ChunkCopy> queueLiveCopy(int x, int z, int floor, int ceiling) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Chunk copies must use the client thread");
        var result = new CompletableFuture<ChunkCopy>();
        liveCopies.addLast(new LiveCopy(x, z, floor, ceiling, result));
        return result;
    }

    void discardLiveCopies() {
        while (!liveCopies.isEmpty()) liveCopies.removeFirst().result().cancel(false);
    }

    /** Retain the full halo of every currently scheduled tile while abandoning old map areas. */
    void discardLiveCopiesOutside(double focusX, double focusZ, double radius) {
        double limit = radius + 96;
        var iterator = liveCopies.iterator();
        while (iterator.hasNext()) {
            var request = iterator.next();
            double x = request.x() * 16.0 + 8, z = request.z() * 16.0 + 8;
            if (Math.abs(x - focusX) <= limit && Math.abs(z - focusZ) <= limit) continue;
            iterator.remove();
            request.result().cancel(false);
        }
    }

    private static boolean cancelled(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof CancellationException;
    }

    CompletableFuture<CaptureInput> capture(ChunkTiles.Tile tile, int ceiling, java.util.function.BooleanSupplier cancelled) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Capture must start on the client thread");
        int originX = GRID.getCellMinX(tile.x()) - HALO, originZ = GRID.getCellMinY(tile.z()) - HALO;
        int minChunkX = Math.floorDiv(originX, 16), minChunkZ = Math.floorDiv(originZ, 16);
        int maxChunkX = Math.floorDiv(originX + SNAPSHOT_SIZE - 1, 16), maxChunkZ = Math.floorDiv(originZ + SNAPSHOT_SIZE - 1, 16);
        int width = maxChunkX - minChunkX + 1, depth = maxChunkZ - minChunkZ + 1;
        ChunkCache captureCache = cache;
        var biomeManager = level.getBiomeManager();
        long biomeSeed = biomeManager.biomeZoomSeed;
        int biomeRadius = BiomeBlendOptions.radius();
        var biomeOps = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        @SuppressWarnings("unchecked") CompletableFuture<ChunkCopy>[] copies = new CompletableFuture[width * depth];
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++)
            copies[z * width + x] = chunk(captureCache, minChunkX + x, minChunkZ + z, ceiling);
        int minY = minY(), maxY = Math.min(maxY(), ceiling);
        int minQuartY = Math.floorDiv(minY, 4), maxQuartY = Math.floorDiv(maxY() - 1, 4);
        return CompletableFuture.allOf(copies).thenComposeAsync(ignored -> {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            ChunkCopy[] ready = Arrays.stream(copies).map(CompletableFuture::join).toArray(ChunkCopy[]::new);
            if (ceiling == Integer.MAX_VALUE) {
                var openings = captureCache.surfaceExposure().openings(tile, ready,
                        () -> SurfaceExposureSearch.find(ready, width, depth, minY, maxY, cancelled));
                if (openings.isEmpty()) return CompletableFuture.completedFuture(ready);
                return expandExposed(captureCache, ready, minChunkX, minChunkZ, width, depth, minY, maxY,
                        openings, new HashSet<>(), cancelled);
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
            boolean coreAvailable = false;
            for (int z = HALO / 16; z < (HALO + 32) / 16; z++)
                for (int x = HALO / 16; x < (HALO + 32) / 16; x++) {
                    int i = z * width + x;
                    if (ready[i] != null && topExclusive[i] > bottom[i]) coreAvailable = true;
                }
            if (!coreAvailable) return null;
            var biomes = biomeManager.withDifferentSource((quartX, quartY, quartZ) -> {
                int chunkX = Math.floorDiv(quartX, 4) - minChunkX;
                int chunkZ = Math.floorDiv(quartZ, 4) - minChunkZ;
                if (chunkX < 0 || chunkX >= width || chunkZ < 0 || chunkZ >= depth) return fallbackBiome;
                int sampledY = Math.max(minQuartY, Math.min(maxQuartY, quartY));
                var chunk = ready[chunkZ * width + chunkX];
                var section = chunk == null ? null : chunk.biomeSection(sampledY << 2);
                return section == null ? fallbackBiome : section.getNoiseBiome(
                        Math.floorMod(quartX, 4), Math.floorMod(sampledY, 4), Math.floorMod(quartZ, 4));
            });
            byte[] biomeFingerprint = fingerprintBiomes(ready, minQuartY, maxQuartY, biomeSeed, biomeRadius, biomeOps, cancelled);
            var tint = new BiomeTintSnapshot(biomes, biomeRadius, originX, originZ,
                    SNAPSHOT_SIZE, SNAPSHOT_SIZE, biomeFingerprint);
            String inputKey = fingerprintInput(tile, ceiling, ready, biomeFingerprint, cancelled);
            int snapshotMinY = capturedMinY, snapshotMaxY = capturedMaxY;
            return new CaptureInput(inputKey, () -> {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                // In surface mode, seal completely sky-unreachable underground air pockets so sealed
                // deep caverns neither bloat mesh buffers nor float as dark fragments at low pitch.
                var visibility = ceiling == Integer.MAX_VALUE
                        ? SurfaceExposureSearch.visibility(ready, width, depth, snapshotMinY, snapshotMaxY, cancelled)
                        : null;
                var builder = ChunkMapSnapshot.builder(originX, originZ, SNAPSHOT_SIZE, SNAPSHOT_SIZE, 1, snapshotMinY, snapshotMaxY);
                var biomePosition = new BlockPos.MutableBlockPos();
                boolean[] known = new boolean[SNAPSHOT_SIZE * SNAPSHOT_SIZE];
                for (int z = 0; z < SNAPSHOT_SIZE; z++) for (int x = 0; x < SNAPSHOT_SIZE; x++) {
                    if (cancelled.getAsBoolean()) throw new CancellationException();
                    int worldX = originX + x, worldZ = originZ + z;
                    int index = (Math.floorDiv(worldZ, 16) - minChunkZ) * width + Math.floorDiv(worldX, 16) - minChunkX;
                    ChunkCopy copy = ready[index];
                    if (copy == null || topExclusive[index] <= bottom[index]) continue;
                    known[z * SNAPSHOT_SIZE + x] = true;
                    int columnMax = topExclusive[index];
                    if (ceiling != Integer.MAX_VALUE) {
                        while (columnMax > minY) {
                            var section = copy.section(columnMax - 1);
                            if (section == null || !section.getBlockState(worldX & 15, (columnMax - 1) & 15, worldZ & 15).canOcclude()) break;
                            columnMax--;
                        }
                    }
                    int lower = bottom[index];
                    int localX = worldX - (minChunkX << 4), localZ = worldZ - (minChunkZ << 4);
                    BlockState previous = Blocks.AIR.defaultBlockState(); int start = lower, top = lower;
                    for (int y = lower; y <= columnMax; y++) {
                        var section = copy.section(y);
                        BlockState state;
                        if (y == columnMax) state = Blocks.AIR.defaultBlockState();
                        else if (section == null) state = visibility != null && !visibility.isSkyReachable(localX, y, localZ)
                                ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        else if (visibility != null && !section.occludes(worldX & 15, y & 15, worldZ & 15)
                                && !visibility.isSkyReachable(localX, y, localZ)) state = Blocks.STONE.defaultBlockState();
                        else state = section.getBlockState(worldX & 15, y & 15, worldZ & 15);
                        if (state == previous) continue;
                        if (!previous.isAir()) { builder.addRun(x, z, start, y, previous); top = y - 1; }
                        previous = state; start = y;
                    }
                    Holder<Biome> biome = biomes.getBiome(biomePosition.set(worldX, top, worldZ));
                    builder.setBiome(x, z, biome, worldX, worldZ);
                }
                return new Capture(builder.build(), known, tint);
            });
        }, conversion);
    }

    private String fingerprintInput(ChunkTiles.Tile tile, int ceiling, ChunkCopy[] chunks, byte[] biomes,
                                    java.util.function.BooleanSupplier cancelled) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), 32768))) {
                out.writeUTF(MODEL_FORMAT + ":chunk-input-v1");
                out.writeInt(tile.x()); out.writeInt(tile.z()); out.writeInt(ceiling); out.write(biomes);
                for (ChunkCopy chunk : chunks) {
                    if (cancelled.getAsBoolean()) throw new CancellationException();
                    out.writeBoolean(chunk != null);
                    if (chunk != null) out.write(blockChunkFingerprint(chunk));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint map chunk content", failure);
        }
    }

    private byte[] blockChunkFingerprint(ChunkCopy chunk) {
        CompletableFuture<byte[]> work;
        boolean owner = false;
        synchronized (blockChunkFingerprints) {
            work = blockChunkFingerprints.get(chunk);
            if (work == null) {
                work = new CompletableFuture<>();
                blockChunkFingerprints.put(chunk, work);
                owner = true;
            }
        }
        if (owner) {
            try { work.complete(computeBlockChunkFingerprint(chunk)); }
            catch (RuntimeException | Error failure) {
                work.completeExceptionally(failure);
                synchronized (blockChunkFingerprints) { blockChunkFingerprints.remove(chunk, work); }
                throw failure;
            }
        }
        return work.join();
    }

    private static byte[] computeBlockChunkFingerprint(ChunkCopy chunk) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = FINGERPRINT_BUFFER.get();
            int pos = 0;
            pos = putInt(buffer, pos, chunk.lowestBlock());
            pos = putInt(buffer, pos, chunk.topExclusive());
            BlockState airState = Blocks.AIR.defaultBlockState();
            int air = Block.getId(airState);
            BlockState previousState = airState;
            int previousId = air;
            for (int y = chunk.lowestBlock(); y < chunk.topExclusive(); y++) {
                var section = chunk.section(y);
                int localY = y & 15;
                if (pos + 1024 > buffer.length) {
                    digest.update(buffer, 0, pos);
                    pos = 0;
                }
                if (section == null) {
                    for (int i = 0; i < 256; i++) pos = putInt(buffer, pos, air);
                } else {
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, localY, z);
                        int id;
                        if (state == previousState) id = previousId;
                        else {
                            previousState = state;
                            previousId = id = Block.getId(state);
                        }
                        pos = putInt(buffer, pos, id);
                    }
                }
            }
            if (pos > 0) digest.update(buffer, 0, pos);
            return digest.digest();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint map chunk blocks", failure);
        }
    }

    private static int putInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value >>> 24);
        buffer[offset + 1] = (byte) (value >>> 16);
        buffer[offset + 2] = (byte) (value >>> 8);
        buffer[offset + 3] = (byte) value;
        return offset + 4;
    }

    private byte[] fingerprintBiomes(ChunkCopy[] chunks, int minQuartY, int maxQuartY, long seed, int radius,
            RegistryOps<com.google.gson.JsonElement> ops, java.util.function.BooleanSupplier cancelled) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), 32768))) {
                out.writeLong(seed); out.writeInt(radius); out.writeInt(minQuartY); out.writeInt(maxQuartY);
                byte[] fallback = biomeDefinition(fallbackBiome, ops);
                out.writeInt(fallback.length); out.write(fallback);
                for (ChunkCopy chunk : chunks) {
                    if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    out.writeBoolean(chunk != null);
                    if (chunk != null) out.write(biomeChunkFingerprint(chunk, minQuartY, maxQuartY, ops));
                }
            }
            return digest.digest();
        } catch (java.io.IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint map biomes", failure);
        }
    }

    private byte[] biomeChunkFingerprint(ChunkCopy chunk, int minQuartY, int maxQuartY,
                                          RegistryOps<com.google.gson.JsonElement> ops) {
        CompletableFuture<byte[]> work;
        boolean owner = false;
        synchronized (biomeChunkFingerprints) {
            work = biomeChunkFingerprints.get(chunk);
            if (work == null) {
                work = new CompletableFuture<>();
                biomeChunkFingerprints.put(chunk, work);
                owner = true;
            }
        }
        if (owner) {
            try { work.complete(computeBiomeChunkFingerprint(chunk, minQuartY, maxQuartY, ops)); }
            catch (RuntimeException | Error failure) {
                work.completeExceptionally(failure);
                synchronized (biomeChunkFingerprints) { biomeChunkFingerprints.remove(chunk, work); }
                throw failure;
            }
        }
        return work.join();
    }

    private byte[] computeBiomeChunkFingerprint(ChunkCopy chunk, int minQuartY, int maxQuartY,
                                                 RegistryOps<com.google.gson.JsonElement> ops) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var palette = new IdentityHashMap<Holder<Biome>, Integer>();
            byte[] buffer = FINGERPRINT_BUFFER.get();
            int pos = 0;
            for (int y = minQuartY; y <= maxQuartY; y++) {
                var section = chunk.biomeSection(y << 2);
                int localY = y & 3;
                for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                    Holder<Biome> biome = section == null ? fallbackBiome : section.getNoiseBiome(x, localY, z);
                    Integer id = palette.get(biome);
                    if (id == null) {
                        id = palette.size(); palette.put(biome, id);
                        byte[] key = biome.unwrapKey().map(Object::toString).orElse("")
                                .getBytes(StandardCharsets.UTF_8);
                        byte[] value = biomeDefinition(biome, ops);
                        if (pos + 20 > buffer.length) { digest.update(buffer, 0, pos); pos = 0; }
                        pos = putInt(buffer, pos, -1);
                        pos = putInt(buffer, pos, id);
                        pos = putInt(buffer, pos, key.length);
                        digest.update(buffer, 0, pos); pos = 0;
                        digest.update(key);
                        pos = putInt(buffer, pos, value.length);
                        digest.update(buffer, 0, pos); pos = 0;
                        digest.update(value);
                    }
                    if (pos + 4 > buffer.length) { digest.update(buffer, 0, pos); pos = 0; }
                    pos = putInt(buffer, pos, id);
                }
            }
            if (pos > 0) digest.update(buffer, 0, pos);
            return digest.digest();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint chunk biomes", failure);
        }
    }

    private byte[] biomeDefinition(Holder<Biome> biome, RegistryOps<com.google.gson.JsonElement> ops) {
        return biomeDefinitions.computeIfAbsent(biome, holder ->
                Biome.DIRECT_CODEC.encodeStart(ops, holder.value()).result()
                        .orElseThrow(() -> new IllegalStateException("Cannot encode biome for map cache"))
                        .toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Surface snapshots start near the heightmap. Follow air connected to the exterior
     * through those snapshots and fetch deeper sections only where that air reaches
     * the lower edge, so cave mouths and overhangs do not end at the section cut.
     */
    private CompletableFuture<ChunkCopy[]> expandExposed(ChunkCache captureCache, ChunkCopy[] copies, int minChunkX, int minChunkZ,
            int width, int depth, int minY, int maxY, Set<Integer> exposed, Set<Integer> attempted,
            java.util.function.BooleanSupplier cancelled) {
        var additions = new ArrayList<CompletableFuture<Void>>();
        var failed = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        for (int index : exposed) {
            if (attempted.contains(index)) continue;
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
        if (additions.isEmpty()) return CompletableFuture.completedFuture(copies);
        return CompletableFuture.allOf(additions.toArray(CompletableFuture[]::new))
                .thenComposeAsync(ignored -> {
                    if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    attempted.addAll(failed);
                    return expandExposed(captureCache, copies, minChunkX, minChunkZ, width, depth, minY, maxY,
                            SurfaceExposureSearch.find(copies, width, depth, minY, maxY, cancelled), attempted, cancelled);
                }, conversion);
    }

    private CompletableFuture<ChunkCopy> exposedChunk(ChunkCache captureCache, int x, int z, int ceiling) {
        var exposed = captureCache.exposed();
        var key = new ExposedKey(x, z, ceiling);
        var result = exposed.computeIfAbsent(key, ignored -> {
            var pending = new CompletableFuture<ChunkCopy>();
            Minecraft.getInstance().execute(() -> {
                if (closed.get()) { pending.complete(null); return; }
                int floor = ceiling - 16;
                CompletableFuture<ChunkCopy> requested = storage.isLoaded(x, z)
                        ? queueLiveCopy(x, z, floor, ceiling) : storage.read(x, z, floor, ceiling);
                requested.whenComplete((copy, failure) -> {
                    if (failure != null) pending.completeExceptionally(failure);
                    else pending.complete(copy);
                });
            });
            return pending;
        });
        captureCache.exposedOrder().used(exposed, key, result);
        result.whenComplete((copy, failure) -> {
            if (failure != null || copy == null) captureCache.exposedOrder().removeFailed(exposed, key, result);
        });
        return result;
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
        var result = chunks.computeIfAbsent(key, ignored -> {
            CompletableFuture<ChunkCopy> requested = storage.isLoaded(x, z)
                    ? queueLiveCopy(x, z, minY(), ceiling) : storage.read(x, z, minY(), ceiling);
            return requested.exceptionally(failure -> {
                if (cancelled(failure)) throw new CancellationException("Map chunk capture is obsolete");
                if (!closed.get()) LogUtils.getLogger().debug("Existing map chunk {},{} unavailable: {}", x, z, failure.toString());
                return null;
            });
        });
        captureCache.chunkOrder().used(chunks, key, result);
        result.whenComplete((copy, failure) -> {
            if (copy != null) return;
            chunks.computeIfPresent(key, (ignored, current) -> {
                if (current != result) return current;
                if (!closed.get() && !cancelled(failure)) {
                    if (missingUntil.size() >= 65536) {
                        var keys = missingUntil.keys();
                        if (keys.hasMoreElements()) missingUntil.remove(keys.nextElement());
                    }
                    missingUntil.put(key, System.nanoTime() + MISSING_RETRY_NANOS);
                }
                return null;
            });
            captureCache.chunkOrder().forgetIfAbsent(chunks, key);
        });
        return result;
    }

    Holder<Biome> cachedBiome(BlockPos position) {
        if (closed.get() || level.isOutsideBuildHeight(position)) return null;
        var observed = cache.chunks();
        return level.getBiomeManager().withDifferentSource((quartX, quartY, quartZ) -> {
            var work = observed.get(ChunkStoragePlatform.key(Math.floorDiv(quartX, 4), Math.floorDiv(quartZ, 4)));
            if (work == null || !work.isDone() || work.isCompletedExceptionally()) return null;
            var chunk = work.getNow(null);
            int y = Math.max(minY(), Math.min(maxY() - 1, quartY << 2));
            var section = chunk == null ? null : chunk.biomeSection(y);
            return section == null ? null : section.getNoiseBiome(Math.floorMod(quartX, 4),
                    (y >> 2) & 3, Math.floorMod(quartZ, 4));
        }).getBiome(position);
    }

    void discardCeiling() {
        // In-flight callbacks retain their old cache and cannot install data in the new layer.
        cache = new ChunkCache();
        discardLiveCopies();
    }

    void invalidate(int chunkX, int chunkZ) {
        long chunk = ChunkStoragePlatform.key(chunkX, chunkZ);
        ChunkCache current = cache;
        current.chunkOrder().invalidate(current.chunks(), chunk);
        // Exposed copies always end at a section boundary. Address this column
        // directly so a Chunky update does not walk every cached cave entrance.
        current.exposedOrder().invalidate(current.exposed(), (minY() >> 4) + 1, (maxY() >> 4) + 1,
                section -> new ExposedKey(chunkX, chunkZ, section << 4));
        current.missingUntil().remove(chunk);
    }

    @Override public void close() {
        closed.set(true);
        discardLiveCopies();
        cache.chunks().values().forEach(future -> future.cancel(false));
        cache.exposed().values().forEach(future -> future.cancel(false));
        cache.chunks().clear();
        cache.exposed().clear();
        cache.chunkOrder().clear();
        cache.exposedOrder().clear();
        cache.missingUntil().clear();
        storage.close();
    }
}
