package io.github.kltyton.xaeroearth.client.chunkmap;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.renderer.DynamicUniformStorage;

/** Groups KUI draws that belong to one map submission into one uniform-buffer frame. */
public final class NativeUniformFrameScope implements AutoCloseable {
    private static final ThreadLocal<NativeUniformFrameScope> ACTIVE = new ThreadLocal<>();
    private final Set<DynamicUniformStorage<?>> rotated = Collections.newSetFromMap(new IdentityHashMap<>());

    public static NativeUniformFrameScope open() {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested map uniform frame");
        var scope = new NativeUniformFrameScope();
        ACTIVE.set(scope);
        return scope;
    }

    public static boolean rotate(DynamicUniformStorage<?> storage) {
        var scope = ACTIVE.get();
        return scope == null || scope.rotated.add(storage);
    }

    @Override public void close() {
        if (ACTIVE.get() != this) throw new IllegalStateException("Map uniform frame ownership changed");
        ACTIVE.remove();
    }
}
