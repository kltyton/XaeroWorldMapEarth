package io.github.kltyton.xaeroearth.client.render.navigation;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** The terrain scan's trigger pose and animation, shared by the world render backends. */
public final class TerrainScan {
    private static volatile Profile profile = new Profile(4, 24, 10, 5, 28, 36, 1.45F, 360);
    private static Pulse pulse;
    private static int age;
    private static long tickTime;

    public record Profile(int burstTicks, int travelTicks, int fadeTicks, float burstRadius, float travelRadius,
                          float endRadius, float lineSpacing, float sectorDegrees) {
        public Profile {
            if (burstTicks < 1 || travelTicks < 1 || fadeTicks < 1 || !Float.isFinite(endRadius)
                    || !(burstRadius > 0 && burstRadius < travelRadius && travelRadius <= endRadius)
                    || !(lineSpacing > 0 && lineSpacing <= endRadius) || !(sectorDegrees > 0 && sectorDegrees <= 360)) {
                throw new IllegalArgumentException("Invalid terrain scan profile");
            }
        }
    }

    public record Frame(Vec3 origin, float forwardX, float forwardZ, float sectorCos,
                        float radius, float intensity, float spacing) {}

    private record Pulse(ClientLevel level, Vec3 origin, float forwardX, float forwardZ, Profile profile) {}

    /** Changes the animation of subsequent scans. The active scan keeps its trigger-time profile. */
    public static void setProfile(int burstTicks, int travelTicks, int fadeTicks, float burstRadius,
                                  float travelRadius, float endRadius, float lineSpacing, float sectorDegrees) {
        profile = new Profile(burstTicks, travelTicks, fadeTicks, burstRadius, travelRadius, endRadius, lineSpacing, sectorDegrees);
    }

    public static void start(ClientLevel level, Vec3 origin, float yaw) {
        double angle = Math.toRadians(yaw);
        pulse = new Pulse(level, origin, (float) -Math.sin(angle), (float) Math.cos(angle), profile);
        age = 0;
        tickTime = System.nanoTime();
    }

    public static void tick(ClientLevel level) {
        if (pulse == null) return;
        if (pulse.level != level) {
            stop();
            return;
        }
        age++;
        tickTime = System.nanoTime();
        Profile p = pulse.profile;
        if (age >= (long) p.burstTicks + p.travelTicks + p.fadeTicks) stop();
    }

    public static Frame frame(ClientLevel level, Vec3 camera) {
        if (pulse == null || pulse.level != level) return null;
        Profile p = pulse.profile;
        float elapsed = age + Mth.clamp((System.nanoTime() - tickTime) / 50_000_000.0F, 0, 1);
        float burst = Mth.clamp(elapsed / p.burstTicks, 0, 1);
        float travel = Mth.clamp((elapsed - p.burstTicks) / p.travelTicks, 0, 1);
        float fade = Mth.clamp((elapsed - p.burstTicks - p.travelTicks) / p.fadeTicks, 0, 1);
        float radius = p.burstRadius * easeOut(burst) + (p.travelRadius - p.burstRadius) * travel
                + (p.endRadius - p.travelRadius) * easeOut(fade);
        return new Frame(pulse.origin.subtract(camera), pulse.forwardX, pulse.forwardZ,
                p.sectorDegrees == 360 ? -2 : (float) Math.cos(Math.toRadians(p.sectorDegrees * 0.5)), radius,
                Math.min(1, burst * 2) * (1 - fade * fade * (3 - 2 * fade)), p.lineSpacing);
    }

    private static float easeOut(float progress) { return progress * (2 - progress); }

    public static void stop() { pulse = null; }

    private TerrainScan() {}
}
