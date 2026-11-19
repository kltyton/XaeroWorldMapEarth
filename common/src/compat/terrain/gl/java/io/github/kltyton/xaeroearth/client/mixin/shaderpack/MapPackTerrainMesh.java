package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.GpuMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GpuMesh.class, remap = false)
public interface MapPackTerrainMesh {
    @Accessor("buffer") int earth$vertices();
    @Accessor("translucentStart") int earth$translucentStart();
    @Accessor("deleted") boolean earth$deleted();
}
