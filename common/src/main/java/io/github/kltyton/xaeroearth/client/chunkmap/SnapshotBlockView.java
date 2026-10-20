package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Exposes an immutable sampled chunk without reading the live world. */
abstract class SnapshotBlockView implements BlockGetter {
    protected final ChunkMapSnapshot snapshot;

    SnapshotBlockView(ChunkMapSnapshot snapshot) { this.snapshot = snapshot; }

    @Override public final BlockState getBlockState(BlockPos pos) {
        return snapshot.block(Math.floorDiv(pos.getX(), snapshot.step()) - snapshot.originX(),
                Math.floorDiv(pos.getY(), snapshot.verticalStep()),
                Math.floorDiv(pos.getZ(), snapshot.step()) - snapshot.originZ());
    }
    @Override public final FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public final BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public final int getHeight() { return (snapshot.maxY() - snapshot.minY()) * snapshot.verticalStep(); }
}
