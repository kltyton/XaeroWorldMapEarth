package io.github.kltyton.xaeroearth.client.chunkmap.biome;

import net.minecraft.world.level.biome.BiomeManager;

/** Detached biome sampling state for one detailed terrain capture. */
public record BiomeTintSnapshot(BiomeManager biomes, int radius, int originX, int originZ,
                                int width, int depth, byte[] fingerprint) {
    public BiomeTintSnapshot {
        if (biomes == null || radius < 0 || width <= 0 || depth <= 0 || fingerprint == null)
            throw new IllegalArgumentException("Invalid biome tint snapshot");
        fingerprint = fingerprint.clone();
    }
    @Override public byte[] fingerprint() { return fingerprint.clone(); }
}
