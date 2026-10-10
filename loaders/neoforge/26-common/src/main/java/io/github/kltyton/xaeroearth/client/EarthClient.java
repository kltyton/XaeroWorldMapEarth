package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import xaero.map.gui.GuiMap;

/** Adds the camera selector while the original GuiMap owns its complete lifecycle. */
public final class EarthClient extends EarthClientState {
    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(EarthClient::onMapInit);
        NeoForge.EVENT_BUS.addListener(EarthClient::onTick);
        NeoForge.EVENT_BUS.addListener(EarthClient::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(EarthClient::onScreenRenderPre);
        NeoForge.EVENT_BUS.addListener(EarthClient::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(EarthClient::onStopping);
        NeoForge.EVENT_BUS.addListener(EarthClient::onChunkLoad);
    }

    private static void onMapInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof GuiMap map)) return;
        themedScreens.add(map);
        for (var view : EarthPreferences.View.values()) {
            int index = view.ordinal();
            Button selector = Button.builder(Component.translatable("xaeroearth.view." + view.name().toLowerCase(java.util.Locale.ROOT)), button -> {
                EarthPreferences.VIEW.set(view); EarthPreferences.SPEC.save();
            }).bounds(map.width / 2 - 96 + index * 64, Math.max(4, map.height - 44), 62, 20).build();
            selector.visible = EarthPreferences.ENABLED.get();
            event.addListener(selector);
        }
        if (EarthPreferences.ENABLED.get()) { themedMap = map; NativeMapSkin.open(map); }
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (next instanceof GuiMap || (themedScreens.contains(event.getCurrentScreen())
                && next instanceof xaero.lib.client.gui.IScreenBase)) themedScreens.add(next);
    }

    private static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof GuiMap) && themedScreen(event.getScreen())) NativeMapSkin.beginFrame(event.getScreen());
    }

    private static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof GuiMap) && themedScreen(event.getScreen()))
            NativeMapSkin.render(event.getScreen(), event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    private static void onTick(ClientTickEvent.Post event) { tick(false); }

    private static void onChunkLoad(ChunkEvent.Load event) {
        var pos = event.getChunk().getPos(); changedChunk(event.getLevel(), pos.x(), pos.z());
    }

    private static void onStopping(GameShuttingDownEvent event) { stop(); }

    public static void registerReloadListener(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath("xaeroearth", "map_resources"), new PreparableReloadListener() {
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
