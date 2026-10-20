package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.render.entity.EntityPreview;
import io.github.kltyton.xaeroearth.client.render.entity.MapEntityLayer;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Native world and framebuffer access for map indicators. */
final class EntityIndicatorPlatform {
    static Level level(Entity entity) { return entity.level(); }
    static RenderTarget mainTarget() { return Minecraft.getInstance().getMainRenderTarget(); }
    static void drawModels(MapCamera camera, Collection<EntityPreview.Model> models, RenderTarget target) {
        MapEntityLayer.drawModels(camera, models, target);
    }
    private EntityIndicatorPlatform() { }
}
