package io.github.kltyton.xaeroearth.client.chunkmap;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources.SpriteLayout;
import java.io.InputStream;
import java.io.FilterInputStream;

final class MapResourcePlatform {
    static final String FORMAT = "KUI-native-model-resources-1-minecraft-1.18.2";

    static Map<String, Resource> resources(ResourceManager manager) {
        Map<String, Resource> resources = new TreeMap<>();
        for (String directory : List.of("blockstates", "models", "textures", "atlases")) {
            for (ResourceLocation id : manager.listResources(directory,
                    path -> isActiveMapResource(directory, path))) {
                try {
                    resources.put("assets/" + id.getNamespace() + "/" + id.getPath(), manager.getResource(id));
                } catch (IOException failure) {
                    throw new IllegalStateException("Cannot enumerate active map resource " + id, failure);
                }
            }
        }
        return resources;
    }

    static Map<String, SpriteLayout> sprites() {
        Map<String, SpriteLayout> sprites = new TreeMap<>();
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        atlas.texturesByName.keySet().forEach(id -> {
            var sprite = atlas.getSprite(id);
            sprites.put(id.toString(), new SpriteLayout(sprite.getX(), sprite.getY(),
                    sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1()));
        });
        return sprites;
    }

    static InputStream open(Resource resource) throws IOException {
        return new FilterInputStream(resource.getInputStream()) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { resource.close(); }
            }
        };
    }

    private static boolean isActiveMapResource(String directory, String path) {
        return switch (directory) {
            case "blockstates", "models", "atlases" -> path.endsWith(".json");
            case "textures" -> path.endsWith(".png") || path.endsWith(".png.mcmeta");
            default -> false;
        };
    }

    private MapResourcePlatform() { }
}
