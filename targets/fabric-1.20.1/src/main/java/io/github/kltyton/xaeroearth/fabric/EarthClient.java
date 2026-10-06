package io.github.kltyton.xaeroearth.fabric;

import net.fabricmc.api.ClientModInitializer;

public final class EarthClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        io.github.kltyton.xaeroearth.client.EarthClient.initialize();
    }
}
