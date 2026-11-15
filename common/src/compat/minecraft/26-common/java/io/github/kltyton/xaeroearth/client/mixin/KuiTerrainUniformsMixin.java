package io.github.kltyton.xaeroearth.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.TerrainUniforms;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps KUI 1.3.1's terrain draw while moving uniform writes before the GPU render pass. */
@Mixin(value = KuiNativeTerrainRenderer.class, remap = false)
public abstract class KuiTerrainUniformsMixin {
    @Shadow @Final private DynamicUniformStorage<?> parameters;

    @Inject(method = "drawMeshes", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass"), allow = 1)
    private void earth$prepareUniforms(RenderTarget target, Matrix4f projection, boolean perspective,
                                      List<KuiNativeTerrainRenderer.Draw> draws, boolean transparent, CallbackInfo callback,
                                      @Share("terrainUniforms") LocalRef<ArrayDeque<GpuBufferSlice>> uniforms) {
        uniforms.set(TerrainUniforms.prepare(draws, transparent, parameters));
    }

    @Group(name = "terrainTransform", min = 1, max = 1)
    @WrapOperation(method = "drawMeshes", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice earth$transform261(DynamicUniforms storage, Matrix4fc modelView, Vector4fc tint,
                                             Vector3fc offset, Matrix4fc texture, Operation<GpuBufferSlice> original,
                                             @Share("terrainUniforms") LocalRef<ArrayDeque<GpuBufferSlice>> uniforms) {
        return uniforms.get().removeFirst();
    }

    @Group(name = "terrainTransform", min = 1, max = 1)
    @WrapOperation(method = "drawMeshes", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;Lorg/joml/Vector3f;Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice earth$transform262(DynamicUniforms storage, Matrix4f modelView, Vector4f tint,
                                             Vector3f offset, Matrix4f texture, Operation<GpuBufferSlice> original,
                                             @Share("terrainUniforms") LocalRef<ArrayDeque<GpuBufferSlice>> uniforms) {
        return uniforms.get().removeFirst();
    }

    @WrapOperation(method = "drawMeshes", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DynamicUniformStorage;writeUniform(Lnet/minecraft/client/renderer/DynamicUniformStorage$DynamicUniform;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice earth$mapParameters(DynamicUniformStorage<?> storage, DynamicUniformStorage.DynamicUniform value,
                                               Operation<GpuBufferSlice> original,
                                               @Share("terrainUniforms") LocalRef<ArrayDeque<GpuBufferSlice>> uniforms) {
        return uniforms.get().removeFirst();
    }
}
