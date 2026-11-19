package io.github.kltyton.xaeroearth.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.xaeroearth.client.ui.NativeMinimapTerrain;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.graphics.ImprovedFramebuffer;
import xaero.common.graphics.CustomVertexConsumers;
import xaero.common.minimap.MinimapProcessor;
import xaero.common.minimap.render.MinimapRenderer;
import xaero.hud.minimap.module.MinimapSession;

/** Supplies 3D terrain before Xaero draws its grid and composites the HUD. */
@Pseudo
@Mixin(targets = "xaero.common.minimap.render.MinimapFBORenderer", remap = false)
public abstract class MinimapTerrainMixin {
    @Shadow private ImprovedFramebuffer scalingFramebuffer;
    @Shadow private ImprovedFramebuffer rotationFramebuffer;
    @Unique private ImprovedFramebuffer earth$filteredFramebuffer;

    @Inject(method = "renderChunksToFBO", at = @At(value = "FIELD",
            target = "Lxaero/hud/minimap/common/config/option/MinimapProfiledConfigOptions;CHUNK_GRID:Lxaero/lib/common/config/option/RangeConfigOption;"))
    private void earth$terrain(MinimapSession session, PoseStack graphics, MinimapProcessor processor,
                               Vec3 center, ResourceKey<Level> dimension, double dimensionScale, int viewWidth, float partialTicks,
                               int light, boolean useWorldMap, boolean lockedNorth, int shape,
                               double sine, double cosine, boolean cave, CustomVertexConsumers buffers,
                               CallbackInfo callback) {
        if (scalingFramebuffer != null)
            NativeMinimapTerrain.draw(scalingFramebuffer, center, ((MinimapRenderer) (Object) this).getZoom(),
                    viewWidth, dimension, cave, lockedNorth, shape, sine, cosine);
    }

    @Inject(method = "renderChunksToFBO", at = @At("RETURN"))
    private void earth$finish(CallbackInfo callback) {
        // The previous pass still follows Xaero's anti-aliasing selection. Do not blur those
        // already sampled pixels a second time when enlarging the 3D HUD to the GUI.
        if (rotationFramebuffer != earth$filteredFramebuffer) earth$filteredFramebuffer = null;
        if (rotationFramebuffer != null && NativeMinimapTerrain.painted()) {
            if (earth$filteredFramebuffer == null) {
                rotationFramebuffer.setFilterMode(GL11.GL_NEAREST);
                earth$filteredFramebuffer = rotationFramebuffer;
            }
        } else if (earth$filteredFramebuffer != null) {
            // Xaero allocates the rotation texture with linear filtering.
            earth$filteredFramebuffer.setFilterMode(GL11.GL_LINEAR);
            earth$filteredFramebuffer = null;
        }
        NativeMinimapTerrain.finishMarkers();
    }
}
