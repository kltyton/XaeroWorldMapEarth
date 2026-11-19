package io.github.kltyton.xaeroearth.client.chunkmap;

import net.minecraft.client.Minecraft;

final class BiomeBlendOptions {
    static int radius() { return Minecraft.getInstance().options.biomeBlendRadius; }
    private BiomeBlendOptions() { }
}
