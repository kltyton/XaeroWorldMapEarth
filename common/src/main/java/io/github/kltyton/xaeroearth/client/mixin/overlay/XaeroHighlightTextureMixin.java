package io.github.kltyton.xaeroearth.client.mixin.overlay;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.kltyton.xaeroearth.client.integration.xaero.highlight.PublishedHighlights;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import xaero.map.highlight.DimensionHighlighterHandler;
import xaero.map.pool.buffer.PoolTextureDirectBufferUnit;
import xaero.map.region.texture.LeafRegionTexture;

/** Keeps the overlay stamped to the same leaf texture as the published Xaero map data. */
@Mixin(value = LeafRegionTexture.class, remap = false)
abstract class XaeroHighlightTextureMixin implements PublishedHighlights.Texture {
    @Unique private volatile List<PublishedHighlights.Chunk> earth$highlights = List.of();

    @WrapMethod(method = "applyHighlights")
    private PoolTextureDirectBufferUnit earth$captureHighlights(DimensionHighlighterHandler handler,
            PoolTextureDirectBufferUnit buffer, boolean copy, Operation<PoolTextureDirectBufferUnit> original) {
        if (!((LeafRegionTexture) (Object) this).getTileChunk().hasHighlights()) {
            var result = original.call(handler, buffer, copy);
            earth$highlights = List.of();
            return result;
        }
        try (var publication = new PublishedHighlights()) {
            var result = original.call(handler, buffer, copy);
            earth$highlights = publication.snapshot();
            return result;
        }
    }

    @Override public List<PublishedHighlights.Chunk> earth$publishedHighlights() { return earth$highlights; }
}
