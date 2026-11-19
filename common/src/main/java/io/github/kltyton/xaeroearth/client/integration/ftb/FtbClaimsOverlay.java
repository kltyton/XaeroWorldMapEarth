package io.github.kltyton.xaeroearth.client.integration.ftb;

import com.mojang.logging.LogUtils;
import io.github.kltyton.xaeroearth.client.ClientScreen;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/** Samples already loaded FTB client claims. It never creates chunks, opens FTB region files, or calls its server API. */
public final class FtbClaimsOverlay {
    private static final String MAP = "dev.ftb.mods.ftbchunks.client.map.";
    private static final String XZ = "dev.ftb.mods.ftblibrary.math.XZ";
    private static final int RADIUS = 12;
    public record Claim(int x, int z, UUID owner, int rgb) { }
    private record Access(MethodHandle current, MethodHandle manager, MethodHandle lock, MethodHandle regions,
                          MethodHandle regionPos, MethodHandle x, MethodHandle z, MethodHandle loaded,
                          MethodHandle chunk, MethodHandle xz, MethodHandle team, MethodHandle id,
                          MethodHandle coloredName) { }
    private record Snapshot(MapScope scope, List<Claim> claims) { }
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Earth FTB claims");
        thread.setDaemon(true);
        return thread;
    });
    private static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();
    private static Access access;
    private static boolean resolved;
    private static int ticks;
    private static volatile Snapshot snapshot = new Snapshot(null, List.of());

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) { reset(); return; }
        if (!resolved) { resolved = true; access = link(); }
        if (access == null || ++ticks % 40 != 1) return;
        var session = WorldMapSession.getCurrentSession();
        var processor = session == null ? null : session.getMapProcessor();
        var selected = MapScope.selected(processor);
        if (selected == null || selected.layer() != Integer.MAX_VALUE || processor.getWorld() != mc.level
                || !processor.isCurrentMultiworldWritable() || processor.isConsideringNetherFairPlay() || processor.getMapWorld() == null
                || !mc.level.dimension().equals(processor.getMapWorld().getCurrentDimensionId())) { reset(); return; }
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        int playerX = Math.floorDiv(mc.player.blockPosition().getX(), 16);
        int playerZ = Math.floorDiv(mc.player.blockPosition().getZ(), 16);
        int focusX = playerX, focusZ = playerZ;
        if (ClientScreen.screen(mc) instanceof GuiMap map && map.getMapProcessor() == processor) {
            var camera = (GuiMapAccess) map;
            focusX = (int) Math.floor(camera.earth$cameraX() / 16);
            focusZ = (int) Math.floor(camera.earth$cameraZ() / 16);
        }
        Access currentAccess = access;
        long ticket = GENERATION.get();
        int fx = focusX, fz = focusZ;
        // Read FTB's locked client cache off the render thread so background region I/O cannot stall map frames.
        WORKER.execute(() -> {
            try {
                if (ticket != GENERATION.get()) return;
                Object dimension = unwrap(currentAccess.current.invoke());
                if (dimension == null) {
                    if (ticket == GENERATION.get()) snapshot = new Snapshot(null, List.of());
                    return;
                }
                var visible = new ArrayList<Claim>();
                var teamColors = new java.util.HashMap<UUID, Integer>();
                Object lock = currentAccess.lock.invoke(currentAccess.manager.invoke(dimension));
                synchronized (lock) {
                    if (ticket != GENERATION.get()) return;
                    for (Object region : (Collection<?>) currentAccess.regions.invoke(dimension)) {
                        if (!(boolean) currentAccess.loaded.invoke(region)) continue;
                        Object pos = currentAccess.regionPos.invoke(region);
                        int minX = (int) currentAccess.x.invoke(pos) * 32;
                        int minZ = (int) currentAccess.z.invoke(pos) * 32;
                        if (!intersects(minX, minZ, fx, fz) && !intersects(minX, minZ, playerX, playerZ)) continue;
                        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                            int cx = minX + x, cz = minZ + z;
                            if (!near(cx, cz, fx, fz) && !near(cx, cz, playerX, playerZ)) continue;
                            Object chunk = currentAccess.chunk.invoke(region, currentAccess.xz.invoke(x, z));
                            if (chunk == null) continue;
                            Object team = unwrap(currentAccess.team.invoke(chunk));
                            if (team == null) continue;
                            UUID owner = (UUID) currentAccess.id.invoke(team);
                            if (owner == null) continue;
                            Integer rgb = teamColors.get(owner);
                            if (rgb == null) {
                                Component name = (Component) currentAccess.coloredName.invoke(team);
                                var color = name == null ? null : name.getStyle().getColor();
                                rgb = color == null ? java.awt.Color.HSBtoRGB((owner.hashCode() & 0xffff) / 65536F, 0.63F, 0.88F)
                                        & 0xffffff : color.getValue() & 0xffffff;
                                teamColors.put(owner, rgb);
                            }
                            visible.add(new Claim(cx, cz, owner, rgb));
                        }
                    }
                }
                if (ticket != GENERATION.get()) return;
                visible.sort(java.util.Comparator.comparingInt(Claim::z).thenComparingInt(Claim::x));
                var previous = snapshot;
                List<Claim> nextClaims = previous.claims().equals(visible) ? previous.claims() : List.copyOf(visible);
                snapshot = new Snapshot(selected, nextClaims);
            } catch (Throwable failure) {
                if (failure instanceof Error && !(failure instanceof LinkageError)) throw (Error) failure;
                LogUtils.getLogger().warn("Cannot read FTB Chunks client claims; Earth FTB overlay was stopped", failure);
                Minecraft.getInstance().execute(() -> {
                    access = null;
                    reset();
                });
            } finally {
                IN_FLIGHT.set(false);
            }
        });
    }

    private static Access link() {
        var loader = FtbClaimsOverlay.class.getClassLoader();
        Class<?> dimension;
        try { dimension = Class.forName(MAP + "MapDimension", false, loader); }
        catch (ClassNotFoundException absent) { return null; }
        catch (LinkageError failure) {
            LogUtils.getLogger().warn("Cannot load the FTB Chunks client map", failure);
            return null;
        }
        // FTB's advertised client API does not expose claims. Only its existing
        // client cache getters are bound, so older/newer return types can coexist
        // without a required loader-specific dependency or edits to FTB's refresh.
        try {
            var access = MethodHandles.publicLookup();
            var manager = getter(access, dimension, "getManager", "manager");
            Class<?> region = Class.forName(MAP + "MapRegion", false, loader);
            Class<?> chunk = Class.forName(MAP + "MapChunk", false, loader);
            Class<?> xz = Class.forName(XZ, false, loader);
            var team = chunk.getMethod("getTeam");
            Class<?> teamType = team.getReturnType() != Optional.class ? team.getReturnType()
                    : Class.forName("dev.ftb.mods.ftbteams.api.Team", false, loader);
            return new Access(access.unreflect(dimension.getMethod("getCurrent")), manager,
                    access.unreflectGetter(manager.type().returnType().getField("lock")),
                    access.unreflect(dimension.getMethod("getLoadedRegions")), access.unreflectGetter(region.getField("pos")),
                    getter(access, xz, "x", "x"), getter(access, xz, "z", "z"),
                    access.unreflect(region.getMethod("isDataLoaded")),
                    access.unreflect(region.getMethod("getMapChunk", xz)),
                    access.unreflectConstructor(xz.getConstructor(int.class, int.class)),
                    access.unreflect(team), access.unreflect(teamType.getMethod("getId")),
                    access.unreflect(teamType.getMethod("getColoredName")));
        } catch (ReflectiveOperationException | LinkageError failure) {
            LogUtils.getLogger().warn("The installed FTB Chunks client cache does not match Earth's claim adapter", failure);
            return null;
        }
    }

    private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> type, String method, String field)
            throws ReflectiveOperationException {
        try { return lookup.unreflect(type.getMethod(method)); }
        catch (NoSuchMethodException legacy) { return lookup.unreflectGetter(type.getField(field)); }
    }

    private static boolean near(int x, int z, int ox, int oz) {
        return Math.abs(x - ox) <= RADIUS && Math.abs(z - oz) <= RADIUS;
    }
    private static boolean intersects(int minX, int minZ, int x, int z) {
        return minX <= x + RADIUS && minX + 31 >= x - RADIUS && minZ <= z + RADIUS && minZ + 31 >= z - RADIUS;
    }
    private static Object unwrap(Object value) { return value instanceof Optional<?> optional ? optional.orElse(null) : value; }
    public static List<Claim> forMap(MapScope selected) {
        var current = snapshot;
        return selected != null && selected.equals(current.scope()) ? current.claims() : List.of();
    }
    public static void reset() { GENERATION.incrementAndGet(); snapshot = new Snapshot(null, List.of()); ticks = 0; }
    private FtbClaimsOverlay() { }
}
