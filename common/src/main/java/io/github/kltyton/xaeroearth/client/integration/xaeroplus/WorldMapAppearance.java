package io.github.kltyton.xaeroearth.client.integration.xaeroplus;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** Reads the installed XaeroPlus world-map background setting without requiring the add-on. */
public final class WorldMapAppearance {
    private WorldMapAppearance() {}

    public static boolean transparentBackground() {
        var getter = Binding.TRANSPARENT_BACKGROUND;
        if (getter == null) return false;
        try {
            return (boolean) getter.invokeExact();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot read XaeroPlus world-map appearance", failure);
        }
    }

    private static final class Binding {
        private static final MethodHandle TRANSPARENT_BACKGROUND = link();

        private static MethodHandle link() {
            try {
                var type = Class.forName("xaeroplus.settings.Settings", false, WorldMapAppearance.class.getClassLoader());
                var lookup = MethodHandles.publicLookup();
                var registry = lookup.unreflectGetter(type.getField("REGISTRY"));
                var field = type.getField("transparentWorldmapBackgroundSetting");
                var option = lookup.unreflectGetter(field);
                var get = lookup.unreflect(field.getType().getMethod("get"));
                return MethodHandles.collectArguments(MethodHandles.filterArguments(get, 0, option), 0, registry);
            } catch (ClassNotFoundException | NoSuchFieldException unsupported) {
                return null;
            } catch (ReflectiveOperationException | LinkageError failure) {
                LogUtils.getLogger().warn("Cannot link the installed XaeroPlus world-map appearance", failure);
                return null;
            }
        }
    }
}
