package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapShadowStages;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
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

/** Uses Iris 1.21.1's near and far shadow projection parameters for Earth terrain. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
abstract class Iris121MapShadowMixin {
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
    public static PoseStack createShadowModelView(float rotation, float interval, float near, float far) {
        throw new AssertionError();
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void earth$captureStages(@Coerce Object pipeline, @Coerce Object source, @Coerce Object directives,
                                     @Coerce Object targets, @Coerce Object composite, @Coerce Object uniforms,
                                     boolean samplers, CallbackInfo callback) {
        if (MapPackTarget.current() != null) earth$stages = new MapShadowStages(targets, composite);
    }

    @Unique private static MethodHandle earth$ortho;
    @Unique private static MethodHandle earth$perspective;

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true)
    private void earth$mapShadows(@Coerce Object levelRenderer, Camera camera, CallbackInfo callback) throws Throwable {
        var pass = MapPackTarget.shadows();
        if (pass == null) return;
        if (earth$stages == null) throw new IllegalStateException("Missing Iris map shadow stages");
        if (!earth$stages.enabled()) { callback.cancel(); return; }
        if (earth$ortho == null) {
            var type = Class.forName("net.irisshaders.iris.shadows.ShadowMatrices");
            var lookup = MethodHandles.publicLookup();
            earth$ortho = lookup.unreflect(type.getMethod("createOrthoMatrix", float.class, float.class, float.class));
            earth$perspective = lookup.unreflect(type.getMethod("createPerspectiveMatrix", float.class));
        }
        var oldView = MODELVIEW;
        var oldProjection = PROJECTION;
        var target = MapPackTarget.current();
        boolean oldActive = ACTIVE;
        int distance = MapPackTarget.shadowDistance() * 16;
        ACTIVE = true;
        try {
            float near = net.minecraft.util.Mth.equal(nearPlane, -1.0f) ? -distance : nearPlane;
            float far = net.minecraft.util.Mth.equal(farPlane, -1.0f) ? distance : farPlane;
            MODELVIEW = new Matrix4f(createShadowModelView(sunPathRotation, intervalSize, near, far).last().pose());
            PROJECTION = (Matrix4f) (fov == null
                    ? earth$ortho.invoke(halfPlaneLength, near, far)
                    : earth$perspective.invoke(fov.floatValue()));
            MapPackTarget.shadowMatrices(MODELVIEW, PROJECTION);
            setupShadowViewport();
            if (shouldRenderTerrain) pass.draw(MODELVIEW, PROJECTION, false);
            earth$stages.copyOpaqueDepth();
            if (shouldRenderTranslucent) pass.draw(MODELVIEW, PROJECTION, true);
            earth$generateMipmaps();
            target.bindWrite(true);
            RenderSystem.enableCull();
            earth$stages.composite();
        } finally {
            ACTIVE = oldActive;
            MODELVIEW = oldView;
            PROJECTION = oldProjection;
            target.bindWrite(true);
            RenderSystem.enableCull();
        }
        callback.cancel();
    }
}
