package io.github.kltyton.xaeroearth.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import io.github.kltyton.xaeroearth.client.EarthClient;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import net.neoforged.bus.api.IEventBus;

@Mod(value = "xaeroearth", dist = Dist.CLIENT)
public final class EarthMod {
    public EarthMod(ModContainer container, IEventBus modBus) {
        container.registerConfig(ModConfig.Type.CLIENT, EarthPreferences.SPEC);
        EarthClient.initialize();
        modBus.addListener(EarthClient::registerReloadListener);
    }
}
