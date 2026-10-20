package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeChunkSource.ChunkCopy;

final class ChunkStoragePlatform extends StoredChunkSource<ChunkStoragePlatform.StoredContext> {
    final Holder<Biome> fallbackBiome;
    int minY() { return level.getMinY(); }
    int maxY() { return level.getMaxY() + 1; }


    static long key(int x, int z) { return ChunkPos.pack(x, z); }

    private record SectionCopy(LevelChunkSection section) implements NativeChunkSource.Section {
        public BlockState getBlockState(int x, int y, int z) { return section.getBlockState(x, y, z); }
        public Holder<Biome> getNoiseBiome(int x, int y, int z) { return section.getNoiseBiome(x, y, z); }
    }
    record StoredContext(MinecraftServer server, ChunkMap storage, Path regions,
                                 PalettedContainerFactory factory, LevelHeightAccessor height, CompoundTag datafix) implements StoredChunkSource.Context { }

    ChunkStoragePlatform(ClientLevel level, Executor conversion, AtomicBoolean closed) {
        super(level, conversion, closed);
        fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        initialize(server, () -> {
            var world = server.getLevel(level.dimension());
            if (world == null || closed.get()) return null;
            var storage = world.getChunkSource().chunkMap;
            Path regions = DimensionType.getStorageFolder(world.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
            return new StoredContext(server, storage, regions, world.palettedContainerFactory(),
                    LevelHeightAccessor.create(world.getMinY(), world.getHeight()),
                    ChunkMap.getChunkDataFixContextTag(world.dimension(), world.getChunkSource().getGenerator().getTypeNameForDataFixer()));
        });
    }


    boolean isLoaded(int x, int z) {
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
    }

    ChunkCopy loaded(int x, int z) {
        LevelChunk live = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (live != null) return copy(live);
        return null;
    }

    @Override protected ChunkCopy copyLoaded(StoredContext context, int x, int z) {
        var world = context.server.getLevel(level.dimension());
        if (world == null) return (ChunkCopy) null;
        LevelChunk current = world.getChunkSource().getChunkNow(x, z);
        return current == null ? null : copy(current);
    }

    @Override protected CompletableFuture<ChunkCopy> readStored(StoredContext context, int x, int z) {
        return context.storage.read(new ChunkPos(x, z)).thenApplyAsync(tag -> decode(context, tag), conversion);
    }

    private ChunkCopy decode(StoredContext context, Optional<CompoundTag> tag) {
        if (closed.get() || tag.isEmpty()) return null;
        CompoundTag upgraded = context.storage.upgradeChunkTag(tag.get(), -1, context.datafix.copy(),
                SharedConstants.getCurrentVersion().dataVersion().version());
        var parsed = SerializableChunkData.parse(context.height, context.factory, upgraded);
        if (parsed == null || !parsed.chunkStatus().isOrAfter(ChunkStatus.FULL)) return null;
        SectionCopy[] sections = new SectionCopy[context.height.getSectionsCount()];
        for (var section : parsed.sectionData()) {
            int index = section.y() - context.height.getMinSectionY();
            if (index >= 0 && index < sections.length) sections[index] = new SectionCopy(section.chunkSection());
        }
        return new ChunkCopy(sections, context.height.getMinSectionY());
    }

    private static ChunkCopy copy(LevelChunk chunk) {
        LevelChunkSection[] source = chunk.getSections();
        SectionCopy[] sections = new SectionCopy[source.length];
        for (int i = 0; i < source.length; i++) sections[i] = new SectionCopy(source[i].copy());
        return new ChunkCopy(sections, chunk.getMinSectionY());
    }


}
