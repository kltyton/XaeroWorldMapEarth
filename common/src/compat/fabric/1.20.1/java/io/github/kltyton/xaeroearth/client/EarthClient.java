package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.server.packs.PackType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import xaero.map.gui.GuiMap;

/** Adds the camera selector while the original GuiMap owns its complete lifecycle. */
public final class EarthClient extends EarthClientState {
    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> onTick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> onStopping());
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            var pos = chunk.getPos(); changedChunk(world, pos.x, pos.z);
        });
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            onMapInit(screen);
            ScreenEvents.beforeRender(screen).register((target, graphics, x, y, ticks) -> onScreenRenderPre(target));
            ScreenEvents.afterRender(screen).register(EarthClient::onScreenRenderPost);
        });
        registerReloadListener();
    }

    private static void onMapInit(Screen screen) {
        initializeMap(screen, (map, button) -> Screens.getButtons(map).add(button),
                (label, x, y, action) -> Button.builder(label, button -> action.run()).bounds(x, y, 62, 20).build());
    }

    public static void screenOpening(Screen current, Screen next) {
        if (next instanceof GuiMap || (themedScreens.contains(current)
                && next instanceof xaero.lib.client.gui.IScreenBase)) themedScreens.add(next);
    }

    private static void onScreenRenderPre(Screen screen) {
        if (!(screen instanceof GuiMap) && themedScreen(screen)) NativeMapSkin.beginFrame(screen);
    }

    private static void onScreenRenderPost(Screen screen, GuiGraphics graphics, int x, int y, float ticks) {
        if (!(screen instanceof GuiMap) && themedScreen(screen)) NativeMapSkin.render(screen, graphics, x, y);
        NativeMapSkin.flushTooltips(graphics);
    }

    private static void onTick() { tick(true); }

    private static void onStopping() { stop(); }

    private static void registerReloadListener() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() {
                return new ResourceLocation("xaeroearth", "map_resources");
            }
            @Override public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                reloadResources(manager);
            }
        });
    }

    private EarthClient() { }
}
