package io.github.kltyton.xaeroearth.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import io.github.kltyton.kltytonui.render.MatrixBridge;
import io.github.kltyton.xaeroearth.client.render.navigation.NavigationRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies the completed world framebuffer before the renderer clears depth for the hand. */
@Mixin(LevelRenderer.class)
public abstract class NavigationWorldRenderMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void earth$drawNavigation(PoseStack pose, float partialTick, long finishTime, boolean blockOutline,
                                     Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                                     Matrix4f projection, CallbackInfo ci) {
        NavigationRenderer.draw(MatrixBridge.fromMojang(pose.last().pose()), MatrixBridge.fromMojang(projection), camera.getPosition());
    }
}
