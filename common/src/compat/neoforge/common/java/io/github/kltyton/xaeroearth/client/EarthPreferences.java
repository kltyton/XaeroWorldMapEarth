package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.ui.NativeMapInput;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class EarthPreferences {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue MODEL_INDICATORS;
    public static final ModConfigSpec.EnumValue<View> VIEW;
    public static final ModConfigSpec.IntValue ISOMETRIC_DETAIL_DISTANCE;
    public static final ModConfigSpec.BooleanValue FAST_TERRAIN;
    public static final ModConfigSpec.BooleanValue SHADER_PACK;
    public static final ModConfigSpec.BooleanValue BACKGROUND_WORLD_SAVE;
    public static final ModConfigSpec.BooleanValue UNEXPLORED_FOG;

    public static final ModConfigSpec.BooleanValue MINIMAP_3D;
    public static final ModConfigSpec.DoubleValue MINIMAP_ZOOM;
    public static final ModConfigSpec.DoubleValue ISOMETRIC_YAW;
    public static final ModConfigSpec.DoubleValue ISOMETRIC_PITCH;
    public static final ModConfigSpec.DoubleValue TOP_YAW;

    public enum View { ISOMETRIC, TOP, STREET }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ENABLED = builder.comment("Apply the Ore-McUI theme to Xaero's existing map interface.")
                .define("enabled", true);
        MODEL_INDICATORS = builder.comment("Show actual locally loaded entity models for map actor and tracked-player indicators.")
                .define("modelIndicators", true);
        VIEW = builder.comment("Map camera: ISOMETRIC, TOP or STREET.")
                .defineEnum("view", View.ISOMETRIC);
        ISOMETRIC_DETAIL_DISTANCE = builder.comment("Maximum detailed terrain radius in blocks around the active map focus. Distant mapped terrain remains visible.")
                .defineInRange("isometricDetailDistance", 512, 64, 1536);
        FAST_TERRAIN = builder.comment("Use smaller high-detail terrain bounds and lower resolution far terrain in Earth.")
                .define("fastTerrain", false);
        SHADER_PACK = builder.comment("Use a compatible Iris/Oculus shader pack for Earth map terrain.")
                .define("mapShaderPack", false);
        BACKGROUND_WORLD_SAVE = builder.comment("Read nearby existing surface regions in the background in a local singleplayer world-save map.")
                .define("backgroundWorldSave", false);
        UNEXPLORED_FOG = builder.comment("Show a cloud layer in blank areas of the Earth map.").define("unexploredFog", true);
        MINIMAP_3D = builder.comment("Render the Earth terrain in Xaero's Minimap.")
                .define("minimap3d", true);
        MINIMAP_ZOOM = builder.comment("Earth magnification relative to Xaero's original minimap range.")
                .defineInRange("minimapZoom", 1.0, 0.063, 50.0);
        ISOMETRIC_YAW = builder.comment("Isometric camera yaw in degrees.")
                .defineInRange("isometricYaw", NativeMapInput.DEFAULT_ISOMETRIC_YAW, -180.0, 180.0);
        ISOMETRIC_PITCH = builder.comment("Isometric camera depression below the horizon in degrees.")
                .defineInRange("isometricPitch", NativeMapInput.DEFAULT_ISOMETRIC_PITCH, 1.0, 90.0);
        TOP_YAW = builder.comment("Top-down camera yaw in degrees.").defineInRange("topYaw", 0.0, -180.0, 180.0);
        SPEC = builder.build();
    }

    public static void save() { SPEC.save(); }

    private EarthPreferences() {}
}
