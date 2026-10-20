package io.github.kltyton.xaeroearth.client.chunkmap;

import net.minecraft.client.Minecraft;

final class FrameMetrics {
    static int fps() { return Minecraft.getInstance().getFps(); }
    static double frameMillis() {
        return Minecraft.getInstance().getFrameTimeNs() / 1_000_000.0;
    }
    private FrameMetrics() { }
}
