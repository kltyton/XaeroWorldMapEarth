package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapInput;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.ui.NativeMapElementLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import xaero.map.element.MapElementRenderHandler;
import xaero.map.element.HoveredMapElementHolder;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;

/** Inserts KUI terrain before Xaero's original marker pass and skins the existing controls. */
@Mixin(value = GuiMap.class)
public abstract class GuiMapMixin {
    @Unique private int earth$mouseX, earth$mouseY;

    @Inject(method = "render", at = @At(value = "FIELD", remap = false, target = "Lxaero/map/gui/GuiMap;cameraZ:D", opcode = Opcodes.PUTFIELD, ordinal = 3, shift = At.Shift.AFTER))
    private void earth$pan(PoseStack graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (EarthClient.themed((GuiMap) (Object) this)) NativeMapInput.pan((GuiMap) (Object) this, x, y);
    }

    @Inject(method = "render", at = @At(value = "FIELD", remap = false, target = "Lxaero/map/gui/GuiMap;cameraDestinationAnimZ:Lxaero/map/animation/SlowingAnimation;", opcode = Opcodes.PUTFIELD, ordinal = 4, shift = At.Shift.AFTER))
    private void earth$panInertia(PoseStack graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (EarthClient.themed((GuiMap) (Object) this)) NativeMapInput.releasePan((GuiMap) (Object) this);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void earth$begin(PoseStack graphics, int x, int y, float ticks, CallbackInfo callback) {
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
    private void earth$mouseDown(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> callback) {
        if (NativeMapInput.mouseClicked((GuiMap) (Object) this, mouseX, mouseY, button)) callback.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void earth$mouseUp(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> callback) {
        if (NativeMapInput.mouseReleased((GuiMap) (Object) this, mouseX, mouseY, button)) callback.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void earth$keyDown(int key, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> callback) {
        if (NativeMapInput.keyPressed((GuiMap) (Object) this, key, modifiers)) callback.setReturnValue(true);
    }

    @Inject(method = "keyReleased", at = @At("HEAD"), cancellable = true)
    private void earth$keyUp(int key, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> callback) {
        if (NativeMapInput.keyReleased((GuiMap) (Object) this, key)) callback.setReturnValue(true);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", remap = false, target = "Lxaero/map/element/MapElementRenderHandler;render(Lxaero/map/gui/GuiMap;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;DDIIDDDDDFZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/client/Minecraft;F)Lxaero/map/element/HoveredMapElementHolder;"))
    private void earth$terrain(PoseStack graphics, int x, int y, float ticks, CallbackInfo callback) {
        net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        EarthClient.drawTerrain((GuiMap) (Object) this);
    }

    @Inject(method = "render", at = @At(value = "FIELD", remap = false, target = "Lxaero/map/gui/GuiMap;mouseBlockPosZ:I", opcode = Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER))
    private void earth$coordinates(PoseStack graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (!EarthClient.themed((GuiMap) (Object) this)) return;
        var point = NativeMapTerrain.pick(x, y);
        if (point == null) return;
        var access = (GuiMapAccess) this;
        access.earth$mouseX((int) Math.floor(point.x)); access.earth$mouseZ((int) Math.floor(point.z));
        access.earth$mouseY((int) Math.floor(point.y));
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", remap = false, target = "Lxaero/map/element/MapElementRenderHandler;render(Lxaero/map/gui/GuiMap;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;DDIIDDDDDFZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/client/Minecraft;F)Lxaero/map/element/HoveredMapElementHolder;"))
    private HoveredMapElementHolder<?, ?> earth$hover(MapElementRenderHandler handler, GuiMap map,
            PoseStack graphics, MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider textures, double cameraX, double cameraZ,
            int width, int height, double scale, double dimensionScale, double depth,
            double mouseX, double mouseZ, float elementScale, boolean shadow,
            HoveredMapElementHolder<?, ?> hovered, Minecraft minecraft, float partialTicks,
            Operation<HoveredMapElementHolder<?, ?>> original) {
        var point = NativeMapTerrain.pick(earth$mouseX, earth$mouseY);
        if (point != null) { mouseX = point.x; mouseZ = point.z; }
        boolean capture = EarthClient.themed(map) && NativeMapTerrain.active();
        if (capture) NativeMapElementLayer.begin(minecraft.getMainRenderTarget());
        try {
            return original.call(handler, map, graphics, buffers, textures, cameraX, cameraZ,
                    width, height, scale, dimensionScale, depth, mouseX, mouseZ,
                    elementScale, shadow, hovered, minecraft, partialTicks);
        } finally {
            if (capture) {
                try { buffers.endBatch(); }
                finally { NativeMapElementLayer.end(); }
            }
        }
    }

    @WrapOperation(method = "drawObjectOnMap", remap = false,
            at = @At(value = "INVOKE", remap = true, target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"))
    private void earth$nativeMarker(PoseStack pose, double x, double y, double z, Operation<Void> original) {
        if (EarthClient.themed((GuiMap) (Object) this) && NativeMapTerrain.active()) {
            var access = (GuiMapAccess) this;
            var point = NativeMapTerrain.project(access.earth$cameraX() + x, access.earth$cameraZ() + y);
            if (point != null && Float.isFinite(point.x)) {
                var window = Minecraft.getInstance().getWindow();
                x = (point.x - window.getGuiScaledWidth() / 2.0) * window.getGuiScale() / access.earth$scale();
                y = (point.y - window.getGuiScaledHeight() / 2.0) * window.getGuiScale() / access.earth$scale();
            }
        }
        original.call(pose, x, y, z);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void earth$skin(PoseStack graphics, int x, int y, float ticks, CallbackInfo callback) {
        if (EarthClient.themed((GuiMap) (Object) this)) NativeMapSkin.render((GuiMap) (Object) this, graphics, x, y);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void earth$close(CallbackInfo callback) {
        NativeMapInput.close((GuiMap) (Object) this);
        EarthClient.closeMap((GuiMap) (Object) this);
    }
}
