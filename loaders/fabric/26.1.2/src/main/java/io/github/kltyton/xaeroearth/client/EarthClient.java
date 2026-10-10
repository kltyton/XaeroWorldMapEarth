package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.server.packs.PackType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import xaero.map.gui.GuiMap;

/** Adds the camera selector while the original GuiMap owns its complete lifecycle. */
public final class EarthClient extends EarthClientState {
    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> onTick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> onStopping());
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            var pos = chunk.getPos(); changedChunk(world, pos.x(), pos.z());
        });
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            onMapInit(screen);
            ScreenEvents.beforeExtract(screen).register((target, graphics, x, y, ticks) -> onScreenRenderPre(target));
            ScreenEvents.afterExtract(screen).register(EarthClient::onScreenRenderPost);
        });
        registerReloadListener();
    }

    private static void onMapInit(Screen screen) {
        if (!(screen instanceof GuiMap map)) return;
        themedScreens.add(map);
        for (var view : EarthPreferences.View.values()) {
            int index = view.ordinal();
            Button selector = Button.builder(Component.translatable("xaeroearth.view." + view.name().toLowerCase(java.util.Locale.ROOT)), button -> {
                EarthPreferences.VIEW.set(view); EarthPreferences.save();
            }).bounds(map.width / 2 - 96 + index * 64, Math.max(4, map.height - 44), 62, 20).build();
            selector.visible = EarthPreferences.ENABLED.get();
            Screens.getWidgets(map).add(selector);
        }
        if (EarthPreferences.ENABLED.get()) { themedMap = map; NativeMapSkin.open(map); }
    }

    public static void screenOpening(Screen current, Screen next) {
        if (next instanceof GuiMap || (themedScreens.contains(current)
                && next instanceof xaero.lib.client.gui.IScreenBase)) themedScreens.add(next);
    }

    private static void onScreenRenderPre(Screen screen) {
        if (!(screen instanceof GuiMap) && themedScreen(screen)) NativeMapSkin.beginFrame(screen);
    }

    private static void onScreenRenderPost(Screen screen, GuiGraphicsExtractor graphics, int x, int y, float ticks) {
        if (!(screen instanceof GuiMap) && themedScreen(screen)) NativeMapSkin.render(screen, graphics, x, y);
    }

    private static void onTick() { tick(false); }

    private static void onStopping() { stop(); }

    private static void registerReloadListener() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {
            @Override public Identifier getFabricId() {
                return Identifier.fromNamespaceAndPath("xaeroearth", "map_resources");
            }
            @Override public CompletableFuture<Void> reload(SharedState state, Executor background, PreparationBarrier barrier, Executor game) {
                return CompletableFuture.<Void>completedFuture(null).thenCompose(barrier::wait).thenRunAsync(() -> {
                    NativeMapSkin.close(); NativeMapTerrain.hide();
                    closeModels();
                    if (resources != null) { resources.close(); resources = null; }
                    if (EarthPreferences.ENABLED.get()) {
                        warmResources(state.resourceManager());
                        NativeMapSkin.prewarm();
                    }
                }, game);
            }
        });
    }

    private EarthClient() { }
}
