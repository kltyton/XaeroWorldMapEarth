package io.github.kltyton.xaeroearth.client.mixin.terrain;

import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.GpuMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Gives KUI ownership of the GPU-baked native buffer without a CPU round trip. */
@Mixin(value = GpuMesh.class, remap = false)
public interface GlTerrainMeshFactory {
    @Invoker("<init>")
    static GpuMesh earth$mesh(int array, int buffer, int opaque, int translucentFirst, int translucent,
                              long bytes, float minY, float maxY) {
        throw new AssertionError();
    }
}
