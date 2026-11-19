package io.github.kltyton.xaeroearth.client;

import io.github.kltyton.xaeroearth.client.ui.NativeMapInput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    public static final AtomicBoolean FAST_TERRAIN = new AtomicBoolean(Boolean.parseBoolean(VALUES.getProperty("fastTerrain", "false")));
    public static final AtomicBoolean SHADER_PACK = new AtomicBoolean(Boolean.parseBoolean(VALUES.getProperty("mapShaderPack", "false")));
    public static final AtomicBoolean BACKGROUND_WORLD_SAVE = new AtomicBoolean(
            Boolean.parseBoolean(VALUES.getProperty("backgroundWorldSave", "false")));
    public static final AtomicBoolean UNEXPLORED_FOG = new AtomicBoolean(
            Boolean.parseBoolean(VALUES.getProperty("unexploredFog", "true")));

    public static final AtomicBoolean MINIMAP_3D = new AtomicBoolean(Boolean.parseBoolean(VALUES.getProperty("minimap3d", "true")));
    public static final AtomicReference<Double> MINIMAP_ZOOM = new AtomicReference<>(readDecimal("minimapZoom", 1.0, 0.063, 50.0));
    public static final AtomicReference<Double> ISOMETRIC_YAW = new AtomicReference<>(readDecimal("isometricYaw", NativeMapInput.DEFAULT_ISOMETRIC_YAW, -180, 180));
    public static final AtomicReference<Double> ISOMETRIC_PITCH = new AtomicReference<>(
            readDecimal("isometricPitch", NativeMapInput.DEFAULT_ISOMETRIC_PITCH, 1, 90));
    public static final AtomicReference<Double> TOP_YAW = new AtomicReference<>(readDecimal("topYaw", 0, -180, 180));

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
        VALUES.setProperty("minimapZoom", Double.toString(MINIMAP_ZOOM.get()));
        VALUES.remove("freeYaw");
        VALUES.remove("freePitch");
        VALUES.setProperty("isometricYaw", Double.toString(ISOMETRIC_YAW.get()));
        VALUES.setProperty("isometricPitch", Double.toString(ISOMETRIC_PITCH.get()));
        VALUES.setProperty("topYaw", Double.toString(TOP_YAW.get()));
        VALUES.setProperty("isometricDetailDistance", Integer.toString(ISOMETRIC_DETAIL_DISTANCE.get()));
        VALUES.setProperty("fastTerrain", Boolean.toString(FAST_TERRAIN.get()));
        VALUES.setProperty("mapShaderPack", Boolean.toString(SHADER_PACK.get()));
        VALUES.setProperty("backgroundWorldSave", Boolean.toString(BACKGROUND_WORLD_SAVE.get()));
        VALUES.setProperty("unexploredFog", Boolean.toString(UNEXPLORED_FOG.get()));
        try {
            Files.createDirectories(FILE.getParent());
            var next = Files.createTempFile(FILE.getParent(), FILE.getFileName().toString(), ".tmp");
            try {
                try (var output = Files.newOutputStream(next)) { VALUES.store(output, "Xaero Earth client preferences"); }
                try { Files.move(next, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unavailable) {
                    Files.move(next, FILE, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(next); }
        } catch (IOException failure) { throw new UncheckedIOException("Cannot save " + FILE, failure); }
    }

    private EarthPreferences() { }
}
