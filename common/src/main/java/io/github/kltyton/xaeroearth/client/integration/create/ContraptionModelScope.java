package io.github.kltyton.xaeroearth.client.integration.create;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Supplies a detached contraption's block entities to its KUI bake on the worker thread. */
public final class ContraptionModelScope implements AutoCloseable {
    private static final ThreadLocal<Map<BlockPos, BlockEntity>> CURRENT = new ThreadLocal<>();
    private final Map<BlockPos, BlockEntity> previous;

    private ContraptionModelScope(Map<BlockPos, BlockEntity> entities) {
        previous = CURRENT.get();
        CURRENT.set(entities);
    }

    public static ContraptionModelScope open(Map<BlockPos, BlockEntity> entities) {
        return entities.isEmpty() ? null : new ContraptionModelScope(entities);
    }

    public static BlockEntity entityAt(BlockPos position) {
        var entities = CURRENT.get();
        return entities == null ? null : entities.get(position);
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
