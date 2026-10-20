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
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources.SpriteLayout;
import java.io.InputStream;

final class MapResourcePlatform {
    static final String FORMAT = "KUI-native-model-resources-1-minecraft-1.21.1";

    static Map<String, Resource> resources(ResourceManager manager) {
        Map<String, Resource> resources = new TreeMap<>();
        for (String directory : List.of("blockstates", "models", "textures", "atlases")) {
            manager.listResources(directory, id -> true).forEach((id, resource) ->
                    resources.put("assets/" + id.getNamespace() + "/" + id.getPath(), resource));
        }
        return resources;
    }

    static Map<String, SpriteLayout> sprites() {
        Map<String, SpriteLayout> sprites = new TreeMap<>();
        Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getTextures()
                .forEach((id, sprite) -> sprites.put(id.toString(), new SpriteLayout(sprite.getX(), sprite.getY(),
                        sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1())));
        return sprites;
    }

    static InputStream open(Resource resource) throws IOException {
        return resource.open();
    }


    private MapResourcePlatform() { }
}
