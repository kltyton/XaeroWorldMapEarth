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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeChunkSource.ChunkCopy;

final class ChunkStoragePlatform implements AutoCloseable {
    private final ClientLevel level;
    private final Executor conversion;
    private final AtomicBoolean closed;
    final Holder<Biome> fallbackBiome;
    private final CompletableFuture<StoredContext> stored;
    int minY() { return level.getMinBuildHeight(); }
    int maxY() { return level.getMaxBuildHeight(); }


    static long key(int x, int z) { return ChunkPos.asLong(x, z); }

    private record SectionCopy(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) implements NativeChunkSource.Section {
        public BlockState getBlockState(int x, int y, int z) { return states.get(x, y, z); }
        public Holder<Biome> getNoiseBiome(int x, int y, int z) { return biomes.get(x, y, z); }
    }
    private record StoredContext(MinecraftServer server, ChunkStorage storage, Path regions,
                                 LevelHeightAccessor height, ResourceKey<Level> dimension,
                                 Optional<ResourceKey<MapCodec<? extends ChunkGenerator>>> generatorType,
                                 DataFixer dataFixer, Registry<Biome> biomes, RegistryOps<Tag> registryOps) { }
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());

    ChunkStoragePlatform(ClientLevel level, Executor conversion, AtomicBoolean closed) {
        this.level = level; this.conversion = conversion; this.closed = closed;
        fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        stored = server == null ? CompletableFuture.completedFuture(null) : server.submit(() -> {
            var world = server.getLevel(level.dimension());
            if (world == null || closed.get()) return null;
            var storage = world.getChunkSource().chunkMap;
            Path regions = DimensionType.getStorageFolder(world.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
            Registry<Biome> biomes = world.registryAccess().registryOrThrow(Registries.BIOME);
            return new StoredContext(server, storage, regions,
                    LevelHeightAccessor.create(world.getMinBuildHeight(), world.getHeight()), world.dimension(),
                    world.getChunkSource().getGenerator().getTypeNameForDataFixer(), server.getFixerUpper(), biomes,
                    world.registryAccess().createSerializationContext(NbtOps.INSTANCE));
        });
    }


    boolean isLoaded(int x, int z) {
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
    }

    ChunkCopy loaded(int x, int z) {
        LevelChunk live = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (live != null) return copy(live,
                level.registryAccess().registryOrThrow(Registries.BIOME));
        return null;
    }

    CompletableFuture<ChunkCopy> read(int x, int z) {
        return stored.thenCompose(context -> {
            if (context == null || closed.get() || context.server.isStopped()) return CompletableFuture.completedFuture(null);
            return context.server.submit(() -> {
                if (closed.get() || context.server.isStopped()) return (ChunkCopy) null;
                var world = context.server.getLevel(level.dimension());
                if (world == null) return (ChunkCopy) null;
                LevelChunk current = world.getChunkSource().getChunkNow(x, z);
                return current == null ? null : copy(current, world.registryAccess().registryOrThrow(Registries.BIOME));
            }).thenComposeAsync(current -> {
                if (current != null) return CompletableFuture.completedFuture(current);
                if (closed.get() || context.server.isStopped()) return CompletableFuture.completedFuture(null);
                Path region = context.regions.resolve("r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
                if (!Files.isRegularFile(region)) return CompletableFuture.<ChunkCopy>completedFuture(null);
                return context.storage.read(new ChunkPos(x, z)).thenApplyAsync(tag -> decode(context, tag), conversion);
            }, conversion);
        });
    }

    private ChunkCopy decode(StoredContext context, Optional<CompoundTag> tag) {
        if (closed.get() || tag.isEmpty()) return null;
        CompoundTag upgraded = tag.get().copy();
        ChunkStorage.injectDatafixingContext(upgraded, context.dimension, context.generatorType);
        upgraded = DataFixTypes.CHUNK.updateToCurrentVersion(context.dataFixer, upgraded,
                ChunkStorage.getVersion(upgraded));
        if (!Optional.ofNullable(ChunkStatus.byName(upgraded.getString("Status")))
                .filter(status -> status.isOrAfter(ChunkStatus.FULL)).isPresent()) return null;
        SectionCopy[] sections = new SectionCopy[context.height.getSectionsCount()];
        var biomeCodec = PalettedContainer.codecRO(context.biomes.asHolderIdMap(),
                context.biomes.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, context.biomes.getHolderOrThrow(Biomes.PLAINS));
        var sectionTags = upgraded.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sectionTags.size(); i++) {
            CompoundTag section = sectionTags.getCompound(i);
            int index = section.getByte("Y") - context.height.getMinSection();
            if (index < 0 || index >= sections.length) continue;
            PalettedContainer<BlockState> states = section.contains("block_states", Tag.TAG_COMPOUND)
                    ? decodePalette(BLOCK_STATES, context.registryOps, section.getCompound("block_states"))
                    : new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                            PalettedContainer.Strategy.SECTION_STATES);
            PalettedContainerRO<Holder<Biome>> biomes = section.contains("biomes", Tag.TAG_COMPOUND)
                    ? decodePalette(biomeCodec, context.registryOps, section.getCompound("biomes"))
                    : new PalettedContainer<>(context.biomes.asHolderIdMap(), context.biomes.getHolderOrThrow(Biomes.PLAINS),
                            PalettedContainer.Strategy.SECTION_BIOMES);
            sections[index] = new SectionCopy(states, biomes);
        }
        return new ChunkCopy(sections, context.height.getMinSection());
    }

    private static <T> T decodePalette(Codec<T> codec, DynamicOps<Tag> ops, CompoundTag tag) {
        DataResult<T> decoded = codec.parse(ops, tag);
        return decoded.getOrThrow(message -> new IllegalArgumentException("Invalid map chunk palette: " + message));
    }

    private static PalettedContainer<Holder<Biome>> copyBiomes(PalettedContainerRO<Holder<Biome>> source,
                                                               Registry<Biome> biomes) {
        Holder<Biome> fallback = biomes.getHolderOrThrow(Biomes.PLAINS);
        var copy = new PalettedContainer<>(biomes.asHolderIdMap(), fallback,
                PalettedContainer.Strategy.SECTION_BIOMES);
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            copy.set(x, y, z, source.get(x, y, z));
        return copy;
    }

    private static ChunkCopy copy(LevelChunk chunk, Registry<Biome> biomes) {
        LevelChunkSection[] source = chunk.getSections();
        SectionCopy[] sections = new SectionCopy[source.length];
        for (int i = 0; i < source.length; i++) {
            LevelChunkSection section = source[i];
            sections[i] = new SectionCopy(section.getStates().copy(),
                    copyBiomes(section.getBiomes(), biomes));
        }
        return new ChunkCopy(sections, chunk.getMinSection());
    }


    @Override public void close() { stored.cancel(false); }
}
