package io.github.kltyton.xaeroearth.client.chunkmap;

import net.minecraft.client.Minecraft;

final class FrameMetrics {
    static int fps() { return Minecraft.fps; }
    static double frameMillis() {
        var timer = Minecraft.getInstance().getFrameTimer();
        var log = timer.getLog();
        return log[Math.floorMod(timer.getLogEnd() - 1, log.length)] / 1_000_000.0;
    }
    private FrameMetrics() { }
}
