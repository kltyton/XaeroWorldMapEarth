package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMinimapTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.joml.Vector3f;
import xaero.lib.client.graphics.XaeroBufferProvider;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Reprojects Xaero's native in-map marker positions onto the Earth terrain. */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler", remap = false)
public abstract class MinimapMarkerProjectionMixin {
    @Unique private Vector3f earth$point;

    @Inject(method = "transformAndRenderForRenderer", at = @At("HEAD"))
    private void earth$project(Object element, double x, double y, double z, MinimapElementRenderer<?, ?> renderer,
                               Object context, int index, double depth, MinimapElementRenderInfo info,
                               XaeroBufferProvider buffers, CallbackInfoReturnable<Boolean> callback) {
        earth$point = NativeMinimapTerrain.projectMarker(x, y, z);
    }

    @ModifyVariable(method = "transformAndRenderForRenderer", at = @At("STORE"), index = 25)
    private double earth$markerX(double original) {
        var point = earth$point;
        return point == null ? original : Float.isFinite(point.x) ? point.x : 100000;
    }

    @ModifyVariable(method = "transformAndRenderForRenderer", at = @At("STORE"), index = 27)
    private double earth$markerY(double original) {
        var point = earth$point;
        return point == null ? original : Float.isFinite(point.y) ? point.y : 100000;
    }
}
