package io.github.kltyton.xaeroearth.client.integration.create;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class ContraptionBlockEntities {
    static BlockEntity load(ClientLevel level, BlockPos position, BlockState state, CompoundTag tag) {
        var data = tag.copy();
        // Create can serialize an update tag without the block entity type.
        if (!data.contains("id")) {
            if (!(state.getBlock() instanceof EntityBlock factory)) return null;
            var entity = factory.newBlockEntity(position, state);
            if (entity == null) return null;
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
            if (type == null) return null;
            data.putString("id", type.toString());
        }
        return BlockEntity.loadStatic(position, state, data, level.registryAccess());
    }

    private ContraptionBlockEntities() { }
}
