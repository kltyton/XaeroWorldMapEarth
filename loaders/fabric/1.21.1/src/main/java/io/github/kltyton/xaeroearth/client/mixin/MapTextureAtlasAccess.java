package io.github.kltyton.xaeroearth.client.mixin;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the uploaded atlas layout for map resource fingerprints. */
@Mixin(TextureAtlas.class)
public interface MapTextureAtlasAccess {
    @Accessor("texturesByName")
    Map<ResourceLocation, TextureAtlasSprite> earth$mapSprites();
}
