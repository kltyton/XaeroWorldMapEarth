package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapShadowStages;
import io.github.kltyton.xaeroearth.client.render.shaderpack.GlMapShaderPackRenderer;
import net.minecraft.client.Camera;
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

/** Uses map geometry in the isolated Iris/Oculus shadow pass for the 1.20.1 API. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
abstract class OculusMapShadowMixin {
    @Unique private MapShadowStages earth$stages;
    @Shadow public static boolean ACTIVE;
    @Shadow public static Matrix4f MODELVIEW;
    @Shadow public static Matrix4f PROJECTION;
    @Shadow @Final private float sunPathRotation;
    @Shadow @Final private float intervalSize;
    @Shadow @Final private float halfPlaneLength;
    @Shadow @Final private float nearPlane;
    @Shadow @Final private float farPlane;
    @Shadow @Final private Float fov;
    @Shadow @Final private boolean shouldRenderTerrain;
    @Shadow @Final private boolean shouldRenderTranslucent;
    @Shadow public abstract void setupShadowViewport();
    @Invoker("generateMipmaps") public abstract void earth$generateMipmaps();

    @Shadow
    public static PoseStack createShadowModelView(float sunPathRotation, float intervalSize) { throw new AssertionError(); }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void earth$captureStages(@Coerce Object source, @Coerce Object directives, @Coerce Object targets,
                                     @Coerce Object composite, @Coerce Object uniforms, boolean samplers,
                                     CallbackInfo callback) {
        if (MapPackTarget.current() != null) earth$stages = new MapShadowStages(targets, composite);
    }

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, require = 0)
    private void earth$mapShadows(@Coerce Object levelRenderer, Camera camera, CallbackInfo callback) {
        var map = MapPackTarget.shadows();
        if (map == null) return;
        if (earth$stages == null) throw new IllegalStateException("Missing Oculus map shadow stages");
        if (!earth$stages.enabled()) { callback.cancel(); return; }
        var target = MapPackTarget.current();
        boolean wasActive = ACTIVE;
        Matrix4f oldView = MODELVIEW, oldProjection = PROJECTION;
        float distance = MapPackTarget.shadowDistance();
        ACTIVE = true;
        try {
            setupShadowViewport();
            MODELVIEW = new Matrix4f(createShadowModelView(sunPathRotation, intervalSize).last().pose());
            PROJECTION = GlMapShaderPackRenderer.shadowMatrix(halfPlaneLength,
                    nearPlane < 0 ? -distance : nearPlane, farPlane < 0 ? distance : farPlane, fov);
            MapPackTarget.shadowMatrices(MODELVIEW, PROJECTION);
            if (shouldRenderTerrain) map.draw(MODELVIEW, PROJECTION, false);
            earth$stages.copyOpaqueDepth();
            if (shouldRenderTranslucent) map.draw(MODELVIEW, PROJECTION, true);
            earth$generateMipmaps();
            target.bindWrite(true);
            RenderSystem.enableCull();
            earth$stages.composite();
        } finally {
            ACTIVE = wasActive;
            MODELVIEW = oldView;
            PROJECTION = oldProjection;
            target.bindWrite(true);
            RenderSystem.enableCull();
        }
        callback.cancel();
    }
}
