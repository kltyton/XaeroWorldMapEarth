package io.github.kltyton.xaeroearth.client.render.shaderpack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** Links public shadow stages captured by the mixin without making optional Iris classes a build dependency. */
public final class MapShadowStages {
    private final MethodHandle copyDepth;
    private final MethodHandle composite;
    private final MethodHandle shadowDistance;
    private final MethodHandle overriddenShadowDistance;

    public MapShadowStages(Object targets, Object renderer) {
        try {
            var lookup = MethodHandles.publicLookup();
            copyDepth = lookup.unreflect(targets.getClass().getMethod("copyPreTranslucentDepth")).bindTo(targets);
            composite = lookup.unreflect(renderer.getClass().getMethod("renderAll")).bindTo(renderer);
            String root = targets.getClass().getName().startsWith("net.coderbot.iris.")
                    ? "net.coderbot.iris." : "net.irisshaders.iris.";
            Class<?> settings = Class.forName(root + "gui.option.IrisVideoSettings", false, targets.getClass().getClassLoader());
            shadowDistance = lookup.unreflectGetter(settings.getField("shadowDistance"));
            overriddenShadowDistance = lookup.unreflect(settings.getMethod("getOverriddenShadowDistance", int.class));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot link Iris map shadow stages", failure);
        }
    }

    public void copyOpaqueDepth() { invoke(copyDepth); }
    public void composite() { invoke(composite); }

    public boolean enabled() {
        try { return (int) overriddenShadowDistance.invokeExact((int) shadowDistance.invokeExact()) != 0; }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot read Iris shadow distance", failure); }
    }

    private static void invoke(MethodHandle method) {
        try { method.invokeExact(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Iris map shadow stage failed", failure); }
    }
}
