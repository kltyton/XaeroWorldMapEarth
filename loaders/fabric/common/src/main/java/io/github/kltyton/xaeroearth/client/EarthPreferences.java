package io.github.kltyton.xaeroearth.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.FabricLoader;

public final class EarthPreferences {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("xaeroearth-client.properties");
    private static final Properties VALUES = load();
    public static final AtomicBoolean ENABLED = new AtomicBoolean(Boolean.parseBoolean(VALUES.getProperty("enabled", "true")));
    public static final AtomicBoolean MODEL_INDICATORS = new AtomicBoolean(Boolean.parseBoolean(VALUES.getProperty("modelIndicators", "true")));
    public static final AtomicReference<View> VIEW = new AtomicReference<>(View.valueOf(VALUES.getProperty("view", "ISOMETRIC")));

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
        try {
            Files.createDirectories(FILE.getParent());
            try (var output = Files.newOutputStream(FILE)) { VALUES.store(output, "Xaero Earth client preferences"); }
        } catch (IOException failure) { throw new UncheckedIOException("Cannot save " + FILE, failure); }
    }

    private EarthPreferences() { }
}
