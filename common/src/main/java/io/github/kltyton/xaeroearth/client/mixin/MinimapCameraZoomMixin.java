package io.github.kltyton.xaeroearth.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.kltyton.xaeroearth.client.ui.NativeMinimapTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import xaero.common.minimap.render.MinimapFBORenderer;

/** Gives Xaero's terrain, grid and markers the same zoom as Earth's minimap camera. */
@Pseudo
@Mixin(targets = "xaero.common.minimap.render.MinimapRenderer", remap = false)
public abstract class MinimapCameraZoomMixin {
    @ModifyExpressionValue(method = "renderMinimap", at = @At(value = "INVOKE",
            target = "Lxaero/common/minimap/MinimapProcessor;getMinimapZoom()D"))
    private double earth$cameraZoom(double zoom) {
        return (Object) this instanceof MinimapFBORenderer ? NativeMinimapTerrain.zoom(zoom) : zoom;
    }
}
