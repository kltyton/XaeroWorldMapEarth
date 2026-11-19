package io.github.kltyton.xaeroearth.client.mixin.terrain;

import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.GpuMesh;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.IndexedDetailedMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Only buffers explicitly created from Earth faces use indexed draws. */
@Mixin(value = GpuMesh.class, remap = false)
abstract class IndexedTerrainMeshMixin implements IndexedDetailedMesh {
    @Unique private boolean earth$indexed;
    @Override public boolean earth$indexed() { return earth$indexed; }
    @Override public void earth$enableIndices() { earth$indexed = true; }
}
