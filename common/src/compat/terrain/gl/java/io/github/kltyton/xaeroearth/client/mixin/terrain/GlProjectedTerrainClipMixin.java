package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.mojang.blaze3d.platform.GlStateManager;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedDrawCoverage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the projected cell clip after KUI installs its framebuffer state. */
@Mixin(value = KuiNativeTerrainRenderer.class, remap = false)
abstract class GlProjectedTerrainClipMixin {
    @Inject(method = "drawMeshes", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lio/github/kltyton/kltytonui/chunkmap/KuiNativeTerrainRenderer;prepareState(Z)V"))
    private void earth$clipToMissingImage(CallbackInfo callback) {
        var clip = ProjectedDrawCoverage.currentClip();
        if (clip != null) {
            GlStateManager._scissorBox(clip.x(), clip.y(), clip.width(), clip.height());
            GlStateManager._enableScissorTest();
        }
    }
}
