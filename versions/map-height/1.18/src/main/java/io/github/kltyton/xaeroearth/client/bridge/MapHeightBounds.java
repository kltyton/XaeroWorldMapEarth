package io.github.kltyton.xaeroearth.client.bridge;

import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import net.minecraft.world.level.dimension.DimensionType;
import xaero.map.MapProcessor;

record MapHeightBounds(double min, double max) {
    static MapHeightBounds resolve(MapProcessor processor, NativeTileSession models, int terrainMinY, int terrainMaxY) {
        var dimension = processor.getMapWorld().getCurrentDimension();
        var world = processor.getWorld();
        var type = world != null && world.dimension().equals(dimension.getDimId()) ? world.dimensionType() : null;
        double minY = models != null ? models.minY() : type != null ? type.minY()
                : terrainMinY != Integer.MAX_VALUE ? terrainMinY : DimensionType.MIN_Y;
        double maxY = models != null ? models.maxY() : type != null ? type.minY() + type.height()
                : terrainMaxY != Integer.MIN_VALUE ? terrainMaxY + 1 : DimensionType.MAX_Y + 1;
        return new MapHeightBounds(minY, maxY);
    }
}
