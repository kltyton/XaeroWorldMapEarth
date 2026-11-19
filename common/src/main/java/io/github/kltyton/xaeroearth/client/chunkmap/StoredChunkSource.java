package io.github.kltyton.xaeroearth.client.chunkmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.IntBinaryOperator;
import java.util.function.IntFunction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeChunkSource.ChunkCopy;

/** Keeps server-thread snapshots and disk conversion in the same world lifetime. */
abstract class StoredChunkSource<C extends StoredChunkSource.Context> implements AutoCloseable {
    interface Context {
        MinecraftServer server();
        Path regions();
    }

    protected final ClientLevel level;
    protected final Executor conversion;
    protected final AtomicBoolean closed;
    private CompletableFuture<C> stored;

    StoredChunkSource(ClientLevel level, Executor conversion, AtomicBoolean closed) {
        this.level = level;
        this.conversion = conversion;
        this.closed = closed;
    }

    protected final void initialize(MinecraftServer server, Supplier<C> context) {
        stored = server == null ? CompletableFuture.completedFuture(null) : server.submit(context::get);
    }

    protected abstract ChunkCopy copyLoaded(C context, int x, int z, int floor, int ceiling);
    protected abstract CompletableFuture<ChunkCopy> readStored(C context, int x, int z, int floor, int ceiling);

    final CompletableFuture<ChunkCopy> read(int x, int z, int floor, int ceiling) {
        return stored.thenCompose(context -> {
            if (context == null || closed.get() || context.server().isStopped()) return CompletableFuture.completedFuture(null);
            return context.server().submit(() -> {
                if (closed.get() || context.server().isStopped()) return (ChunkCopy) null;
                return copyLoaded(context, x, z, floor, ceiling);
            }).thenComposeAsync(current -> {
                if (current != null) return CompletableFuture.completedFuture(current);
                if (closed.get() || context.server().isStopped()) return CompletableFuture.completedFuture(null);
                Path region = context.regions().resolve("r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
                if (!Files.isRegularFile(region)) return CompletableFuture.completedFuture(null);
                return readStored(context, x, z, floor, ceiling);
            }, conversion);
        });
    }

    protected record SectionRange(int low, int high) {
        boolean contains(int section) { return section >= low && section <= high; }
    }

    protected static SectionRange sectionRange(int minSection, int count, int floor, int ceiling, IntBinaryOperator height) {
        int last = minSection + count - 1;
        if (ceiling <= minSection * 16) return new SectionRange(last + 1, last);
        if (ceiling != Integer.MAX_VALUE)
            return new SectionRange(Math.max(minSection, floor >> 4), Math.min(last, (ceiling - 1) >> 4));
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int y = height.applyAsInt(x, z);
            if (y < minSection * 16) continue;
            lowest = Math.min(lowest, y); highest = Math.max(highest, y);
        }
        if (lowest == Integer.MAX_VALUE) return new SectionRange(last + 1, last);
        // Keep a section below the lowest mapped surface for cliff sides and model neighbours.
        return new SectionRange(Math.max(minSection, (lowest >> 4) - 1), Math.min(last, highest >> 4));
    }

    protected static SectionRange liveSectionRange(LevelChunk chunk, int minSection, int count, int floor, int ceiling) {
        if (ceiling != Integer.MAX_VALUE) return sectionRange(minSection, count, floor, ceiling, (x, z) -> 0);
        if (chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE))
            return sectionRange(minSection, count, floor, ceiling, (x, z) -> chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        int highest = minSection;
        var sections = chunk.getSections();
        if (!chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING)) {
            return scanSurface(minSection, count, i -> sections[i].hasOnlyAir() ? null : sections[i],
                    (section, x, y, z) -> section.getBlockState(x, y, z).isAir());
        }
        for (int i = sections.length - 1; i >= 0; i--) {
            if (!sections[i].hasOnlyAir()) {
                highest = minSection + i;
                break;
            }
        }
        int top = highest;
        var heights = sectionRange(minSection, count, floor, ceiling, (x, z) -> chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
        if (heights.low() > heights.high()) return new SectionRange(Math.max(minSection, top - 1), top);
        return new SectionRange(heights.low(), Math.max(heights.high(), top));
    }

    protected static SectionRange storedSectionRange(int minSection, int count, int floor, int ceiling, long[] packedHeights) {
        if (ceiling != Integer.MAX_VALUE) return sectionRange(minSection, count, floor, ceiling, (x, z) -> 0);
        int bits = 32 - Integer.numberOfLeadingZeros(count * 16);
        int entriesPerWord = 64 / bits;
        if (packedHeights.length != (256 + entriesPerWord - 1) / entriesPerWord)
            return null; // A detached block scan can recover a missing or malformed heightmap.
        return sectionRange(minSection, count, floor, ceiling, (x, z) -> {
            int index = z * 16 + x;
            long offset = packedHeights[index / entriesPerWord] >>> (index % entriesPerWord * bits);
            return minSection * 16 + (int) (offset & ((1L << bits) - 1)) - 1;
        });
    }

    @FunctionalInterface
    protected interface AirTest<S> { boolean isAir(S section, int x, int y, int z); }

    /** Finds the first non-air block of each column when the source lacks surface metadata. */
    protected static <S> SectionRange scanSurface(int minimum, int count, IntFunction<S> load, AirTest<S> blocks) {
        var open = new BitSet(256);
        open.set(0, 256);
        int highest = minimum - 1, lowest = minimum + count;
        for (int index = count - 1; index >= 0 && !open.isEmpty(); index--) {
            S section = load.apply(index);
            if (section == null) continue;
            for (int column = open.nextSetBit(0); column >= 0; column = open.nextSetBit(column + 1)) {
                for (int y = 15; y >= 0; y--) {
                    if (blocks.isAir(section, column & 15, y, column >> 4)) continue;
                    highest = Math.max(highest, minimum + index);
                    lowest = minimum + index;
                    open.clear(column);
                    break;
                }
            }
        }
        return new SectionRange(Math.max(minimum, lowest - 1), highest);
    }

    protected static <T> ChunkCopy decodeSections(int tagCount, IntFunction<T> tags,
            java.util.function.ToIntFunction<T> sectionY, int count, int minimum,
            SectionRange range, java.util.function.BiFunction<T, Boolean, NativeChunkSource.Section> decode) {
        NativeChunkSource.Section[] sections = new NativeChunkSource.Section[count];
        if (range == null) {
            var bySection = new java.util.ArrayList<T>(java.util.Collections.nCopies(count, null));
            for (int i = 0; i < tagCount; i++) {
                T tag = tags.apply(i);
                int index = sectionY.applyAsInt(tag) - minimum;
                if (index >= 0 && index < count) bySection.set(index, tag);
            }
            range = scanSurface(minimum, count, index -> {
                T tag = bySection.get(index);
                return tag == null ? null : (sections[index] = decode.apply(tag, false));
            }, (section, x, y, z) -> section.getBlockState(x, y, z).isAir());
        }
        if (range.low() > range.high()) return null;
        for (int i = 0; i < tagCount; i++) {
            T section = tags.apply(i);
            int y = sectionY.applyAsInt(section);
            int index = y - minimum;
            if (index < 0 || index >= sections.length) continue;
            boolean biomeOnly = !range.contains(y);
            var observed = sections[index];
            if (observed == null) sections[index] = decode.apply(section, biomeOnly);
            else if (biomeOnly) sections[index] = NativeChunkSource.copyBiomes(observed::getNoiseBiome);
        }
        return new ChunkCopy(sections, minimum, range.low() << 4, (range.high() + 1) << 4);
    }

    @Override public final void close() { stored.cancel(false); }
}
