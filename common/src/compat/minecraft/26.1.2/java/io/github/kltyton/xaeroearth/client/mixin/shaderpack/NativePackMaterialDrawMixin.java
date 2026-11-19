package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.kltyton.xaeroearth.client.render.shaderpack.NativeMapShaderPackRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Gives the material pass the bound Iris program before the KUI map draw reaches OpenGL. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder", remap = false)
abstract class NativePackMaterialDrawMixin {
    @WrapOperation(method = "executeDraw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;drawFromBuffers(Lcom/mojang/blaze3d/opengl/GlRenderPass;IIILcom/mojang/blaze3d/vertex/VertexFormat$IndexType;Lcom/mojang/blaze3d/opengl/GlRenderPipeline;I)V"))
    private void earth$mapMaterial(@Coerce Object encoder, @Coerce Object pass, int baseVertex, int first,
                                   int count, VertexFormat.IndexType index, GlRenderPipeline pipeline,
                                   int instances, Operation<Void> original) {
        NativeMapShaderPackRenderer.submitMaterial(() ->
                original.call(encoder, pass, baseVertex, first, count, index, pipeline, instances));
    }
}
