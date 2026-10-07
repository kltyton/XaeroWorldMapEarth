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
public final class EarthClient {
    private static XaeroMapBridge bridge;
    private static Object level;
    private static GuiMap themedMap;
    private static ActiveMapResources resources;
    private static NativeTileSession models;
    private static String modelWorld, modelDimension, modelMultiworld;
    private static final int MODEL_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4);
    private static final java.util.Set<Screen> themedScreens = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

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

    private static boolean themedScreen(Screen screen) { return EarthPreferences.ENABLED.get() && themedScreens.contains(screen); }

    private static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof GuiMap) && themedScreen(event.getScreen())) NativeMapSkin.beginFrame(event.getScreen());
    }

    private static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof GuiMap) && themedScreen(event.getScreen()))
            NativeMapSkin.render(event.getScreen(), event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (level != minecraft.level) {
            level = minecraft.level;
            NativeMapSkin.suspend();
            themedScreens.clear();
            NativeMapTerrain.hide();
            closeModels();
            if (bridge != null) { bridge.close(); bridge = null; }
        }
        if (minecraft.level != null && EarthPreferences.ENABLED.get() && bridge == null) bridge = new XaeroMapBridge();
        if (minecraft.level == null || !EarthPreferences.ENABLED.get()) {
            NativeMapTerrain.hide();
            closeModels();
            if (bridge != null) { bridge.close(); bridge = null; }
            if (!EarthPreferences.ENABLED.get() && resources != null) { resources.close(); resources = null; }
        }
        if (minecraft.level != null && resources == null && EarthPreferences.ENABLED.get()) {
            warmResources(minecraft.getResourceManager());
        }
        if (minecraft.level != null && resources != null && EarthPreferences.ENABLED.get()) {
            var session = xaero.map.WorldMapSession.getCurrentSession();
            var processor = session == null ? null : session.getMapProcessor();
            boolean currentModelContext = processor != null && processor.isMapWorldUsable()
                    && processor.getWorld() == minecraft.level && !processor.isConsideringNetherFairPlay()
                    && processor.isCurrentMultiworldWritable()
                    && minecraft.level.dimension().equals(processor.getMapWorld().getCurrentDimensionId());
            if (models != null && currentModelContext && (!java.util.Objects.equals(modelWorld, processor.getCurrentWorldId())
                    || !java.util.Objects.equals(modelDimension, processor.getCurrentDimId())
                    || !java.util.Objects.equals(modelMultiworld, processor.getCurrentMWId()))) {
                NativeMapTerrain.suspend();
                closeModels();
            }
            if (models == null && currentModelContext) {
                modelWorld = processor.getCurrentWorldId(); modelDimension = processor.getCurrentDimId();
                modelMultiworld = processor.getCurrentMWId();
                models = new NativeTileSession(minecraft.level, resources, cacheDirectory(),
                        modelWorld + ":" + modelDimension + ":" + modelMultiworld, MODEL_THREADS,
                        NativeTileSession.Options.WORLD_MAP);
                NativeMapTerrain.prewarm(resources);
            }
            if (models != null && minecraft.player != null && currentModelContext) {
                if (minecraft.screen instanceof GuiMap activeMap) io.github.kltyton.xaeroearth.client.ui.NativeMapInput.tick(activeMap);
                double x = minecraft.player.getX(), z = minecraft.player.getZ();
                double radius = Math.hypot(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()) / 8;
                int layer = processor == null ? Integer.MAX_VALUE : processor.getCurrentCaveLayer();
                int ceiling = layer == Integer.MAX_VALUE ? Integer.MAX_VALUE
                        : processor.getMapWorld().getCurrentDimension().getLayeredMapRegions().getLayer(layer).getCaveStart();
                if (ceiling != Integer.MAX_VALUE && ceiling != Integer.MIN_VALUE) ceiling++;
                if (minecraft.screen instanceof GuiMap map && modelsFor(map) != null) {
                    var access = (io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess) map;
                    x = access.earth$cameraX(); z = access.earth$cameraZ();
                    radius = Math.hypot(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()) / (2 * access.earth$scale());
                    if (EarthPreferences.VIEW.get() == EarthPreferences.View.ISOMETRIC)
                        radius /= Math.max(0.05, Math.cos(io.github.kltyton.xaeroearth.client.ui.NativeMapInput.angle()));
                }
                models.tick(x, z, Math.min(1536, radius), ceiling, minecraft.screen instanceof GuiMap);
            }
        }
        if (!themedScreen(minecraft.screen)) NativeMapSkin.suspend();
        if (!(minecraft.screen instanceof GuiMap map)) {
            themedMap = null; NativeMapTerrain.suspend();
        } else {
            for (var child : map.children()) if (child instanceof Button button
                    && button.getMessage().getContents() instanceof TranslatableContents translated
                    && translated.getKey().startsWith("xaeroearth.view."))
                button.visible = EarthPreferences.ENABLED.get() && !GuiMap.hiddenUI;
            if (!EarthPreferences.ENABLED.get()) { themedMap = null; NativeMapTerrain.suspend(); }
        }
    }

    public static boolean themed(GuiMap map) { return EarthPreferences.ENABLED.get() && Minecraft.getInstance().screen == map; }

    public static NativeTileSession modelsFor(GuiMap map) {
        var processor = map.getMapProcessor();
        return models != null && processor.getWorld() == Minecraft.getInstance().level
                && !processor.isConsideringNetherFairPlay() && processor.isCurrentMultiworldWritable()
                && java.util.Objects.equals(modelWorld, processor.getCurrentWorldId())
                && java.util.Objects.equals(modelDimension, processor.getCurrentDimId())
                && java.util.Objects.equals(modelMultiworld, processor.getCurrentMWId()) ? models : null;
    }

    public static void changedChunk(net.minecraft.world.level.LevelAccessor changed, int x, int z) {
        if (models != null && changed instanceof net.minecraft.client.multiplayer.ClientLevel
                && changed == Minecraft.getInstance().level) models.invalidate(x, z);
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        var pos = event.getChunk().getPos(); changedChunk(event.getLevel(), pos.x(), pos.z());
    }

    private static void closeModels() { if (models != null) { models.close(); models = null; } }

    private static void onStopping(GameShuttingDownEvent event) {
        NativeMapSkin.close(); NativeMapTerrain.hide();
        closeModels();
        if (bridge != null) { bridge.close(); bridge = null; }
        if (resources != null) { resources.close(); resources = null; }
        themedScreens.clear();
    }

    public static void beginFrame(GuiMap map) {
        if (!themed(map)) return;
        themedMap = map; NativeMapSkin.beginFrame(map);
    }

    public static void drawTerrain(GuiMap map) {
        if (!themed(map) || bridge == null || resources == null || resources.isClosed()) return;
        NativeMapTerrain.draw(map, bridge, resources);
    }

    private static void warmResources(net.minecraft.server.packs.resources.ResourceManager manager) {
        NativeMapTerrain.hide();
        if (resources != null) resources.close();
        closeModels();
        resources = new ActiveMapResources(manager, cacheDirectory(), MODEL_THREADS);
        NativeMapTerrain.prewarm(resources);
        resources.renderer().whenComplete((renderer, failure) -> {
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)
                    && !(failure.getCause() instanceof java.util.concurrent.CancellationException))
                com.mojang.logging.LogUtils.getLogger().error("Cannot prepare active AUI map materials", failure);
        });
    }

    private static java.nio.file.Path cacheDirectory() {
        var fallback = Minecraft.getInstance().gameDirectory.toPath().resolve("cache/apricityui/map-materials");
        return java.nio.file.Path.of(System.getProperty("apricityui.mapCacheDirectory", fallback.toString()));
    }

    public static void closeMap(GuiMap map) {
        if (map == themedMap) { themedMap = null; NativeMapSkin.suspend(); NativeMapTerrain.suspend(); }
    }

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
