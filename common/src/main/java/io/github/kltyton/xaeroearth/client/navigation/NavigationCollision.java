package io.github.kltyton.xaeroearth.client.navigation;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Tests the continuous sweep of a movement footprint against block collision shapes. */
final class NavigationCollision {
    static boolean clear(ClientLevel level, Entity mover, AABB footprint, Vec3 from, Vec3 to) {
        // A long diagonal should check the chunks it crosses, not every chunk in its enclosing rectangle.
        int parts = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 6));
        Vec3 start = from;
        for (int i = 1; i <= parts; i++) {
            Vec3 end = i == parts ? to : from.lerp(to, i / (double) parts);
            if (!clearSpan(level, mover, footprint, start, end)) return false;
            start = end;
        }
        return true;
    }

    private static boolean clearSpan(ClientLevel level, Entity mover, AABB footprint, Vec3 from, Vec3 to) {
        AABB start = footprint.move(from);
        AABB sweep = start.minmax(footprint.move(to));
        if (!loaded(level, sweep)) return false;
        for (var shape : level.getBlockCollisions(mover, sweep)) for (AABB block : shape.toAabbs()) {
            AABB occupied = new AABB(block.minX - footprint.maxX, block.minY - footprint.maxY,
                    block.minZ - footprint.maxZ, block.maxX - footprint.minX,
                    block.maxY - footprint.minY, block.maxZ - footprint.minZ);
            if (occupied.contains(from) || occupied.contains(to) || occupied.clip(from, to).isPresent()) return false;
        }
        return true;
    }

    static boolean loaded(ClientLevel level, AABB box) {
        int y = Mth.floor(box.minY);
        if (level.isOutsideBuildHeight(new BlockPos(Mth.floor(box.minX), y, Mth.floor(box.minZ)))
                || !NavigationPlatform.withinBorder(level, box)) return false;
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++)
                if (!level.getChunkSource().hasChunk(x, z)) return false;
        return true;
    }

    private NavigationCollision() {}
}
