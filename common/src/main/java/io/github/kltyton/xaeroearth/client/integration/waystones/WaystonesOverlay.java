package io.github.kltyton.xaeroearth.client.integration.waystones;

import com.mojang.logging.LogUtils;
import io.github.kltyton.xaeroearth.client.mapmark.MapMark;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import xaero.map.WorldMapSession;

/** Reads only Waystones' locally synchronized activation list, never its global server database. */
public final class WaystonesOverlay {
    private static final String ROOT = "net.blay09.mods.waystones.";
    private record Access(MethodHandle activated, MethodHandle id, MethodHandle dimension, MethodHandle pos, MethodHandle name) { }
    private static Access access;
    private static boolean resolved;
    private static int ticks;
    private static MapScope scope;
    private static List<MapMark> visible = List.of();

    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) { reset(); return; }
        if (!resolved) {
            resolved = true;
            access = link();
        }
        if (access == null || ++ticks % 20 != 1) return;
        var session = WorldMapSession.getCurrentSession();
        var processor = session == null ? null : session.getMapProcessor();
        var mapped = MapScope.selected(processor);
        if (mapped == null || mapped.layer() != Integer.MAX_VALUE
                || processor.getWorld() != minecraft.level || !processor.isCurrentMultiworldWritable()
                || processor.isConsideringNetherFairPlay()
                || processor.getMapWorld() == null) { reset(); return; }
        var dimension = processor.getMapWorld().getCurrentDimensionId();
        if (dimension == null) { reset(); return; }
        try {
            Collection<?> active = (Collection<?>) access.activated.invoke(minecraft.player);
            var result = new ArrayList<MapMark>();
            for (Object stone : active) {
                if (!dimension.equals(access.dimension.invoke(stone))) continue;
                BlockPos pos = (BlockPos) access.pos.invoke(stone);
                UUID id = (UUID) access.id.invoke(stone);
                if (pos == null || id == null) continue;
                var rawName = access.name.invoke(stone);
                String name = rawName instanceof Component component ? component.getString()
                        : rawName instanceof String plain ? plain : "";
                name = name.strip();
                if (name.length() > MapMark.TEXT_LIMIT) name = name.substring(0, MapMark.TEXT_LIMIT);
                if (Math.abs((long) pos.getX()) > 30_000_001 || Math.abs((long) pos.getZ()) > 30_000_001
                        || Math.abs((long) pos.getY()) > 65_534) continue;
                result.add(new MapMark(id, MapMark.Kind.WAYSTONE,
                        List.of(new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5)), name));
            }
            scope = mapped;
            if (!visible.equals(result)) visible = List.copyOf(result);
        } catch (Throwable failure) {
            if (failure instanceof Error && !(failure instanceof LinkageError)) throw (Error) failure;
            LogUtils.getLogger().warn("Cannot read the synchronized Waystones list; Earth Waystones overlay was stopped", failure);
            access = null;
            reset();
        }
    }

    private static Access link() {
        var loader = WaystonesOverlay.class.getClassLoader();
        Class<?> api;
        try { api = Class.forName(ROOT + "api.WaystonesAPI", false, loader); }
        catch (ClassNotFoundException absent) { return null; }
        catch (LinkageError failure) {
            LogUtils.getLogger().warn("Cannot load the Waystones API", failure);
            return null;
        }
        // The Forge 1.18/1.19 public manager and the newer public API expose the
        // same client-side list, but are not present in the same releases.
        // Bind only these public methods once; no foreign-type mixin or private lookup.
        try {
            var publicAccess = MethodHandles.publicLookup();
            var read = tryActivated(api, loader, publicAccess);
            Class<?> stone;
            try { stone = Class.forName(ROOT + "api.Waystone", false, loader); }
            catch (ClassNotFoundException legacy) { stone = Class.forName(ROOT + "api.IWaystone", false, loader); }
            return new Access(read, publicAccess.unreflect(stone.getMethod("getWaystoneUid")),
                    publicAccess.unreflect(stone.getMethod("getDimension")),
                    publicAccess.unreflect(stone.getMethod("getPos")), publicAccess.unreflect(stone.getMethod("getName")));
        } catch (ReflectiveOperationException | LinkageError failure) {
            LogUtils.getLogger().warn("The installed Waystones API does not match Earth's activated-list adapter", failure);
            return null;
        }
    }

    private static MethodHandle tryActivated(Class<?> api, ClassLoader loader, MethodHandles.Lookup lookup)
            throws ReflectiveOperationException {
        try { return lookup.unreflect(api.getMethod("getActivatedWaystones", Player.class)); }
        catch (NoSuchMethodException legacy) {
            var manager = Class.forName(ROOT + "core.PlayerWaystoneManager", false, loader);
            return lookup.unreflect(manager.getMethod("getWaystones", Player.class));
        }
    }

    public static List<MapMark> forMap(MapScope selected) {
        return selected != null && selected.equals(scope) ? visible : List.of();
    }

    public static void reset() { scope = null; visible = List.of(); ticks = 0; }
    private WaystonesOverlay() { }
}
