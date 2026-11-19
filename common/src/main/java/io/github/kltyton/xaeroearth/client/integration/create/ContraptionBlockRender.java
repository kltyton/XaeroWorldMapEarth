package io.github.kltyton.xaeroearth.client.integration.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** An animated block carried by the same interpolated pose as its contraption mesh. */
public record ContraptionBlockRender(BlockEntity entity, Vec3 origin, Quaternionf rotation, float tick) {
    public Vec3 worldCenter() {
        BlockPos pos = entity.getBlockPos();
        var local = rotation.transform(new Vector3f(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f));
        return origin.add(local.x(), local.y(), local.z());
    }

    public Vec3 localEye(Vec3 worldEye) {
        var offset = worldEye.subtract(origin);
        var local = rotation.transformInverse(new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
        return new Vec3(local.x(), local.y(), local.z());
    }

    public int packedLight(Level world) {
        Vec3 point = worldCenter();
        var pos = new BlockPos(net.minecraft.util.Mth.floor(point.x), net.minecraft.util.Mth.floor(point.y),
                net.minecraft.util.Mth.floor(point.z));
        int block = Math.max(world.getBrightness(LightLayer.BLOCK, pos), entity.getBlockState().getLightEmission());
        int sky = world.getBrightness(LightLayer.SKY, pos);
        return (block << 4) | (sky << 20);
    }
}
