package io.github.kltyton.xaeroearth.client.bridge;

import io.github.kltyton.xaeroearth.client.EarthPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.MapSaveLoad;
import xaero.map.file.RegionDetection;

/** Queues native world-save reads at idle priority; never requests Minecraft chunk generation. */
public final class WorldSavePrefetch {
    private record Offset(int x, int z) { }
    private record Scope(MapProcessor processor, String world, String dimension, String subworld) { }
    private static final List<Offset> NEAR_FIRST = offsets();
    private static final long COOLDOWN = 450_000_000L;
    private static final long RESCAN = 30_000_000_000L;
    private final Set<Long> visited = new HashSet<>();
    private Scope scope;
    private Iterator<RegionDetection> remaining;
    private int nearX, nearZ, next;
    private long retryAt, rescanAt;

    public void tick(Minecraft minecraft) {
        if (!EarthPreferences.BACKGROUND_WORLD_SAVE.get() || minecraft.level == null || minecraft.player == null
                || minecraft.getSingleplayerServer() == null) { reset(); return; }
        long now = System.nanoTime();
        if (retryAt != 0 && now - retryAt < 0) return;
        retryAt = now + COOLDOWN;
        var session = WorldMapSession.getCurrentSession();
        var processor = session == null ? null : session.getMapProcessor();
        if (processor == null) { reset(); return; }
        synchronized (processor.renderThreadPauseSync) {
            if (!processor.isMapWorldUsable() || processor.isRenderingPaused() || processor.isWaitingForWorldUpdate()
                    || processor.isCurrentMapLocked() || processor.getWorld() != minecraft.level
                    || processor.isConsideringNetherFairPlay() || !processor.isCurrentMultiworldWritable()
                    || processor.getCurrentCaveLayer() != Integer.MAX_VALUE) { reset(); return; }
            var mapWorld = processor.getMapWorld();
            if (mapWorld == null || !minecraft.level.dimension().equals(mapWorld.getCurrentDimensionId())) { reset(); return; }
            var dimension = mapWorld.getCurrentDimension();
            if (dimension == null || !dimension.isUsingWorldSave()) { reset(); return; }
            var queue = processor.getMapSaveLoad();
            if (!queue.isRegionDetectionComplete() || queue.loadingFiles || queue.getSizeOfToLoad() != 0
                    || queue.getSizeOfToLoadBranchCache() != 0) return;
            var feet = minecraft.player.blockPosition();
            int originX = Math.floorDiv(feet.getX(), 512), originZ = Math.floorDiv(feet.getZ(), 512);
            var wanted = new Scope(processor, processor.getCurrentWorldId(), processor.getCurrentDimId(),
                    processor.getCurrentMWId());
            if (!wanted.equals(scope)) {
                scope = wanted; remaining = null; visited.clear(); rescanAt = 0; next = 0;
            }
            if (nearX != originX || nearZ != originZ) { nearX = originX; nearZ = originZ; next = 0; }
            while (next < NEAR_FIRST.size()) {
                var offset = NEAR_FIRST.get(next++);
                int x = originX + offset.x(), z = originZ + offset.z();
                if (dimension.getWorldSaveRegionDetection(x, z) != null && request(processor, queue, x, z)) return;
            }
            // Do not enumerate or sort a large world on the client thread at once. Xaero's
            // chain iterator tolerates removed nodes; keep the iteration within its map lock.
            if (remaining == null) {
                if (rescanAt != 0 && now - rescanAt < 0) return;
                remaining = dimension.getLinkedWorldSaveDetectedRegions().iterator();
            }
            for (int checked = 0; checked < 24; checked++) {
                if (!remaining.hasNext()) { remaining = null; rescanAt = now + RESCAN; return; }
                var entry = remaining.next();
                if (entry != null && request(processor, queue, entry.getRegionX(), entry.getRegionZ())) return;
            }
        }
    }

    private boolean request(MapProcessor processor, MapSaveLoad queue, int x, int z) {
        long key = (long) x << 32 | (z & 0xffffffffL);
        if (visited.contains(key)) return false;
        var region = processor.getLeafMapRegion(Integer.MAX_VALUE, x, z, true);
        if (region == null) return false;
        visited.add(key);
        if (region.isLoaded()) return false;
        queue.requestLoad(region, "Earth surface prefetch", false);
        return true;
    }

    public void reset() { scope = null; remaining = null; visited.clear(); next = 0; retryAt = rescanAt = 0; }

    private static List<Offset> offsets() {
        var result = new ArrayList<Offset>();
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) result.add(new Offset(x, z));
        result.sort(Comparator.comparingInt(offset -> offset.x * offset.x + offset.z * offset.z));
        return List.copyOf(result);
    }
}
