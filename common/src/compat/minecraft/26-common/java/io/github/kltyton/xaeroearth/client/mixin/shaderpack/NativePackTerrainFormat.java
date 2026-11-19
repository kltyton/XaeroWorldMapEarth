package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Shares the exact packed terrain format with the KUI draw call. */
@Mixin(value = KuiNativeTerrainRenderer.class, remap = false)
public interface NativePackTerrainFormat {
    @Accessor("FORMAT")
    static VertexFormat earth$format() { throw new AssertionError(); }
}
