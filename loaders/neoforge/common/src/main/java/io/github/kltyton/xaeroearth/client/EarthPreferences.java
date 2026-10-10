package io.github.kltyton.xaeroearth.client;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class EarthPreferences {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue MODEL_INDICATORS;
    public static final ModConfigSpec.EnumValue<View> VIEW;

    public enum View { ISOMETRIC, TOP, STREET }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
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
