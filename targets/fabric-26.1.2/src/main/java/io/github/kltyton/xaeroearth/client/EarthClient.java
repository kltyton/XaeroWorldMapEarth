package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import com.sighs.apricityui.chunkmap.AuiActiveMapResources;
import com.sighs.apricityui.chunkmap.AuiNativeTileSession;
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
public final class EarthClient {
    private static XaeroMapBridge bridge;
    private static Object level;
    private static GuiMap themedMap;
    private static AuiActiveMapResources resources;
    private static AuiNativeTileSession models;
    private static String modelWorld, modelDimension, modelMultiworld;
    private static final int MODEL_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4);
    private static final java.util.Set<Screen> themedScreens = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

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

    private static boolean themedScreen(Screen screen) { return EarthPreferences.ENABLED.get() && themedScreens.contains(screen); }

    private static void onScreenRenderPre(Screen screen) {
        if (!(screen instanceof GuiMap) && themedScreen(screen)) NativeMapSkin.beginFrame(screen);
    }

    private static void onScreenRenderPost(Screen screen, GuiGraphicsExtractor graphics, int x, int y, float ticks) {
        if (!(screen instanceof GuiMap) && themedScreen(screen)) NativeMapSkin.render(screen, graphics, x, y);
    }

    private static void onTick() {
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
                models = new AuiNativeTileSession(minecraft.level, resources, cacheDirectory(),
                        modelWorld + ":" + modelDimension + ":" + modelMultiworld, MODEL_THREADS,
                        AuiNativeTileSession.Options.WORLD_MAP);
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

    public static AuiNativeTileSession modelsFor(GuiMap map) {
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

    private static void closeModels() { if (models != null) { models.close(); models = null; } }

    private static void onStopping() {
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
        resources = new AuiActiveMapResources(manager, cacheDirectory(), MODEL_THREADS);
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
