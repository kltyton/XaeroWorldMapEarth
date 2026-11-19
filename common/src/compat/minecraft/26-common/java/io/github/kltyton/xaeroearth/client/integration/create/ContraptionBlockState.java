package io.github.kltyton.xaeroearth.client.integration.create;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

final class ContraptionBlockState {
    static BlockState state(Object info) { return ((StructureBlockInfo) info).state(); }
    static CompoundTag data(Object info) { return ((StructureBlockInfo) info).nbt(); }
    private ContraptionBlockState() { }
}
