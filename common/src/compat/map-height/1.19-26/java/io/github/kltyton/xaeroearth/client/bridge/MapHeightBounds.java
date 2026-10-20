package io.github.kltyton.xaeroearth.client.bridge;

import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import net.minecraft.world.level.dimension.DimensionType;
import xaero.map.MapProcessor;

record MapHeightBounds(double min, double max) {
    static MapHeightBounds resolve(MapProcessor processor, NativeTileSession models, int terrainMinY, int terrainMaxY) {
        var dimension = processor.getMapWorld().getCurrentDimension();
        var registry = processor.getWorldDimensionTypeRegistry();
        var type = registry != null && !dimension.isUsingUnknownDimensionType(registry)
                ? dimension.getDimensionType(registry) : null;
        double minY = models != null ? models.minY() : type != null ? type.minY() : DimensionType.MIN_Y;
        double maxY = models != null ? models.maxY() : type != null ? type.minY() + type.height() : DimensionType.MAX_Y + 1;
        return new MapHeightBounds(minY, maxY);
    }
}
