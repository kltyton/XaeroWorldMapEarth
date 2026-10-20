package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

final class SnapshotSurfaceView extends SnapshotBlockView {
    SnapshotSurfaceView(ChunkMapSnapshot snapshot) { super(snapshot); }
    @Override public int getMinBuildHeight() { return snapshot.minY() * snapshot.verticalStep(); }
    int color(BlockState state, BlockPos pos, int x, int z) {
        MapColor mapColor = state.getMapColor(this, pos);
        int color = mapColor.col;
        var biome = snapshot.biome(x, z);
        if (biome != null) {
            if (mapColor == MapColor.GRASS) color = biome.grass();
            else if (mapColor == MapColor.PLANT) color = biome.foliage();
            else if (mapColor == MapColor.WATER) color = biome.water();
        }
        return color;
    }
}
