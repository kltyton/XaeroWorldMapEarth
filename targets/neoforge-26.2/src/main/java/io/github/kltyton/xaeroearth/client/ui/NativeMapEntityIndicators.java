package io.github.kltyton.xaeroearth.client.ui;

import com.sighs.apricityui.client.gui.AuiEntityPreview;
import com.sighs.apricityui.client.gui.AuiMapEntityLayer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import xaero.map.WorldMap;
import xaero.map.common.config.option.WorldMapProfiledConfigOptions;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.gui.GuiMap;
import xaero.map.radar.tracker.PlayerTrackerMapElement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Replaces eligible original marker textures with models in the map's world coordinates. */
public final class NativeMapEntityIndicators {
    private static Frame frame;

    private NativeMapEntityIndicators() {
    }

    public static void begin(GuiMap map, GuiGraphicsExtractor graphics, float partialTicks) {
        frame = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (!EarthPreferences.MODEL_INDICATORS.get() || !EarthClient.themed(map) || GuiMap.hiddenUI
                || minecraft.level == null || map.getMapProcessor() == null
                || map.getMapProcessor().getWorld() != minecraft.level
                || map.getMapProcessor().getMapWorld() == null
                || !minecraft.level.dimension().equals(map.getMapProcessor().getMapWorld().getCurrentDimensionId())) return;
        frame = new Frame(map, graphics, minecraft.level, partialTicks);
    }

    public static boolean replaceActor(GuiMap map, Entity actor) {
        Frame current = activeFrame();
        if (current == null || current.map != map || !loaded(current, actor)
                || !WorldMap.INSTANCE.getConfigs().getClientConfigManager().getEffective(WorldMapProfiledConfigOptions.ARROW)) return false;
        if (current.actorId != null && current.actorId.equals(actor.getUUID())) {
            return AuiMapEntityLayer.visible(NativeMapTerrain.camera(), current.models.get(current.actorId));
        }
        var position = actor.getPosition(current.partialTicks);
        AuiEntityPreview.Model model = capture(current, actor, position.x, position.y, position.z);
        if (model == null) return false;
        current.models.put(actor.getUUID(), model);
        current.actorId = actor.getUUID();
        return AuiMapEntityLayer.visible(NativeMapTerrain.camera(), model);
    }

    public static boolean replaceTracked(PlayerTrackerMapElement<?> marker, ElementRenderInfo info) {
        Frame current = activeFrame();
        if (current == null || info.location != ElementRenderLocation.WORLD_MAP
                || !current.level.dimension().equals(info.mapDimension)
                || !current.level.dimension().equals(marker.getDimension())) return false;
        Entity entity = current.level.getPlayerByUUID(marker.getPlayerId());
        if (!loaded(current, entity)) return false;
        if (entity.getUUID().equals(current.actorId)) return true;
        AuiEntityPreview.Model model = capture(current, entity, marker.getX(), marker.getY(), marker.getZ());
        if (model == null) return false;
        current.models.put(entity.getUUID(), model);
        return true;
    }

    public static void captureRadar(Entity entity, ElementRenderInfo info) {
        Frame current = activeFrame();
        if (current == null || info.location != ElementRenderLocation.WORLD_MAP
                || !current.level.dimension().equals(info.mapDimension) || !loaded(current, entity)) return;
        var position = entity.getPosition(current.partialTicks);
        AuiEntityPreview.Model model = capture(current, entity, position.x, position.y, position.z);
        if (model != null) current.models.put(entity.getUUID(), model);
    }

    private static AuiEntityPreview.Model capture(Frame current, Entity entity, double x, double y, double z) {
        return AuiEntityPreview.capture(entity, current.partialTicks, x, y, z);
    }

    private static boolean loaded(Frame current, Entity entity) {
        return entity != null && entity.level() == current.level && !entity.isRemoved()
                && current.level.getEntity(entity.getId()) == entity;
    }

    private static Frame activeFrame() {
        Frame current = frame;
        if (current == null || Minecraft.getInstance().gui.screen() != current.map
                || Minecraft.getInstance().level != current.level || !EarthPreferences.MODEL_INDICATORS.get()
                || !EarthClient.themed(current.map) || GuiMap.hiddenUI || !NativeMapTerrain.active()) return null;
        return current;
    }

    public static void draw(GuiMap map, Entity actor, RenderTarget target) {
        if (!EarthClient.themed(map) || !NativeMapTerrain.active()
                || target != Minecraft.getInstance().gameRenderer.mainRenderTarget()) return;
        Frame current = activeFrame();
        try {
            replaceActor(map, actor);
            if (current != null && current.map == map && !current.models.isEmpty()) {
                AuiMapEntityLayer.drawModels(NativeMapTerrain.camera(), current.models.values(), target);
            }
        } finally {
            NativeMapTerrain.finishTerrain();
        }
        if (current != null && current.map == map && !current.models.isEmpty()) {
            AuiMapEntityLayer.drawIndicators(NativeMapTerrain.camera(), current.models.values(), target);
        }
        NativeMapElementLayer.composite(target);
    }

    public static void end(GuiMap map, GuiGraphicsExtractor graphics) {
        if (frame != null && frame.map == map && frame.graphics == graphics) frame = null;
    }

    public static void close() {
        frame = null;
        AuiMapEntityLayer.close();
        NativeMapElementLayer.close();
    }

    private static final class Frame {
        private final GuiMap map;
        private final GuiGraphicsExtractor graphics;
        private final ClientLevel level;
        private final float partialTicks;
        private final Map<UUID, AuiEntityPreview.Model> models = new LinkedHashMap<>();
        private UUID actorId;

        private Frame(GuiMap map, GuiGraphicsExtractor graphics, ClientLevel level, float partialTicks) {
            this.map = map;
            this.graphics = graphics;
            this.level = level;
            this.partialTicks = partialTicks;
        }
    }
}
