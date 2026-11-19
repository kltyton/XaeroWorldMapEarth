package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapShadowStages;
import io.github.kltyton.xaeroearth.client.render.shaderpack.NativeMapShaderPackRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws map terrain into the isolated shadow attachments using Iris's shadow projection. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
abstract class NativeMapShadowMixin {
    @Unique private MapShadowStages earth$stages;
    @Shadow public static boolean ACTIVE;
    @Shadow public static int RESOLUTION;
    @Shadow public static Matrix4f MODELVIEW;
    @Shadow public static Matrix4f PROJECTION;
    @Shadow @Final private float sunPathRotation;
    @Shadow @Final private float intervalSize;
    @Shadow @Final private float halfPlaneLength;
    @Shadow @Final private float nearPlane;
    @Shadow @Final private float farPlane;
    @Shadow @Final private int resolution;
    @Shadow @Final private Float fov;
    @Shadow @Final private boolean shouldRenderTerrain;
    @Shadow @Final private boolean shouldRenderTranslucent;
    @Shadow public abstract void setupShadowViewport();
    @Invoker("generateMipmaps") public abstract void earth$generateMipmaps();

    @Shadow
    public static PoseStack createShadowModelView(float rotation, float interval, float near, float far) {
        throw new AssertionError();
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void earth$captureStages(@Coerce Object pipeline, @Coerce Object source, @Coerce Object directives,
                                     @Coerce Object targets, @Coerce Object composite, @Coerce Object uniforms,
                                     boolean samplers, CallbackInfo callback) {
        if (MapPackTarget.current() != null) earth$stages = new MapShadowStages(targets, composite);
    }

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true)
    private void earth$drawMap(@Coerce Object levelRenderer, Camera camera, CameraRenderState cameraState,
                               CallbackInfo callback) {
        var map = MapPackTarget.shadows();
        if (map == null) return;
        if (earth$stages == null) throw new IllegalStateException("Missing Iris map shadow stages");
        if (!earth$stages.enabled()) { callback.cancel(); return; }
        boolean active = ACTIVE;
        int previousResolution = RESOLUTION;
        var previousView = MODELVIEW;
        var previousProjection = PROJECTION;
        int distance = MapPackTarget.shadowDistance() * 16;
        ACTIVE = true;
        RESOLUTION = resolution;
        try {
            setupShadowViewport();
            float near = Mth.equal(nearPlane, -1.0f) ? -distance : nearPlane;
            float far = Mth.equal(farPlane, -1.0f) ? distance : farPlane;
            MODELVIEW = new Matrix4f(createShadowModelView(sunPathRotation, intervalSize, near, far)
                    .last().pose());
            PROJECTION = NativeMapShaderPackRenderer.shadowMatrix(halfPlaneLength, near, far, fov);
            MapPackTarget.shadowMatrices(MODELVIEW, NativeMapShaderPackRenderer.shadowUniformProjection(PROJECTION));
            if (shouldRenderTerrain) map.draw(MODELVIEW, PROJECTION, false);
            earth$stages.copyOpaqueDepth();
            if (shouldRenderTranslucent) map.draw(MODELVIEW, PROJECTION, true);
            earth$generateMipmaps();
            var target = MapPackTarget.current();
            com.mojang.blaze3d.opengl.GlStateManager._enableCull();
            com.mojang.blaze3d.opengl.GlStateManager._viewport(0, 0, target.width, target.height);
            earth$stages.composite();
        } finally {
            ACTIVE = active;
            RESOLUTION = previousResolution;
            MODELVIEW = previousView;
            PROJECTION = previousProjection;
            var target = MapPackTarget.current();
            com.mojang.blaze3d.opengl.GlStateManager._enableCull();
            com.mojang.blaze3d.opengl.GlStateManager._viewport(0, 0, target.width, target.height);
        }
        callback.cancel();
    }
}
