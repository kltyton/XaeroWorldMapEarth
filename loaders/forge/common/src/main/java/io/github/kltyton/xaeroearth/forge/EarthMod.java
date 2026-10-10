package io.github.kltyton.xaeroearth.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("xaeroearth")
public final class EarthMod {
    public EarthMod() {
        if (FMLEnvironment.dist == Dist.CLIENT) Client.initialize();
    }

    private static final class Client {
        static void initialize() {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT,
                    io.github.kltyton.xaeroearth.client.EarthPreferences.SPEC);
            io.github.kltyton.xaeroearth.client.EarthClient.initialize();
            FMLJavaModLoadingContext.get().getModEventBus().addListener(
                    io.github.kltyton.xaeroearth.client.EarthClient::registerReloadListener);
        }
    }
}
