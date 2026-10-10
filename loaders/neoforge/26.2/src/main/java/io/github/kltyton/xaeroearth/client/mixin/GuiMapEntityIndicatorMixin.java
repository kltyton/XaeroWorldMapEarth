package io.github.kltyton.xaeroearth.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.kltyton.xaeroearth.client.ui.NativeMapEntityIndicators;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.gui.GuiMap;
import xaero.map.graphics.ImprovedFramebuffer;

@Mixin(value = GuiMap.class, remap = false, priority = 900)
abstract class GuiMapEntityIndicatorMixin {
    @Shadow private Entity player;
    @Shadow private static ImprovedFramebuffer immediateRenderFBO;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void earth$beginModels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        NativeMapEntityIndicators.begin((GuiMap) (Object) this, graphics, ticks);
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/gui/GuiMap;drawArrowOnMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDFD)V"), require = 3)
    private void earth$actorModel(GuiMap map, PoseStack pose, VertexConsumer vertices, double x, double z,
                                 float yaw, double scale, Operation<Void> original) {
        if (!NativeMapEntityIndicators.replaceActor(map, player)) original.call(map, pose, vertices, x, z, yaw, scale);
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/gui/GuiMap;drawFarArrowOnMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDFD)V"), require = 3)
    private void earth$farActorModel(GuiMap map, PoseStack pose, VertexConsumer vertices, double x, double z,
                                    float yaw, double scale, Operation<Void> original) {
        if (!NativeMapEntityIndicators.replaceActor(map, player)) original.call(map, pose, vertices, x, z, yaw, scale);
    }

    @Inject(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lxaero/map/graphics/ImprovedFramebuffer;bindDefaultFramebuffer(Lnet/minecraft/client/Minecraft;)V"))
    private void earth$drawModels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        NativeMapEntityIndicators.draw((GuiMap) (Object) this, player, immediateRenderFBO);
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void earth$endModels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float ticks, CallbackInfo callback) {
        NativeMapEntityIndicators.end((GuiMap) (Object) this, graphics);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void earth$closeModels(CallbackInfo callback) {
        NativeMapEntityIndicators.close();
    }
}
