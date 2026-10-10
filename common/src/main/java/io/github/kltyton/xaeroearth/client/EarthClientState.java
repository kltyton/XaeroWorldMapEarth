package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import xaero.map.gui.GuiMap;

abstract class EarthClientState {
    protected static XaeroMapBridge bridge;
    protected static Object level;
    protected static GuiMap themedMap;
    protected static ActiveMapResources resources;
    protected static NativeTileSession models;
    protected static String modelWorld, modelDimension, modelMultiworld;
    protected static final int MODEL_THREADS = net.minecraft.util.Mth.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4);
    protected static final java.util.Set<Screen> themedScreens = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    protected static void tick(boolean releaseOnWorldChange) {
        Minecraft minecraft = Minecraft.getInstance();
        if (level != minecraft.level) {
            level = minecraft.level;
            if (releaseOnWorldChange) NativeMapSkin.close(); else NativeMapSkin.suspend();
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
            if ((releaseOnWorldChange || !EarthPreferences.ENABLED.get()) && resources != null) { resources.close(); resources = null; }
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
                if (ClientScreen.screen(minecraft) instanceof GuiMap activeMap) io.github.kltyton.xaeroearth.client.ui.NativeMapInput.tick(activeMap);
                double x = minecraft.player.getX(), z = minecraft.player.getZ();
                double radius = Math.hypot(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()) / 8;
                int layer = processor == null ? Integer.MAX_VALUE : processor.getCurrentCaveLayer();
                int ceiling = layer == Integer.MAX_VALUE ? Integer.MAX_VALUE
                        : processor.getMapWorld().getCurrentDimension().getLayeredMapRegions().getLayer(layer).getCaveStart();
                if (ceiling != Integer.MAX_VALUE && ceiling != Integer.MIN_VALUE) ceiling++;
                if (ClientScreen.screen(minecraft) instanceof GuiMap map && modelsFor(map) != null) {
                    var access = (io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess) map;
                    x = access.earth$cameraX(); z = access.earth$cameraZ();
                    radius = Math.hypot(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()) / (2 * access.earth$scale());
                    if (EarthPreferences.VIEW.get() == EarthPreferences.View.ISOMETRIC)
                        radius /= Math.max(0.05, Math.cos(io.github.kltyton.xaeroearth.client.ui.NativeMapInput.angle()));
                }
                models.tick(x, z, Math.min(1536, radius), ceiling, ClientScreen.screen(minecraft) instanceof GuiMap);
            }
        }
        if (!themedScreen(ClientScreen.screen(minecraft))) NativeMapSkin.suspend();
        if (!(ClientScreen.screen(minecraft) instanceof GuiMap map)) {
            themedMap = null; NativeMapTerrain.suspend();
        } else {
            for (var child : map.children()) if (child instanceof Button button
                    && ClientScreen.isViewSelector(button))
                button.visible = EarthPreferences.ENABLED.get() && !GuiMap.hiddenUI;
            if (!EarthPreferences.ENABLED.get()) { themedMap = null; NativeMapTerrain.suspend(); }
        }
    }

    protected static boolean themedScreen(Screen screen) { return EarthPreferences.ENABLED.get() && themedScreens.contains(screen); }

    public static boolean themed(GuiMap map) { return EarthPreferences.ENABLED.get() && ClientScreen.screen(Minecraft.getInstance()) == map; }

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

    protected static void closeModels() { if (models != null) { models.close(); models = null; } }

    public static void beginFrame(GuiMap map) {
        if (!themed(map)) return;
        themedMap = map; NativeMapSkin.beginFrame(map);
    }

    public static void drawTerrain(GuiMap map) {
        if (!themed(map) || bridge == null || resources == null || resources.isClosed()) return;
        NativeMapTerrain.draw(map, bridge, resources);
    }

    protected static void warmResources(net.minecraft.server.packs.resources.ResourceManager manager) {
        NativeMapTerrain.hide();
        if (resources != null) resources.close();
        closeModels();
        resources = new ActiveMapResources(manager, cacheDirectory(), MODEL_THREADS);
        NativeMapTerrain.prewarm(resources);
        resources.renderer().whenComplete((renderer, failure) -> {
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)
                    && !(failure.getCause() instanceof java.util.concurrent.CancellationException))
                com.mojang.logging.LogUtils.getLogger().error("Cannot prepare active KUI map materials", failure);
        });
    }

    protected static java.nio.file.Path cacheDirectory() {
        var fallback = Minecraft.getInstance().gameDirectory.toPath().resolve("cache/kltytonui/map-materials");
        return java.nio.file.Path.of(System.getProperty("kltytonui.mapCacheDirectory", fallback.toString()));
    }

    public static void closeMap(GuiMap map) {
        if (map == themedMap) { themedMap = null; NativeMapSkin.suspend(); NativeMapTerrain.suspend(); }
    }

    protected static void stop() {
        NativeMapSkin.close(); NativeMapTerrain.hide();
        closeModels();
        if (bridge != null) { bridge.close(); bridge = null; }
        if (resources != null) { resources.close(); resources = null; }
        themedScreens.clear();
    }
}
