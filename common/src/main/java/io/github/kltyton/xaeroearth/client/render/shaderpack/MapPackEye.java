package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;

/** Eye-dependent Iris values in lightmap units at the map's own virtual camera. */
public record MapPackEye(int blockLight, int skyLight, int medium, BlockPos biomePosition, Holder<Biome> cachedBiome) {
    static MapPackEye at(MapCamera camera, ClientLevel level, ChunkTileSource source) {
        var offset = camera.eyeOffset();
        double y = camera.y() + offset.y;
        var position = new BlockPos(Mth.floor(camera.x() + offset.x), Mth.floor(y), Mth.floor(camera.z() + offset.z));
        // Surface cameras use an elevated eye to frame the map. Weather follows the focus
        // on the terrain, while the lightmap and medium still follow the virtual eye.
        var focus = camera.mode().equals("street") ? position
                : new BlockPos(Mth.floor(camera.x()), Mth.floor(camera.y()), Mth.floor(camera.z()));
        boolean loadedFocus = readable(level, focus);
        var biome = !loadedFocus && source != null ? source.cachedBiome(focus) : null;
        BlockPos biomePosition = loadedFocus || biome != null ? focus : null;
        if (!readable(level, position)) {
            return new MapPackEye(0, level.dimensionType().hasSkyLight() ? 15 * 16 : 0, 0, biomePosition, biome);
        }
        var fluid = level.getFluidState(position);
        int medium = 0;
        if (!fluid.isEmpty() && y < position.getY() + fluid.getHeight(level, position)) {
            if (fluid.is(FluidTags.WATER)) medium = 1;
            else if (fluid.is(FluidTags.LAVA)) medium = 2;
        } else if (level.getBlockState(position).is(Blocks.POWDER_SNOW)) {
            medium = 3;
        }
        return new MapPackEye(16 * level.getBrightness(LightLayer.BLOCK, position),
                16 * level.getBrightness(LightLayer.SKY, position), medium, biomePosition, biome);
    }

    private static boolean readable(ClientLevel level, BlockPos position) {
        return !level.isOutsideBuildHeight(position)
                && level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4);
    }
}
