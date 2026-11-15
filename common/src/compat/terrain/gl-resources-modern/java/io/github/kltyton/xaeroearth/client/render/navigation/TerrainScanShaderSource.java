package io.github.kltyton.xaeroearth.client.render.navigation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

final class TerrainScanShaderSource {
    static String read(Minecraft minecraft, String path) {
        var id = ResourceLocation.tryParse("xaeroearth:shaders/" + path);
        try (var input = minecraft.getResourceManager().getResourceOrThrow(id).open()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load terrain scan shader " + id, failure);
        }
    }

    private TerrainScanShaderSource() {}
}
