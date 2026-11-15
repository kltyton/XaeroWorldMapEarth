package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.Draw;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.client.renderer.DynamicUniformStorage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Uploads the uniforms for the KUI terrain draw before its render pass opens. */
public final class TerrainUniforms {
    private TerrainUniforms() {}

    public static ArrayDeque<GpuBufferSlice> prepare(List<Draw> draws, boolean transparent,
                                                     DynamicUniformStorage<?> mapParameters) {
        var result = new ArrayDeque<GpuBufferSlice>(draws.size() * 2);
        var transforms = RenderSystem.getDynamicUniforms();
        var tint = new Vector4f(1, 1, 1, 1);
        var offset = new Vector3f();
        var textureMatrix = new Matrix4f();
        // Storage accepts any DynamicUniform and keeps KUI's buffer alignment and lifecycle.
        @SuppressWarnings("unchecked")
        var parameters = (DynamicUniformStorage<DynamicUniformStorage.DynamicUniform>) mapParameters;
        for (Draw draw : draws) {
            var mesh = draw.mesh();
            if ((transparent ? mesh.translucentCount() : mesh.opaqueCount()) == 0) continue;
            result.add(transforms.writeTransform(draw.modelView(), tint, offset, textureMatrix));
            result.add(parameters.writeUniform(new MapParameters(draw.chunkMask(), draw.skyLightFactor())));
        }
        return result;
    }

    private record MapParameters(float chunkMask, float brightness) implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer buffer) {
            buffer.putFloat(chunkMask).putFloat(brightness).putLong(0);
        }
    }
}
