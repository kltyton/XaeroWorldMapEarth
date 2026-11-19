package io.github.kltyton.xaeroearth.client.mixin.overlay;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.kltyton.xaeroearth.client.integration.xaero.highlight.PublishedHighlights;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xaero.map.highlight.AbstractHighlighter;
import xaero.map.highlight.DimensionHighlighterHandler;

/** Observes colors during the native provider call without invoking providers a second time. */
@Mixin(value = DimensionHighlighterHandler.class, remap = false)
abstract class XaeroHighlightColorMixin {
    @WrapOperation(method = "applyChunkHighlightColors", at = @At(value = "INVOKE",
            target = "Lxaero/map/highlight/AbstractHighlighter;getChunkHighlitColor"))
    private int[] earth$copyHighlight(AbstractHighlighter provider, ResourceKey<Level> dimension,
            int chunkX, int chunkZ, Operation<int[]> original) {
        int[] colors = original.call(provider, dimension, chunkX, chunkZ);
        PublishedHighlights.accept(chunkX, chunkZ, colors);
        return colors;
    }
}
