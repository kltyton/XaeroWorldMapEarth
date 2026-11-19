package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.bridge.XaeroMapBridge;
import io.github.kltyton.xaeroearth.client.bridge.CaveModelCeiling;
import io.github.kltyton.xaeroearth.client.bridge.WorldSavePrefetch;
import io.github.kltyton.xaeroearth.client.integration.waystones.WaystonesOverlay;
import io.github.kltyton.xaeroearth.client.integration.ftb.FtbClaimsOverlay;
import io.github.kltyton.xaeroearth.client.integration.create.CreateStructures;
import io.github.kltyton.xaeroearth.client.ui.NativeMapSkin;
import io.github.kltyton.xaeroearth.client.ui.NativeMapInput;
import io.github.kltyton.xaeroearth.client.ui.NativeMapTerrain;
import io.github.kltyton.xaeroearth.client.ui.NativeMinimapTerrain;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTileSession;
import io.github.kltyton.xaeroearth.client.chunkmap.TerrainDetail;
import io.github.kltyton.xaeroearth.client.navigation.PlayerNavigation;
import io.github.kltyton.xaeroearth.client.render.navigation.NavigationRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.level.ServerLevel;
import xaero.map.gui.GuiMap;

abstract class EarthClientState {
    protected static XaeroMapBridge bridge;
    private static final WorldSavePrefetch worldSavePrefetch = new WorldSavePrefetch();
    private static final CaveModelCeiling caveModelCeiling = new CaveModelCeiling();
    private record ServerChunkChange(ServerLevel level, int x, int z) { }
    private static final java.util.Set<ServerChunkChange> integratedChanges =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    protected static Object level;
    protected static GuiMap themedMap;
    protected static ActiveMapResources resources;
    protected static NativeTileSession models;
    protected static String modelWorld, modelDimension, modelMultiworld;
    protected static final int MODEL_THREADS = net.minecraft.util.Mth.clamp(
            (Runtime.getRuntime().availableProcessors() - 2) / 2, 2, 12);
    protected static final java.util.Set<Screen> themedScreens = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    @FunctionalInterface
    protected interface ViewButtonFactory {
        Button create(net.minecraft.network.chat.Component label, int x, int y, Runnable action);
    }

    protected static void initializeMap(Screen screen, java.util.function.BiConsumer<GuiMap, Button> add,
                                        ViewButtonFactory factory) {
        if (!(screen instanceof GuiMap map)) return;
        themedScreens.add(map);
        for (var view : EarthPreferences.View.values()) {
            String key = "xaeroearth.view." + view.name().toLowerCase(java.util.Locale.ROOT);
            Button selector = factory.create(ClientScreen.viewLabel(key), map.width / 2 - 96 + view.ordinal() * 64,
                    Math.max(4, map.height - 44), () -> NativeMapInput.selectView(map, view));
            selector.visible = EarthPreferences.ENABLED.get();
            add.accept(map, selector);
        }
        if (EarthPreferences.ENABLED.get()) { themedMap = map; NativeMapSkin.open(map); }
    }

    protected static void reloadResources(net.minecraft.server.packs.resources.ResourceManager manager) {
        CreateStructures.reset();
        NavigationRenderer.close();
        NativeMapSkin.close(); NativeMapTerrain.hide();
        NativeMinimapTerrain.close();
        closeModels();
        if (resources != null) { resources.close(); resources = null; }
        if (EarthPreferences.ENABLED.get()) {
            warmResources(manager);
            NativeMapSkin.prewarm();
        }
    }

    protected static void tick(boolean releaseOnWorldChange) {
        NativeMapInput.flushOrientation();
        io.github.kltyton.xaeroearth.client.ui.minimap.MinimapFraming.flush();
        Minecraft minecraft = Minecraft.getInstance();
        PlayerNavigation.tick(ClientScreen.screen(minecraft) != null);
        if (level != minecraft.level) {
            worldSavePrefetch.reset();
            WaystonesOverlay.reset();
            FtbClaimsOverlay.reset();
            CreateStructures.reset();
            level = minecraft.level;
            if (releaseOnWorldChange) NativeMapSkin.close(); else NativeMapSkin.suspend();
            themedScreens.clear();
            NativeMapTerrain.hide();
            NativeMinimapTerrain.close();
            closeModels();
            if (bridge != null) { bridge.close(); bridge = null; }
        }
        if (!EarthPreferences.ENABLED.get() || !EarthPreferences.MINIMAP_3D.get()) NativeMinimapTerrain.close();
        if (EarthPreferences.ENABLED.get()) worldSavePrefetch.tick(minecraft);
        else worldSavePrefetch.reset();
        if (EarthPreferences.ENABLED.get()) WaystonesOverlay.tick(minecraft);
        else WaystonesOverlay.reset();
        if (EarthPreferences.ENABLED.get()) FtbClaimsOverlay.tick(minecraft);
        else FtbClaimsOverlay.reset();
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
        if (EarthPreferences.ENABLED.get()) CreateStructures.tick(minecraft, resources);
        else CreateStructures.reset();
        drainIntegratedChanges(minecraft);
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
                double radius = 0, pixelsPerBlock = 1;
                int ceiling = caveModelCeiling.resolve(processor);
                if (ClientScreen.screen(minecraft) instanceof GuiMap map && map.getMapProcessor() == processor) {
                    var access = (io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess) map;
                    x = access.earth$cameraX(); z = access.earth$cameraZ();
                    pixelsPerBlock = access.earth$scale() / minecraft.getWindow().getGuiScale();
                    radius = Math.hypot(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()) / (2 * access.earth$scale());
                    if (NativeMapInput.view() != EarthPreferences.View.STREET)
                        radius /= Math.max(0.05, Math.cos(io.github.kltyton.xaeroearth.client.ui.NativeMapInput.angle()));
                } else if (EarthPreferences.MINIMAP_3D.get()) {
                    radius = NativeMinimapTerrain.detailRadius();
                }
                models.tick(x, z, TerrainDetail.radius(pixelsPerBlock, radius), ceiling,
                        ClientScreen.screen(minecraft) instanceof GuiMap || EarthPreferences.MINIMAP_3D.get());
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

    protected static boolean themedScreen(Screen screen) {
        if (!EarthPreferences.ENABLED.get()) return false;
        if (themedScreens.contains(screen)) return true;
        if (!(screen instanceof xaero.lib.client.gui.GuiSettings)) return false;
        String owner = screen.getClass().getPackageName();
        return owner.equals("xaero.common.gui") || owner.equals("xaero.map.gui");
    }

    public static boolean themed(GuiMap map) { return EarthPreferences.ENABLED.get() && ClientScreen.screen(Minecraft.getInstance()) == map; }

    public static NativeTileSession modelsFor(GuiMap map) {
        return modelsFor(map.getMapProcessor());
    }

    public static NativeTileSession modelsFor(xaero.map.MapProcessor processor) {
        return models != null && processor.isMapWorldUsable() && processor.getWorld() == Minecraft.getInstance().level
                && !processor.isConsideringNetherFairPlay() && processor.isCurrentMultiworldWritable()
                && java.util.Objects.equals(modelWorld, processor.getCurrentWorldId())
                && java.util.Objects.equals(modelDimension, processor.getCurrentDimId())
                && java.util.Objects.equals(modelMultiworld, processor.getCurrentMWId())
                && models.usesCeiling(caveModelCeiling.resolve(processor)) ? models : null;
    }

    public static void changedChunk(net.minecraft.world.level.LevelAccessor changed, int x, int z) {
        var client = Minecraft.getInstance();
        if (changed instanceof ServerLevel server) {
            if (EarthPreferences.ENABLED.get() && server.getServer() == client.getSingleplayerServer())
                integratedChanges.add(new ServerChunkChange(server, x, z));
        } else if (models != null && changed == client.level) models.invalidate(x, z);
    }

    private static void drainIntegratedChanges(Minecraft client) {
        var server = client.getSingleplayerServer();
        var current = client.level;
        for (var change : integratedChanges) {
            if (!integratedChanges.remove(change)) continue;
            if (models != null && current != null && server == change.level().getServer()
                    && current.dimension().equals(change.level().dimension()))
                models.invalidate(change.x(), change.z());
        }
    }

    protected static void closeModels() {
        caveModelCeiling.reset();
        if (models != null) { models.close(); models = null; }
    }

    public static void beginFrame(GuiMap map) {
        if (!themed(map)) return;
        NativeMapTerrain.beginWorldBackground(map);
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
        if (map == themedMap) {
            themedMap = null;
            NativeMapInput.close(map);
            NativeMapSkin.suspend();
            NativeMapTerrain.suspend();
        }
    }

    protected static void stop() {
        io.github.kltyton.xaeroearth.client.ui.minimap.MinimapFraming.flush(true);
        CreateStructures.reset();
        io.github.kltyton.xaeroearth.client.mapmark.MapAnnotations.stop();
        PlayerNavigation.cancel();
        NavigationRenderer.close();
        NativeMapSkin.close(); NativeMapTerrain.hide();
        NativeMinimapTerrain.close();
        closeModels();
        if (bridge != null) { bridge.close(); bridge = null; }
        if (resources != null) { resources.close(); resources = null; }
        themedScreens.clear();
    }
}
