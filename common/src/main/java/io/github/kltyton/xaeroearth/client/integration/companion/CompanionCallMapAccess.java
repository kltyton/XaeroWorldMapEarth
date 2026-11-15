package io.github.kltyton.xaeroearth.client.integration.companion;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** Binds the public optional API once. An access rule or mixin cannot supply absent mod classes. */
final class CompanionCallMapAccess implements CompanionMapAccess {
    private final Predicate<ClientLevel> supported;
    private final Function<ClientLevel, List<?>> registered;
    private final MoveRequest navigate;
    private final Function<Object, UUID> uuid;
    private final ToIntFunction<Object> entityId;
    private boolean failed;
    private ClientLevel cachedLevel;
    private long cachedAt;
    private List<Companion> cached = List.of();

    private record Companion(UUID id, int entityId) {}

    @SuppressWarnings("unchecked")
    private CompanionCallMapAccess(Class<?> api) throws ReflectiveOperationException {
        var lookup = MethodHandles.publicLookup();
        Class<?> companion = Class.forName(api.getName() + "$Companion", false, api.getClassLoader());
        supported = MethodHandleProxies.asInterfaceInstance(Predicate.class,
                lookup.findStatic(api, "isSupported", MethodType.methodType(boolean.class, ClientLevel.class)));
        registered = MethodHandleProxies.asInterfaceInstance(Function.class,
                lookup.findStatic(api, "companions", MethodType.methodType(List.class, ClientLevel.class)));
        navigate = MethodHandleProxies.asInterfaceInstance(MoveRequest.class,
                lookup.findStatic(api, "moveTo", MethodType.methodType(boolean.class, ClientLevel.class, Collection.class, BlockPos.class)));
        uuid = MethodHandleProxies.asInterfaceInstance(Function.class,
                lookup.findVirtual(companion, "id", MethodType.methodType(UUID.class)));
        entityId = MethodHandleProxies.asInterfaceInstance(ToIntFunction.class,
                lookup.findVirtual(companion, "entityId", MethodType.methodType(int.class)));
    }

    static CompanionMapAccess connect() {
        Class<?> api;
        try {
            api = Class.forName("com.kltyton.companioncall.api.client.CompanionMapApi", false, CompanionCallMapAccess.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (LinkageError failure) {
            LogUtils.getLogger().error("Cannot link CompanionCall's map API", failure);
            return null;
        }
        try {
            return new CompanionCallMapAccess(api);
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError failure) {
            LogUtils.getLogger().error("CompanionCall map API does not match the expected contract", failure);
            return null;
        }
    }

    @Override public boolean available(ClientLevel level) {
        if (failed) return false;
        try {
            return supported.test(level);
        } catch (RuntimeException | LinkageError failure) {
            report(failure);
            return false;
        }
    }

    @Override public Collection<? extends Entity> visibleCompanions(ClientLevel level) {
        if (failed) return List.of();
        try {
            long now = System.nanoTime();
            if (cachedLevel != level || now - cachedAt >= 100_000_000L) {
                List<?> companions = registered.apply(level);
                var snapshot = new ArrayList<Companion>(companions.size());
                for (Object companion : companions)
                    snapshot.add(new Companion(uuid.apply(companion), entityId.applyAsInt(companion)));
                cachedLevel = level;
                cachedAt = now;
                cached = List.copyOf(snapshot);
            }
            var result = new ArrayList<Entity>(cached.size());
            for (Companion companion : cached) {
                Entity entity = level.getEntity(companion.entityId);
                if (entity != null && companion.id.equals(entity.getUUID())) result.add(entity);
            }
            return result;
        } catch (RuntimeException | LinkageError failure) {
            report(failure);
            return List.of();
        }
    }

    @Override public boolean move(ClientLevel level, Set<UUID> selection, BlockPos goal) {
        if (failed) return false;
        try {
            return navigate.send(level, selection, goal);
        } catch (RuntimeException | LinkageError failure) {
            report(failure);
            return false;
        }
    }

    private void report(Throwable failure) {
        if (!failed) LogUtils.getLogger().error("CompanionCall map API failed", failure);
        failed = true;
    }

    @Override public void clearSnapshot() {
        cachedLevel = null;
        cached = List.of();
    }
}
