package io.github.kltyton.xaeroearth.client.ui.minimap;

import com.mojang.logging.LogUtils;
import io.github.kltyton.xaeroearth.client.ClientScreen;
import io.github.kltyton.xaeroearth.client.EarthPreferences;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Earth magnification relative to Xaero's current 2D minimap framing. */
public final class MinimapFraming {
    public static final String TOOLTIP = "xaeroearth.minimap.framing.tooltip";
    public static final int SLIDER_STEPS = 1000;
    private static boolean pending;
    private static double zoom;
    private static long saveAfter;

    public static double value() {
        return pending ? zoom : EarthPreferences.MINIMAP_ZOOM.get();
    }

    public static double sliderValue(int index) {
        int step = Math.max(0, Math.min(SLIDER_STEPS, index));
        double value = step <= 500
                ? 0.063 * Math.pow(1.0 / 0.063, step / 500.0)
                : Math.pow(50.0, (step - 500) / 500.0);
        return Math.round(value * 1000.0) / 1000.0;
    }

    public static double sliderCurrent() {
        double current = value();
        double position = current <= 1.0
                ? 500.0 * Math.log(current / 0.063) / Math.log(1.0 / 0.063)
                : 500.0 + 500.0 * Math.log(current) / Math.log(50.0);
        return sliderValue((int) Math.round(position));
    }

    public static Component label(double value) {
        return ClientScreen.literal(String.format(Locale.ROOT, "%.3fx", value));
    }

    public static boolean enabled() {
        return EarthPreferences.ENABLED.get() && EarthPreferences.MINIMAP_3D.get();
    }

    public static void set(double value) {
        if (!Double.isFinite(value)) return;
        zoom = Math.max(0.063, Math.min(50.0, value));
        pending = true;
        saveAfter = System.nanoTime() + 500_000_000L;
    }

    public static void flush() { flush(false); }

    public static void flush(boolean immediate) {
        if (!pending || !immediate && System.nanoTime() < saveAfter) return;
        saveAfter = System.nanoTime() + 500_000_000L;
        try {
            EarthPreferences.MINIMAP_ZOOM.set(zoom);
            EarthPreferences.save();
            pending = false;
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            if (!(cause instanceof java.io.IOException)) throw failure;
            LogUtils.getLogger().warn("Could not save minimap zoom; will retry", failure);
        }
    }

    private MinimapFraming() { }
}
