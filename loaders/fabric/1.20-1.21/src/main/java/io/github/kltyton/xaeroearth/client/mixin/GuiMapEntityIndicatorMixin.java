package io.github.kltyton.xaeroearth.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.kltyton.xaeroearth.client.ui.NativeMapEntityIndicators;
import io.github.kltyton.xaeroearth.client.ui.NativeMapElementLayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.gui.GuiMap;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(value = GuiMap.class, priority = 900)
abstract class GuiMapEntityIndicatorMixin {
    @Shadow(remap = false) private Entity player;

    @Inject(method = "render", at = @At("HEAD"))
    private void earth$beginModels(GuiGraphics graphics, int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        NativeMapEntityIndicators.begin((GuiMap) (Object) this, graphics, ticks);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", remap = false, desc = @org.spongepowered.asm.mixin.injection.Desc(owner = xaero.map.gui.GuiMap.class, value = "drawArrowOnMap", args = {com.mojang.blaze3d.vertex.PoseStack.class, com.mojang.blaze3d.vertex.VertexConsumer.class, double.class, double.class, float.class, double.class}, ret = void.class)), require = 3)
    private void earth$actorModel(GuiMap map, PoseStack pose, VertexConsumer vertices, double x, double z,
                                 float yaw, double scale, Operation<Void> original) {
        if (!NativeMapEntityIndicators.replaceActor(map, player)) original.call(map, pose, vertices, x, z, yaw, scale);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", remap = false, desc = @org.spongepowered.asm.mixin.injection.Desc(owner = xaero.map.gui.GuiMap.class, value = "drawFarArrowOnMap", args = {com.mojang.blaze3d.vertex.PoseStack.class, com.mojang.blaze3d.vertex.VertexConsumer.class, double.class, double.class, float.class, double.class}, ret = void.class)), require = 3)
    private void earth$farActorModel(GuiMap map, PoseStack pose, VertexConsumer vertices, double x, double z,
                                    float yaw, double scale, Operation<Void> original) {
        if (!NativeMapEntityIndicators.replaceActor(map, player)) original.call(map, pose, vertices, x, z, yaw, scale);
    }

    @Inject(method = "render", slice = @Slice(from = @At(value = "INVOKE", remap = false,
            desc = @org.spongepowered.asm.mixin.injection.Desc(owner = xaero.map.gui.GuiMap.class, value = "drawArrowOnMap", args = {com.mojang.blaze3d.vertex.PoseStack.class, com.mojang.blaze3d.vertex.VertexConsumer.class, double.class, double.class, float.class, double.class}, ret = void.class), ordinal = 2)),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
                    ordinal = 0, shift = At.Shift.AFTER))
    private void earth$drawModels(GuiGraphics graphics, int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        NativeMapEntityIndicators.draw((GuiMap) (Object) this, player, Minecraft.getInstance().getMainRenderTarget());
    }

    @WrapOperation(method = "render", slice = @Slice(from = @At(value = "INVOKE", remap = false,
            desc = @org.spongepowered.asm.mixin.injection.Desc(owner = xaero.map.gui.GuiMap.class, value = "drawArrowOnMap", args = {com.mojang.blaze3d.vertex.PoseStack.class, com.mojang.blaze3d.vertex.VertexConsumer.class, double.class, double.class, float.class, double.class}, ret = void.class), ordinal = 2)),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 0))
    private void earth$remainingElements(MultiBufferSource.BufferSource buffers, Operation<Void> original) {
        boolean capture = NativeMapElementLayer.pending();
        if (capture) NativeMapElementLayer.resume();
        try { original.call(buffers); }
        finally { if (capture) NativeMapElementLayer.end(); }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void earth$endModels(GuiGraphics graphics, int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        NativeMapEntityIndicators.end((GuiMap) (Object) this, graphics);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void earth$closeModels(CallbackInfo callback) {
        NativeMapEntityIndicators.close();
    }
}
