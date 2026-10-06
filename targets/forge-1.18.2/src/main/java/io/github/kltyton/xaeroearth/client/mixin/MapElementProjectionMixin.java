package io.github.kltyton.xaeroearth.client.mixin;

import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.element.MapElementRenderHandler;
import xaero.map.element.render.ElementRenderer;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import net.minecraft.client.renderer.MultiBufferSource;

/** Projects each original renderer's element without replacing its provider, reader or actions. */
@Mixin(value = MapElementRenderHandler.class, remap = false)
public abstract class MapElementProjectionMixin {
    @Unique private Vector3f earth$projected;

    @WrapOperation(method = "renderWithRenderer", at = @At(value = "INVOKE", target = "Lxaero/map/element/render/ElementReader;isOnScreen(Ljava/lang/Object;DDIIDDDLjava/lang/Object;F)Z"))
    private <E, C, R extends ElementRenderer<E, C, R>> boolean earth$viewport(
            ElementReader<E, C, R> reader, E element, double cameraX, double cameraZ,
            int screenWidth, int screenHeight, double elementScale, double elementX, double elementZ,
            C context, float partialTicks, Operation<Boolean> original,
            ElementRenderer<E, C, R> renderer, PoseStack graphics, ElementRenderInfo info,
            MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textures,
            int width, int height, double scale, double dimensionScale, boolean shadow, int index, int count) {
        if (NativeMapTerrain.active() && info.location == ElementRenderLocation.WORLD_MAP) {
            double x = reader.getRenderX(element, context, partialTicks) / dimensionScale;
            double z = reader.getRenderZ(element, context, partialTicks) / dimensionScale;
            var point = reader.hasYCoordinate()
                    ? NativeMapTerrain.project(x, reader.getRenderY(element, context, partialTicks), z)
                    : NativeMapTerrain.project(x, z);
            if (point != null && Float.isFinite(point.x)) {
                double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
                cameraX = x - (point.x * guiScale - width / 2.0) / info.scale;
                cameraZ = z - (point.y * guiScale - height / 2.0) / info.scale;
            }
        }
        return original.call(reader, element, cameraX, cameraZ, screenWidth, screenHeight,
                elementScale, elementX, elementZ, context, partialTicks);
    }

    @Inject(method = "transformAndRenderElement", at = @At("HEAD"), cancellable = true)
    private <E, C, R extends ElementRenderer<E, C, R>> void earth$project(
            ElementRenderer<E, C, R> renderer, E element, boolean hovered, PoseStack graphics, ElementRenderInfo info,
            MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textures,
            double scale, double dimensionScale, boolean shadow, int index, int count,
            CallbackInfoReturnable<Boolean> callback) {
        earth$projected = null;
        if (!NativeMapTerrain.active() || info.location != xaero.map.element.render.ElementRenderLocation.WORLD_MAP) return;
        var reader = renderer.getReader();
        double worldX = reader.getRenderX(element, renderer.getContext(), info.partialTicks) / dimensionScale;
        double worldZ = reader.getRenderZ(element, renderer.getContext(), info.partialTicks) / dimensionScale;
        earth$projected = reader.hasYCoordinate()
                ? NativeMapTerrain.project(worldX, reader.getRenderY(element, renderer.getContext(), info.partialTicks), worldZ)
                : NativeMapTerrain.project(worldX, worldZ);
        if (earth$projected != null && (!Float.isFinite(earth$projected.x) || earth$projected.z < -1 || earth$projected.z > 1)) {
            earth$projected = null;
            callback.setReturnValue(false);
        }
    }

    @WrapOperation(method = "transformAndRenderElement", at = @At(value = "INVOKE", target = "Lxaero/map/element/render/ElementReader;isHoveredOnMap(Lxaero/map/element/render/ElementRenderLocation;Ljava/lang/Object;DDDDDLjava/lang/Object;F)Z"))
    private <E, C, R extends ElementRenderer<E, C, R>> boolean earth$hover(
            ElementReader<E, C, R> reader, ElementRenderLocation location, E element,
            double mouseWorldX, double mouseWorldZ, double elementX, double elementZ, double hitScale,
            C context, float partialTicks, Operation<Boolean> original,
            ElementRenderer<E, C, R> renderer, E renderedElement, boolean hovered,
            PoseStack graphics, ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider textures, double scale, double dimensionScale,
            boolean shadow, int index, int count) {
        if (earth$projected != null) {
            var minecraft = Minecraft.getInstance();
            var window = minecraft.getWindow();
            double mouseX = minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
            double mouseY = minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
            double x = reader.getRenderX(element, context, partialTicks) / dimensionScale;
            double z = reader.getRenderZ(element, context, partialTicks) / dimensionScale;
            mouseWorldX = x + (mouseX - earth$projected.x) * window.getGuiScale() / info.scale;
            mouseWorldZ = z + (mouseY - earth$projected.y) * window.getGuiScale() / info.scale;
        }
        return original.call(reader, location, element, mouseWorldX, mouseWorldZ,
                elementX, elementZ, hitScale, context, partialTicks);
    }

    @ModifyVariable(method = "transformAndRenderElement", at = @At("STORE"), index = 17)
    private double earth$x(double original) {
        if (earth$projected == null) return original;
        var window = Minecraft.getInstance().getWindow();
        return (earth$projected.x - window.getGuiScaledWidth() / 2.0) * window.getGuiScale();
    }

    @ModifyVariable(method = "transformAndRenderElement", at = @At("STORE"), index = 19)
    private double earth$z(double original) {
        if (earth$projected == null) return original;
        var window = Minecraft.getInstance().getWindow();
        return (earth$projected.y - window.getGuiScaledHeight() / 2.0) * window.getGuiScale();
    }
}
