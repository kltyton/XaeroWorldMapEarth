package io.github.kltyton.xaeroearth.client.chunkmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
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

    protected abstract ChunkCopy copyLoaded(C context, int x, int z);
    protected abstract CompletableFuture<ChunkCopy> readStored(C context, int x, int z);

    final CompletableFuture<ChunkCopy> read(int x, int z) {
        return stored.thenCompose(context -> {
            if (context == null || closed.get() || context.server().isStopped()) return CompletableFuture.completedFuture(null);
            return context.server().submit(() -> {
                if (closed.get() || context.server().isStopped()) return (ChunkCopy) null;
                return copyLoaded(context, x, z);
            }).thenComposeAsync(current -> {
                if (current != null) return CompletableFuture.completedFuture(current);
                if (closed.get() || context.server().isStopped()) return CompletableFuture.completedFuture(null);
                Path region = context.regions().resolve("r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
                if (!Files.isRegularFile(region)) return CompletableFuture.completedFuture(null);
                return readStored(context, x, z);
            }, conversion);
        });
    }

    protected static <T> ChunkCopy decodeSections(int tagCount, java.util.function.IntFunction<T> tags,
            java.util.function.ToIntFunction<T> sectionY, int count, int minimum,
            java.util.function.Function<T, NativeChunkSource.Section> decode) {
        NativeChunkSource.Section[] sections = new NativeChunkSource.Section[count];
        for (int i = 0; i < tagCount; i++) {
            T section = tags.apply(i);
            int index = sectionY.applyAsInt(section) - minimum;
            if (index >= 0 && index < sections.length) sections[index] = decode.apply(section);
        }
        return new ChunkCopy(sections, minimum);
    }

    @Override public final void close() { stored.cancel(false); }
}
