package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.ui.NativeMapInput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;

public final class EarthPreferences {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("xaeroearth-client.properties");
    private static final Properties VALUES = load();
    public static final AtomicBoolean ENABLED = new AtomicBoolean(Boolean.parseBoolean(VALUES.getProperty("enabled", "true")));
    public static final AtomicBoolean MODEL_INDICATORS = new AtomicBoolean(Boolean.parseBoolean(VALUES.getProperty("modelIndicators", "true")));
    public static final AtomicReference<View> VIEW = new AtomicReference<>(View.valueOf(VALUES.getProperty("view", "ISOMETRIC")));

    public static final AtomicInteger ISOMETRIC_DETAIL_DISTANCE = new AtomicInteger(
            Math.max(64, Math.min(1536, Integer.parseInt(VALUES.getProperty("isometricDetailDistance", "512")))));

    public static final AtomicBoolean MINIMAP_3D = new AtomicBoolean(Boolean.parseBoolean(VALUES.getProperty("minimap3d", "true")));
    public static final AtomicReference<Double> MINIMAP_ZOOM_MULTIPLIER = new AtomicReference<>(
            readDecimal("minimapZoomMultiplier", NativeMapInput.DEFAULT_MINIMAP_ZOOM_MULTIPLIER, 0.5, 3));
    public static final AtomicBoolean MINIMAP_FOLLOW_WORLD_ZOOM = new AtomicBoolean(
            Boolean.parseBoolean(VALUES.getProperty("minimapFollowWorldZoom", "false")));
    public static final AtomicReference<Double> ISOMETRIC_YAW = new AtomicReference<>(readDecimal("isometricYaw", NativeMapInput.DEFAULT_ISOMETRIC_YAW, -180, 180));
    public static final AtomicReference<Double> ISOMETRIC_PITCH = new AtomicReference<>(
            readDecimal("isometricPitch", NativeMapInput.DEFAULT_ISOMETRIC_PITCH, 1, 90));

    private static double readDecimal(String key, double fallback, double min, double max) {
        try {
            double value = Double.parseDouble(VALUES.getProperty(key, Double.toString(fallback)));
            return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    public enum View { ISOMETRIC, TOP, STREET }

    private static Properties load() {
        Properties result = new Properties();
        if (Files.isRegularFile(FILE)) {
            try (var input = Files.newInputStream(FILE)) { result.load(input); }
            catch (IOException failure) { throw new UncheckedIOException("Cannot read " + FILE, failure); }
        }
        return result;
    }

    public static void save() {
        VALUES.setProperty("enabled", Boolean.toString(ENABLED.get()));
        VALUES.setProperty("modelIndicators", Boolean.toString(MODEL_INDICATORS.get()));
        VALUES.setProperty("view", VIEW.get().name());
        VALUES.setProperty("minimap3d", Boolean.toString(MINIMAP_3D.get()));
        VALUES.setProperty("minimapZoomMultiplier", Double.toString(MINIMAP_ZOOM_MULTIPLIER.get()));
        VALUES.setProperty("minimapFollowWorldZoom", Boolean.toString(MINIMAP_FOLLOW_WORLD_ZOOM.get()));
        VALUES.remove("freeYaw");
        VALUES.remove("freePitch");
        VALUES.setProperty("isometricYaw", Double.toString(ISOMETRIC_YAW.get()));
        VALUES.setProperty("isometricPitch", Double.toString(ISOMETRIC_PITCH.get()));
        VALUES.setProperty("isometricDetailDistance", Integer.toString(ISOMETRIC_DETAIL_DISTANCE.get()));
        try {
            Files.createDirectories(FILE.getParent());
            try (var output = Files.newOutputStream(FILE)) { VALUES.store(output, "Xaero Earth client preferences"); }
        } catch (IOException failure) { throw new UncheckedIOException("Cannot save " + FILE, failure); }
    }

    private EarthPreferences() { }
}
