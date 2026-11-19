package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapShadowStages;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import net.minecraft.client.Camera;
import org.lwjgl.system.MemoryStack;
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

/** Submits the map's terrain to the older Oculus shadow stage. */
@Pseudo
@Mixin(targets = "net.coderbot.iris.pipeline.ShadowRenderer", remap = false)
abstract class LegacyMapShadowMixin {
    @Unique private MapShadowStages earth$stages;
    @Shadow public static boolean ACTIVE;
    @Shadow public static Matrix4f MODELVIEW;
    @Shadow @Final private float sunPathRotation;
    @Shadow @Final private float intervalSize;
    @Shadow @Final private float halfPlaneLength;
    @Shadow @Final private Float fov;
    @Shadow @Final private boolean shouldRenderTerrain;
    @Shadow @Final private boolean shouldRenderTranslucent;
    @Shadow public abstract void setupShadowViewport();
    @Invoker("generateMipmaps") public abstract void earth$generateMipmaps();

    @Shadow
    public static PoseStack createShadowModelView(float rotation, float interval) { throw new AssertionError(); }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void earth$captureStages(@Coerce Object source, @Coerce Object directives, @Coerce Object targets,
                                     @Coerce Object composite, @Coerce Object uniforms, boolean samplers,
                                     CallbackInfo callback) {
        if (MapPackTarget.current() != null) earth$stages = new MapShadowStages(targets, composite);
    }

    @Unique private static MethodHandle earth$ortho;
    @Unique private static MethodHandle earth$perspective;

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true)
    private void earth$mapShadows(@Coerce Object levelRenderer, Camera camera, CallbackInfo callback) throws Throwable {
        var pass = MapPackTarget.shadows();
        if (pass == null) return;
        if (earth$stages == null) throw new IllegalStateException("Missing legacy Iris map shadow stages");
        if (!earth$stages.enabled()) { callback.cancel(); return; }
        if (earth$ortho == null) {
            var type = Class.forName("net.coderbot.iris.shadows.ShadowMatrices");
            var lookup = MethodHandles.publicLookup();
            earth$ortho = lookup.unreflect(type.getMethod("createOrthoMatrix", float.class));
            earth$perspective = lookup.unreflect(type.getMethod("createPerspectiveMatrix", float.class));
        }
        var target = MapPackTarget.current();
        var oldView = MODELVIEW;
        var oldProjection = RenderSystem.getProjectionMatrix();
        boolean oldActive = ACTIVE;
        ACTIVE = true;
        try (var memory = MemoryStack.stackPush()) {
            MODELVIEW = createShadowModelView(sunPathRotation, intervalSize).last().pose();
            var values = (float[]) (fov == null ? earth$ortho.invoke(halfPlaneLength) : earth$perspective.invoke(fov.floatValue()));
            RenderSystem.setProjectionMatrix(new Matrix4f(values));
            var raw = memory.mallocFloat(16);
            MODELVIEW.store(raw);
            var view = new org.joml.Matrix4f().set(raw);
            var projection = new org.joml.Matrix4f().set(values);
            MapPackTarget.shadowMatrices(view, projection);
            setupShadowViewport();
            if (shouldRenderTerrain) pass.draw(view, projection, false);
            earth$stages.copyOpaqueDepth();
            if (shouldRenderTranslucent) pass.draw(view, projection, true);
            earth$generateMipmaps();
            target.bindWrite(true);
            RenderSystem.enableCull();
            earth$stages.composite();
        } finally {
            ACTIVE = oldActive;
            MODELVIEW = oldView;
            RenderSystem.setProjectionMatrix(oldProjection);
            target.bindWrite(true);
            RenderSystem.enableCull();
        }
        callback.cancel();
    }
}
