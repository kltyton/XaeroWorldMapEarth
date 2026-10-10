package io.github.kltyton.xaeroearth.client;

import net.minecraftforge.common.ForgeConfigSpec;

public final class EarthPreferences {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue MODEL_INDICATORS;
    public static final ForgeConfigSpec.EnumValue<View> VIEW;

    public enum View { ISOMETRIC, TOP, STREET }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ENABLED = builder.comment("Apply the Ore-McUI theme to Xaero's existing map interface.")
                .define("enabled", true);
        MODEL_INDICATORS = builder.comment("Show actual locally loaded entity models for map actor and tracked-player indicators.")
                .define("modelIndicators", true);
        VIEW = builder.comment("Map camera: ISOMETRIC, TOP or STREET.")
                .defineEnum("view", View.ISOMETRIC);
        SPEC = builder.build();
    }

    private EarthPreferences() {}
}
