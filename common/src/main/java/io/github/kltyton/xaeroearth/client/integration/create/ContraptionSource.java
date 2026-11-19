package io.github.kltyton.xaeroearth.client.integration.create;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Public Create block snapshots and its own local-to-world motion transform. */
final class ContraptionSource {
    private static final String CREATE = "com.simibubi.create.content.contraptions.";
    private final Class<?> entityType;
    private final MethodHandle contraption, blocks, transform, clientContraption, renderBlocks;

    private ContraptionSource(Class<?> entityType, MethodHandle contraption, MethodHandle blocks, MethodHandle transform,
                              MethodHandle clientContraption, MethodHandle renderBlocks) {
        this.entityType = entityType; this.contraption = contraption; this.blocks = blocks;
        this.transform = transform;
        this.clientContraption = clientContraption;
        this.renderBlocks = renderBlocks;
    }

    static ContraptionSource open() {
        ClassLoader loader = ContraptionSource.class.getClassLoader();
        Class<?> entity;
        try { entity = Class.forName(CREATE + "AbstractContraptionEntity", false, loader); }
        catch (ClassNotFoundException absent) { return null; }
        catch (LinkageError failed) { LogUtils.getLogger().warn("Cannot load Create's contraption type", failed); return null; }
        // Only the documented public snapshot and transform members are needed.
        // Binding here keeps the mod optional across Create and Create Fabric.
        try {
            var lookup = MethodHandles.publicLookup();
            var getter = entity.getMethod("getContraption");
            var renderTransform = entity.getMethod("toGlobalVector", Vec3.class, float.class);
            Class<?> snapshot = getter.getReturnType();
            MethodHandle clientView = null, renderedEntities = null;
            try {
                var clientGetter = snapshot.getMethod("getOrCreateClientContraptionLazy");
                renderedEntities = lookup.unreflectGetter(clientGetter.getReturnType().getField("renderedBlockEntityView"));
                clientView = lookup.unreflect(clientGetter);
            } catch (NoSuchMethodException | NoSuchFieldException absent) {
                try { renderedEntities = lookup.unreflect(snapshot.getMethod("getSpecialRenderedBEs")); }
                catch (NoSuchMethodException unavailable) {
                    LogUtils.getLogger().debug("Create supplies block snapshots without a public block entity render list");
                }
            }
            return new ContraptionSource(entity, lookup.unreflect(getter), lookup.unreflect(snapshot.getMethod("getBlocks")),
                    lookup.unreflect(renderTransform), clientView, renderedEntities);
        } catch (ReflectiveOperationException | LinkageError failure) {
            LogUtils.getLogger().warn("The installed Create build does not match Earth's contraption adapter", failure);
            return null;
        }
    }

    boolean accepts(Entity entity) { return entityType.isInstance(entity); }
    Object snapshot(Entity entity) throws Throwable { return contraption.invoke(entity); }
    Map<?, ?> blocks(Object snapshot) throws Throwable { return (Map<?, ?>) blocks.invoke(snapshot); }
    Collection<?> renderBlocks(Object snapshot) throws Throwable {
        if (renderBlocks == null) return List.of();
        Object owner = clientContraption == null ? snapshot : clientContraption.invoke(snapshot);
        return (Collection<?>) renderBlocks.invoke(owner);
    }
    Vec3 transform(Entity entity, Vec3 point, float partialTick) throws Throwable {
        return (Vec3) transform.invoke(entity, point, partialTick);
    }
}
