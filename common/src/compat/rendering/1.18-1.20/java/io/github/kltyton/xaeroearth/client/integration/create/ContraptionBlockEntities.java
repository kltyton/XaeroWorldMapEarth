package io.github.kltyton.xaeroearth.client.integration.create;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class ContraptionBlockEntities {
    static BlockEntity load(ClientLevel level, BlockPos position, BlockState state, CompoundTag tag) {
        return BlockEntity.loadStatic(position, state, tag.copy());
    }

    private ContraptionBlockEntities() { }
}
