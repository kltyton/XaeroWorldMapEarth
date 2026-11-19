package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.mojang.blaze3d.buffers.GpuBuffer;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.GpuMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Keeps GPU ownership and lifecycle with KUI for compact indexed geometry. */
@Mixin(value = GpuMesh.class, remap = false)
public interface NativeTerrainMeshFactory {
    @Invoker("<init>")
    public static GpuMesh earth$mesh(GpuBuffer vertices, int opaque, int translucentFirst, int translucent,
                                     long bytes, float minY, float maxY) { throw new AssertionError(); }
}
