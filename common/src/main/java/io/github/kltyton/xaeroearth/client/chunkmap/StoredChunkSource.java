package io.github.kltyton.xaeroearth.client.chunkmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.IntBinaryOperator;
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
        if (!chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING))
            return new SectionRange(minSection + count, minSection + count - 1);
        int highest = minSection;
        var sections = chunk.getSections();
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
            return new SectionRange(minSection + count, minSection + count - 1);
        return sectionRange(minSection, count, floor, ceiling, (x, z) -> {
            int index = z * 16 + x;
            long offset = packedHeights[index / entriesPerWord] >>> (index % entriesPerWord * bits);
            return minSection * 16 + (int) (offset & ((1L << bits) - 1)) - 1;
        });
    }

    protected static <T> ChunkCopy decodeSections(int tagCount, java.util.function.IntFunction<T> tags,
            java.util.function.ToIntFunction<T> sectionY, int count, int minimum,
            SectionRange range, java.util.function.Function<T, NativeChunkSource.Section> decode) {
        NativeChunkSource.Section[] sections = new NativeChunkSource.Section[count];
        for (int i = 0; i < tagCount; i++) {
            T section = tags.apply(i);
            int y = sectionY.applyAsInt(section);
            int index = y - minimum;
            if (index >= 0 && index < sections.length && range.contains(y)) sections[index] = decode.apply(section);
        }
        return new ChunkCopy(sections, minimum, range.low() << 4, (range.high() + 1) << 4);
    }

    @Override public final void close() { stored.cancel(false); }
}
