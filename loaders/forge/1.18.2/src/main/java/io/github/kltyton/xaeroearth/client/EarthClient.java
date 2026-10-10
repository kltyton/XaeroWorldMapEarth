package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ScreenOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import xaero.map.gui.GuiMap;

/** Adds the camera selector while the original GuiMap owns its complete lifecycle. */
public final class EarthClient extends EarthClientState {
    public static void initialize() {
        MinecraftForge.EVENT_BUS.addListener(EarthClient::onMapInit);
        MinecraftForge.EVENT_BUS.addListener(EarthClient::onTick);
        MinecraftForge.EVENT_BUS.addListener(EarthClient::onScreenOpening);
        MinecraftForge.EVENT_BUS.addListener(EarthClient::onScreenRenderPre);
        MinecraftForge.EVENT_BUS.addListener(EarthClient::onScreenRenderPost);
        MinecraftForge.EVENT_BUS.addListener(EarthClient::onChunkLoad);
    }

    private static void onMapInit(ScreenEvent.InitScreenEvent.Post event) {
        if (!(event.getScreen() instanceof GuiMap map)) return;
        themedScreens.add(map);
        for (var view : EarthPreferences.View.values()) {
            int index = view.ordinal();
            Button selector = new Button(map.width / 2 - 96 + index * 64, Math.max(4, map.height - 44), 62, 20,
                    new TranslatableComponent("xaeroearth.view." + view.name().toLowerCase(java.util.Locale.ROOT)), button -> {
                EarthPreferences.VIEW.set(view); EarthPreferences.SPEC.save();
            });
            selector.visible = EarthPreferences.ENABLED.get();
            event.addListener(selector);
        }
        if (EarthPreferences.ENABLED.get()) { themedMap = map; NativeMapSkin.open(map); }
    }

    private static void onScreenOpening(ScreenOpenEvent event) {
        Screen next = event.getScreen();
        if (next instanceof GuiMap || (themedScreens.contains(Minecraft.getInstance().screen)
                && next instanceof xaero.lib.client.gui.IScreenBase)) themedScreens.add(next);
    }

    private static void onScreenRenderPre(ScreenEvent.DrawScreenEvent.Pre event) {
        if (!(event.getScreen() instanceof GuiMap) && themedScreen(event.getScreen())) NativeMapSkin.beginFrame(event.getScreen());
    }

    private static void onScreenRenderPost(ScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.getScreen() instanceof GuiMap) && themedScreen(event.getScreen()))
            NativeMapSkin.render(event.getScreen(), event.getPoseStack(), event.getMouseX(), event.getMouseY());
        NativeMapSkin.flushTooltips(event.getPoseStack());
    }

    private static void onTick(TickEvent.ClientTickEvent event) { if (event.phase != TickEvent.Phase.END) return; tick(false); }

    private static void onChunkLoad(ChunkEvent.Load event) {
        var pos = event.getChunk().getPos(); changedChunk(event.getWorld(), pos.x, pos.z);
    }

    public static void onStopping() { stop(); }

    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PreparableReloadListener() {
            @Override public CompletableFuture<Void> reload(PreparationBarrier barrier, net.minecraft.server.packs.resources.ResourceManager manager,
                    net.minecraft.util.profiling.ProfilerFiller preparationProfiler,
                    net.minecraft.util.profiling.ProfilerFiller reloadProfiler, Executor background, Executor game) {
                return CompletableFuture.<Void>completedFuture(null).thenCompose(barrier::wait).thenRunAsync(() -> {
                    NativeMapSkin.close(); NativeMapTerrain.hide();
                    closeModels();
                    if (resources != null) { resources.close(); resources = null; }
                    if (EarthPreferences.ENABLED.get()) {
                        warmResources(manager);
                        NativeMapSkin.prewarm();
                    }
                }, game);
            }
        });
    }

    private EarthClient() { }
}
