package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapInput;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.ui.NativeMapElementLayer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.map.element.HoveredMapElementHolder;
import xaero.map.element.MapElementRenderHandler;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/** Inserts AUI terrain before Xaero's original marker pass and skins the existing controls. */
@Mixin(value = GuiMap.class, remap = false)
public abstract class GuiMapMixin {
    @Unique private int earth$mouseX, earth$mouseY;

    @Inject(method = "extractRenderState", at = @At(value = "FIELD", target = "Lxaero/map/gui/GuiMap;cameraZ:D", opcode = Opcodes.PUTFIELD, ordinal = 3, shift = At.Shift.AFTER))
    private void earth$pan(GuiGraphicsExtractor graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (EarthClient.themed((GuiMap) (Object) this)) NativeMapInput.pan((GuiMap) (Object) this, x, y);
    }

    @Inject(method = "extractRenderState", at = @At(value = "FIELD", target = "Lxaero/map/gui/GuiMap;cameraDestinationAnimZ:Lxaero/map/animation/SlowingAnimation;", opcode = Opcodes.PUTFIELD, ordinal = 2, shift = At.Shift.AFTER))
    private void earth$panInertia(GuiGraphicsExtractor graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (EarthClient.themed((GuiMap) (Object) this)) NativeMapInput.releasePan((GuiMap) (Object) this);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void earth$begin(GuiGraphicsExtractor graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (NativeMapSkin.settingsBackground()) {
            NativeMapSkin.renderSettingsBackground(graphics);
            callback.cancel();
            return;
        }
        earth$mouseX = x; earth$mouseY = y;
        NativeMapInput.mouseMoved((GuiMap) (Object) this, x, y);
        EarthClient.beginFrame((GuiMap) (Object) this);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void earth$mouseDown(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> callback) {
        if (NativeMapInput.mouseClicked((GuiMap) (Object) this, event)) callback.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void earth$mouseUp(MouseButtonEvent event, CallbackInfoReturnable<Boolean> callback) {
        if (NativeMapInput.mouseReleased((GuiMap) (Object) this, event)) callback.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void earth$keyDown(KeyEvent event, CallbackInfoReturnable<Boolean> callback) {
        if (NativeMapInput.keyPressed((GuiMap) (Object) this, event)) callback.setReturnValue(true);
    }

    @Inject(method = "keyReleased", at = @At("HEAD"), cancellable = true)
    private void earth$keyUp(KeyEvent event, CallbackInfoReturnable<Boolean> callback) {
        if (NativeMapInput.keyReleased((GuiMap) (Object) this, event)) callback.setReturnValue(true);
    }

    @Inject(method = "extractRenderState", at = @At(value = "FIELD", target = "Lxaero/map/gui/GuiMap;mouseBlockPosZ:I", opcode = Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER))
    private void earth$coordinates(GuiGraphicsExtractor graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (!EarthClient.themed((GuiMap) (Object) this)) return;
        var point = NativeMapTerrain.pick(x, y);
        if (point == null) return;
        var access = (GuiMapAccess) this;
        access.earth$mouseX((int) Math.floor(point.x)); access.earth$mouseZ((int) Math.floor(point.z));
        access.earth$mouseY((int) Math.floor(point.y));
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/element/MapElementRenderHandler;render(Lxaero/map/gui/GuiMap;Lxaero/lib/client/graphics/XaeroBufferProvider;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;DDIIDDDDDFZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/client/Minecraft;F)Lxaero/map/element/HoveredMapElementHolder;"))
    private HoveredMapElementHolder<?, ?> earth$elements(MapElementRenderHandler handler, GuiMap map,
            XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider textures,
            double cameraX, double cameraZ, int width, int height, double scale,
            double dimensionScale, double depth, double mouseX, double mouseZ,
            float elementScale, boolean shadow, HoveredMapElementHolder<?, ?> hovered,
            Minecraft minecraft, float partialTicks, Operation<HoveredMapElementHolder<?, ?>> original) {
        if (EarthClient.themed(map)) buffers.endBatch();
        EarthClient.drawTerrain(map);
        var point = NativeMapTerrain.pick(earth$mouseX, earth$mouseY);
        if (point != null) { mouseX = point.x; mouseZ = point.z; }
        boolean capture = EarthClient.themed(map) && NativeMapTerrain.active();
        if (capture) NativeMapElementLayer.begin(minecraft.gameRenderer.mainRenderTarget());
        try {
            return original.call(handler, map, buffers, textures, cameraX, cameraZ,
                    width, height, scale, dimensionScale, depth, mouseX, mouseZ,
                    elementScale, shadow, hovered, minecraft, partialTicks);
        } finally {
            if (capture) {
                try { buffers.endBatch(); }
                finally { NativeMapElementLayer.end(); }
            }
        }
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lxaero/lib/client/graphics/XaeroBufferProvider;endBatch()V", ordinal = 2))
    private void earth$remainingElements(XaeroBufferProvider buffers, Operation<Void> original) {
        boolean capture = NativeMapElementLayer.pending();
        if (capture) NativeMapElementLayer.resume();
        try { original.call(buffers); }
        finally { if (capture) NativeMapElementLayer.end(); }
    }

    @ModifyArgs(method = "drawObjectOnMap", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"))
    private void earth$nativeMarker(Args args) {
        if (!EarthClient.themed((GuiMap) (Object) this) || !NativeMapTerrain.active()) return;
        var access = (GuiMapAccess) this;
        var point = NativeMapTerrain.project(access.earth$cameraX() + (double) args.get(0),
                access.earth$cameraZ() + (double) args.get(1));
        if (point == null || !Float.isFinite(point.x)) return;
        var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        args.set(0, (point.x - window.getGuiScaledWidth() / 2.0) * window.getGuiScale() / access.earth$scale());
        args.set(1, (point.y - window.getGuiScaledHeight() / 2.0) * window.getGuiScale() / access.earth$scale());
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void earth$skin(GuiGraphicsExtractor graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (EarthClient.themed((GuiMap) (Object) this)) NativeMapSkin.render((GuiMap) (Object) this, graphics, x, y);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void earth$close(CallbackInfo callback) {
        NativeMapInput.close((GuiMap) (Object) this);
        EarthClient.closeMap((GuiMap) (Object) this);
    }
}
