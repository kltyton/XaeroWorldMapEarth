package io.github.kltyton.xaeroearth.client.reload;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ResourceReloadRegistration {
    public static void register(Identifier id, PreparableReloadListener listener) {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return id;
            }

            @Override
            public CompletableFuture<Void> reload(SharedState state, Executor background,
                                                   PreparationBarrier barrier, Executor game) {
                return listener.reload(state, background, barrier, game);
            }
        });
    }

    private ResourceReloadRegistration() { }
}
