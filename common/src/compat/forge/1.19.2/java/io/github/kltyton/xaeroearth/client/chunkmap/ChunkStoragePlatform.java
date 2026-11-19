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
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
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
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeChunkSource.ChunkCopy;

final class ChunkStoragePlatform extends StoredChunkSource<ChunkStoragePlatform.StoredContext> {
    final Holder<Biome> fallbackBiome;
    int minY() { return level.getMinBuildHeight(); }
    int maxY() { return level.getMaxBuildHeight(); }


    static long key(int x, int z) { return ChunkPos.asLong(x, z); }

    private record SectionCopy(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) implements NativeChunkSource.Section {
        public BlockState getBlockState(int x, int y, int z) { return states.get(x, y, z); }
        public Holder<Biome> getNoiseBiome(int x, int y, int z) { return biomes.get(x, y, z); }
    }
    record StoredContext(MinecraftServer server, ChunkStorage storage, Path regions,
                                 LevelHeightAccessor height, ResourceKey<Level> dimension,
                                 Optional<ResourceKey<Codec<? extends ChunkGenerator>>> generatorType,
                                 DataFixer dataFixer, Registry<Biome> biomes, RegistryOps<Tag> registryOps) implements StoredChunkSource.Context { }
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());

    ChunkStoragePlatform(ClientLevel level, Executor conversion, AtomicBoolean closed) {
        super(level, conversion, closed);
        fallbackBiome = level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).getHolderOrThrow(Biomes.PLAINS);
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        initialize(server, () -> {
            var world = server.getLevel(level.dimension());
            if (world == null || closed.get()) return null;
            var storage = world.getChunkSource().chunkMap;
            Path regions = DimensionType.getStorageFolder(world.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
            Registry<Biome> biomes = world.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
            return new StoredContext(server, storage, regions,
                    LevelHeightAccessor.create(world.getMinBuildHeight(), world.getHeight()), world.dimension(),
                    world.getChunkSource().getGenerator().getTypeNameForDataFixer(), server.getFixerUpper(), biomes,
                    RegistryOps.create(NbtOps.INSTANCE, world.registryAccess()));
        });
    }


    boolean isLoaded(int x, int z) {
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
    }

    ChunkCopy loaded(int x, int z, int floor, int ceiling) {
        LevelChunk live = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (live != null) return copy(live,
                level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY), floor, ceiling);
        return null;
    }

    @Override protected ChunkCopy copyLoaded(StoredContext context, int x, int z, int floor, int ceiling) {
        var world = context.server.getLevel(level.dimension());
        if (world == null) return (ChunkCopy) null;
        LevelChunk current = world.getChunkSource().getChunkNow(x, z);
        return current == null ? null : copy(current, world.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY), floor, ceiling);
    }

    @Override protected CompletableFuture<ChunkCopy> readStored(StoredContext context, int x, int z, int floor, int ceiling) {
        return context.storage.read(new ChunkPos(x, z)).thenApplyAsync(tag -> decode(context, tag, floor, ceiling), conversion);
    }

    private ChunkCopy decode(StoredContext context, Optional<CompoundTag> tag, int floor, int ceiling) {
        if (closed.get() || tag.isEmpty()) return null;
        CompoundTag upgraded = tag.get();
        int targetVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (ChunkStorage.getVersion(upgraded) != targetVersion) {
            upgraded = upgraded.copy();
            ChunkStorage.injectDatafixingContext(upgraded, context.dimension, context.generatorType);
            upgraded = (CompoundTag) context.dataFixer.update(DataFixTypes.CHUNK.getType(),
                    new Dynamic<>(NbtOps.INSTANCE, upgraded), ChunkStorage.getVersion(upgraded), targetVersion).getValue();
        }
        if (!Optional.ofNullable(ChunkStatus.byName(upgraded.getString("Status")))
                .filter(status -> status.isOrAfter(ChunkStatus.FULL)).isPresent()) return null;
        var biomeCodec = PalettedContainer.codecRO(context.biomes.asHolderIdMap(),
                context.biomes.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, context.biomes.getHolderOrThrow(Biomes.PLAINS));
        var range = storedSectionRange(context.height.getMinSection(), context.height.getSectionsCount(), floor, ceiling,
                upgraded.getCompound("Heightmaps").getLongArray("WORLD_SURFACE"));
        var sectionTags = upgraded.getList("sections", Tag.TAG_COMPOUND);
        return decodeSections(sectionTags.size(), sectionTags::getCompound, section -> section.getByte("Y"),
                context.height.getSectionsCount(), context.height.getMinSection(), range, (section, biomeOnly) -> {
                    PalettedContainerRO<Holder<Biome>> biomes = section.contains("biomes", Tag.TAG_COMPOUND)
                            ? decodePalette(biomeCodec, context.registryOps, section.getCompound("biomes"))
                            : new PalettedContainer<>(context.biomes.asHolderIdMap(), context.biomes.getHolderOrThrow(Biomes.PLAINS),
                                    PalettedContainer.Strategy.SECTION_BIOMES);
                    if (biomeOnly) return NativeChunkSource.copyBiomes(biomes::get);
                    PalettedContainer<BlockState> states = section.contains("block_states", Tag.TAG_COMPOUND)
                            ? decodePalette(BLOCK_STATES, context.registryOps, section.getCompound("block_states"))
                            : new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                                    PalettedContainer.Strategy.SECTION_STATES);
                    return new SectionCopy(states, biomes);
                });
    }

    private static <T> T decodePalette(Codec<T> codec, DynamicOps<Tag> ops, CompoundTag tag) {
        DataResult<T> decoded = codec.parse(ops, tag);
        return decoded.getOrThrow(false, message -> new IllegalArgumentException("Invalid map chunk palette: " + message));
    }

    private static PalettedContainer<Holder<Biome>> copyBiomes(PalettedContainerRO<Holder<Biome>> source,
                                                               Registry<Biome> biomes) {
        if (source instanceof PalettedContainer<Holder<Biome>> container) return container.copy();
        Holder<Biome> fallback = biomes.getHolderOrThrow(Biomes.PLAINS);
        var copy = new PalettedContainer<>(biomes.asHolderIdMap(), fallback,
                PalettedContainer.Strategy.SECTION_BIOMES);
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            copy.set(x, y, z, source.get(x, y, z));
        return copy;
    }

    private static ChunkCopy copy(LevelChunk chunk, Registry<Biome> biomes, int floor, int ceiling) {
        LevelChunkSection[] source = chunk.getSections();
        NativeChunkSource.Section[] sections = new NativeChunkSource.Section[source.length];
        int minimum = chunk.getMinSection();
        var range = liveSectionRange(chunk, minimum, source.length, floor, ceiling);
        for (int y = minimum; y < minimum + source.length; y++) {
            int i = y - minimum;
            LevelChunkSection section = source[i];
            if (!range.contains(y)) { sections[i] = NativeChunkSource.copyBiomes(section::getNoiseBiome); continue; }
            sections[i] = new SectionCopy(section.getStates().copy(),
                    copyBiomes(section.getBiomes(), biomes));
        }
        return new ChunkCopy(sections, minimum, range.low() << 4, (range.high() + 1) << 4);
    }


}
