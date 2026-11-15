package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.ui.NativeMapInput;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class EarthPreferences {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue MODEL_INDICATORS;
    public static final ModConfigSpec.EnumValue<View> VIEW;
    public static final ModConfigSpec.IntValue ISOMETRIC_DETAIL_DISTANCE;

    public static final ModConfigSpec.BooleanValue MINIMAP_3D;
    public static final ModConfigSpec.DoubleValue MINIMAP_ZOOM_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue MINIMAP_FOLLOW_WORLD_ZOOM;
    public static final ModConfigSpec.DoubleValue ISOMETRIC_YAW;
    public static final ModConfigSpec.DoubleValue ISOMETRIC_PITCH;

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
        MINIMAP_3D = builder.comment("Render the Earth terrain in Xaero's Minimap.")
                .define("minimap3d", true);
        MINIMAP_ZOOM_MULTIPLIER = builder.comment("Multiply Xaero's native minimap zoom for Earth terrain. 1.0 uses Xaero's scale unchanged.")
                .defineInRange("minimapZoomMultiplier", NativeMapInput.DEFAULT_MINIMAP_ZOOM_MULTIPLIER, 0.5, 3.0);
        MINIMAP_FOLLOW_WORLD_ZOOM = builder.comment("Allow world map zoom to make a bounded adjustment to Earth minimap framing.")
                .define("minimapFollowWorldZoom", false);
        ISOMETRIC_YAW = builder.comment("Isometric camera yaw in degrees.")
                .defineInRange("isometricYaw", NativeMapInput.DEFAULT_ISOMETRIC_YAW, -180.0, 180.0);
        ISOMETRIC_PITCH = builder.comment("Isometric camera depression below the horizon in degrees.")
                .defineInRange("isometricPitch", NativeMapInput.DEFAULT_ISOMETRIC_PITCH, 1.0, 90.0);
        SPEC = builder.build();
    }

    public static void save() { SPEC.save(); }

    private EarthPreferences() {}
}
