package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeMesh;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/** Native map scene: owns geometry residency, coverage and surface passes; GPU mesh rendering is delegated to the KUI primitive. */
public final class NativeTerrainScene extends TerrainPlatform implements AutoCloseable {
    public record Surface(long x, long z, int width, int depth, int step, byte[] rgba, int[] heights,
                          int[] patches, int[] outline, int[] joins, int floor, long revision) {
        int heightTextureRows() { return depth + (patches.length + outline.length + joins.length + width - 1) / width; }
        int vertexCount() { return Math.multiplyExact(patches.length + outline.length + joins.length, 6); }

        // Extra rows index coarse top patches, unexplored edges and shared near/far edges.
        void writeHeights(ByteBuffer buffer) {
            for (int y : heights) buffer.putFloat(y + 1.0F);
            for (int patch : patches) buffer.putFloat(patch);
            for (int edge : outline) buffer.putFloat(edge);
            for (int edge : joins) buffer.putFloat(edge);
            while (buffer.position() < width * heightTextureRows() * Float.BYTES) buffer.putFloat(0);
        }
    }
    public record Statistics(int visible, int resident, int loading, long residentBytes, int drawCalls,
                             int minecraftFps, double minecraftFrameMillis, double cpuMillis) { }
    private static final long OFFSCREEN_LIMIT = 768L << 20, UPLOAD_BUDGET = 8L << 20;
    private static final int COVERAGE_SIZE = 200;
    private final ExecutorService decoding = Executors.newFixedThreadPool(
            net.minecraft.util.Mth.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 8), task -> {
                Thread thread = new Thread(task, "Earth native map geometry"); thread.setDaemon(true); return thread;
            });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long offscreenLimit;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);
    private long residentBytes;
    private final Map<ChunkTiles.Tile, Integer> coverageMasks = new HashMap<>();
    private final Map<ChunkTiles.Tile, String> displayedKeys = new HashMap<>();
    private int coverageX = Integer.MIN_VALUE, coverageZ = Integer.MIN_VALUE;
    private RenderTarget frameTarget;
    private MapCamera frameCamera;
    private List<KuiNativeTerrainRenderer.Draw> frameDraws = List.of();
    private volatile Statistics statistics = new Statistics(0,0,0,0,0,0,0,0);

    public NativeTerrainScene() {
        this(OFFSCREEN_LIMIT);
    }

    public NativeTerrainScene(long offscreenLimit) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Create map scenes on the client thread");
        this.offscreenLimit = offscreenLimit;
    }


    public boolean drawOpaque(MapCamera camera, Surface surface, ChunkTileSource source,
                              Map<ChunkTiles.Tile,Integer> masks, RenderTarget target, float brightness, boolean underground) {
        if (closed.get()) return false;
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Draw map scenes on the client thread");
        long started = System.nanoTime();
        suspend();
        var frustum = new FrustumIntersection(camera.viewportProjectionMatrix().mul(camera.viewMatrix()));
        var wanted = new ArrayList<ChunkTiles.Tile>();
        var requestedKeys = new HashSet<String>();
        double detailRadius = TerrainDetail.radius(camera.pixelsPerBlock(), Double.POSITIVE_INFINITY);
        if (source != null) for (var tile : masks.keySet()) {
            double radius = displayedKeys.containsKey(tile) ? detailRadius + 12 : detailRadius;
            if (detailRadius <= 0 || !TerrainDetail.intersects(camera.x(), camera.z(), radius, tile.x() * 32.0, tile.z() * 32.0, 32)) continue;
            String key = source.contentKey(tile);
            if (key == null) continue;
            Entry cached = entries.get(key);
            float minY = cached != null && cached.gpu != null ? cached.gpu.minY() : source.minY();
            float maxY = cached != null && cached.gpu != null ? cached.gpu.maxY() : source.maxY();
            if (frustum.testAab((float)(tile.x()*32.0-camera.x()-8), minY-(float)camera.y(),
                    (float)(tile.z()*32.0-camera.z()-8), (float)(tile.x()*32.0+40-camera.x()),
                    maxY-(float)camera.y(), (float)(tile.z()*32.0+40-camera.z()))) {
                wanted.add(tile); requestedKeys.add(key);
            }
        }
        wanted.sort(Comparator.comparingDouble(tile -> Math.hypot(tile.x()*32+16-camera.x(),tile.z()*32+16-camera.z())));
        var obsolete = entries.entrySet().iterator();
        while (obsolete.hasNext()) {
            var value = obsolete.next(); Entry entry = value.getValue();
            if (entry.work == null || requestedKeys.contains(value.getKey())) continue;
            entry.abandoned = true;
            entry.work.whenComplete((data,failure) -> { if (data != null) data.close(); });
            obsolete.remove();
        }
        int pending = (int)entries.values().stream().filter(entry -> entry.work != null).count();
        long uploaded = 0;
        var geometries = new ArrayList<Placed>();
        for (var tile : wanted) {
            String key = source.contentKey(tile);
            if (key == null) continue;
            Entry entry = entries.get(key);
            if (entry == null && pending < 8) {
                Entry scheduled = new Entry();
                scheduled.mask = masks.get(tile);
                scheduled.work = CompletableFuture.supplyAsync(() -> {
                    if (scheduled.abandoned || closed.get()) return null;
                    var data = source.tile(tile);
                    if (data == null || !key.equals(source.contentKey(tile))) return null;
                    try {
                        var mesh = KuiNativeMesh.decode(data.model());
                        if (closed.get() || scheduled.abandoned) { mesh.close(); return null; }
                        return mesh;
                    } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }, decoding);
                entries.put(key, scheduled); entry = scheduled; pending++;
            }
            if (entry != null && !entry.failed && entry.gpu == null && entry.work.isDone() && uploaded < UPLOAD_BUDGET) {
                try (var mesh = entry.work.join()) {
                    if (mesh == null) { entries.remove(key); }
                    else {
                        ByteBuffer vertices = mesh.vertices();
                        long bytes = vertices.remaining();
                        entry.gpu = delegate.upload(mesh);
                        uploaded += bytes; residentBytes += bytes;
                    }
                } catch (RuntimeException failure) {
                    entry.failed = true;
                    LogUtils.getLogger().error("Cannot upload Earth map tile {},{}", tile.x(), tile.z(), failure);
                } finally { entry.work = null; }
            }
            if (entry != null && entry.gpu != null) displayedKeys.put(tile, key);
            String displayedKey = displayedKeys.get(tile);
            if (displayedKey == null) continue;
            Entry displayed = entries.get(displayedKey);
            if (displayed == null || displayed.gpu == null) {
                displayedKeys.remove(tile);
                continue;
            }
            requestedKeys.add(displayedKey);
            int mask = masks.get(tile) & displayed.mask;
            if (mask != 0) geometries.add(new Placed(tile, mask, displayed.gpu));
        }
        trim(requestedKeys, source != null && source.persistentCache());
        displayedKeys.values().removeIf(key -> !entries.containsKey(key));
        if (surface == null && geometries.isEmpty()) return false;
        var draws = new ArrayList<KuiNativeTerrainRenderer.Draw>();
        // Translucent faces are submitted from far to near along the same view direction.
        var view = camera.viewMatrix();
        geometries.sort(Comparator.comparingDouble(placed ->
                view.m02() * (placed.tile.x() * 32.0 + 16 - camera.x())
                        + view.m22() * (placed.tile.z() * 32.0 + 16 - camera.z())));
        for (Placed placed : geometries) {
            Matrix4f transform = new Matrix4f(view).translate(
                    (float) (placed.tile.x() * 32.0 - camera.x()), (float) -camera.y(),
                    (float) (placed.tile.z() * 32.0 - camera.z()));
            draws.add(new KuiNativeTerrainRenderer.Draw(placed.mesh, transform, placed.mask, brightness));
        }
        frameCamera = camera; frameTarget = target; frameDraws = List.copyOf(draws);
        boolean surfaceDrawn = drawSurface(camera, surface, target, brightness, underground,
                () -> updateCoverage(camera, geometries), () -> coverageX, () -> coverageZ);
        delegate.drawOpaque(target, projection(camera), camera.mode().equals("street"), frameDraws);
        int calls = (surfaceDrawn ? 1 : 0) + (int)draws.stream().filter(draw -> draw.mesh().opaqueCount()>0).count()
                + (int)draws.stream().filter(draw -> draw.mesh().translucentCount()>0).count();
        statistics = new Statistics(geometries.size(),(int)entries.values().stream().filter(e -> e.gpu!=null).count(),
                (int)entries.values().stream().filter(e -> e.work!=null).count(),residentBytes,calls,
                FrameMetrics.fps(),FrameMetrics.frameMillis(),(System.nanoTime()-started)/1_000_000.0);
        return true;
    }

    public void drawTransparent(boolean showNavigation) {
        if (frameTarget == null || closed.get()) return;
        try {
            delegate.drawTransparent(frameTarget, projection(frameCamera), frameCamera.mode().equals("street"), frameDraws);
            if (showNavigation) drawNavigation(frameTarget, frameCamera);
        } finally { suspend(); }
    }

    private void updateCoverage(MapCamera camera, List<Placed> drawn) {
        if (coverage == null) coverage = createCoverage(COVERAGE_SIZE);
        int x=Math.floorDiv((int)Math.floor(camera.x()),16)-COVERAGE_SIZE/2;
        int z=Math.floorDiv((int)Math.floor(camera.z()),16)-COVERAGE_SIZE/2;
        if (coverageX == x && coverageZ == z && coverageMasks.size() == drawn.size()) {
            boolean unchanged = true;
            for (Placed placed : drawn) {
                if (!Objects.equals(coverageMasks.get(placed.tile), placed.mask)) { unchanged = false; break; }
            }
            if (unchanged) return;
        }
        coverageX=x; coverageZ=z;
        coverageMasks.clear();
        for (Placed placed : drawn) coverageMasks.put(placed.tile, placed.mask);
        var pixels=coverage.getPixels();
        for(int cz=0;cz<COVERAGE_SIZE;cz++) for(int cx=0;cx<COVERAGE_SIZE;cx++) setPixel(pixels,cx,cz,0);
        for(Placed placed:drawn) for(int q=0;q<4;q++) if((placed.mask&(1<<q))!=0) {
            int cx=placed.tile.x()*2+(q&1)-x,cz=placed.tile.z()*2+(q>>1)-z;
            if(cx>=0&&cx<COVERAGE_SIZE&&cz>=0&&cz<COVERAGE_SIZE) setPixel(pixels,cx,cz,0xFFFFFFFF);
        }
        coverage.upload();
    }

    private void trim(Set<String> active, boolean retain) {
        long bytes=entries.entrySet().stream().filter(e -> !active.contains(e.getKey())&&e.getValue().gpu!=null)
                .mapToLong(e -> e.getValue().gpu.bytes()).sum();
        var iterator=entries.entrySet().iterator();
        while(bytes>(retain?offscreenLimit:0)&&iterator.hasNext()) {
            var item=iterator.next(); Entry entry=item.getValue();
            if(active.contains(item.getKey())||entry.gpu==null) continue;
            long removed=entry.gpu.bytes(); entry.gpu.close(); iterator.remove(); residentBytes-=removed; bytes-=removed;
        }
    }

    public void suspend() { frameTarget=null; frameDraws=List.of(); }
    public void resetSurface() {
        suspend();
        surfaceRenderer.reset();
        for (Entry entry : entries.values()) {
            entry.abandoned = true;
            if (entry.gpu != null) entry.gpu.close();
            if (entry.work != null) entry.work.whenComplete((mesh, failure) -> { if (mesh != null) mesh.close(); });
        }
        entries.clear();
        displayedKeys.clear();
        coverageMasks.clear();
        residentBytes = 0;
    }
    public Statistics statistics() { return statistics; }

    public boolean hasDetailAt(int x, int z) {
        var tile = new ChunkTiles.Tile(Math.floorDiv(x, 32), Math.floorDiv(z, 32));
        int quarter = Math.floorMod(x, 32) / 16 + 2 * (Math.floorMod(z, 32) / 16);
        return (coverageMasks.getOrDefault(tile, 0) & (1 << quarter)) != 0;
    }

    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        resetSurface();
        decoding.shutdownNow();
        surfaceRenderer.close();
        if(coverage!=null) coverage.close();
        delegate.close();
    }

    private static final class Entry {
        int mask;
        CompletableFuture<KuiNativeMesh> work;
        KuiNativeTerrainRenderer.GpuMesh gpu;
        boolean failed;
        volatile boolean abandoned;
    }
    private record Placed(ChunkTiles.Tile tile, int mask, KuiNativeTerrainRenderer.GpuMesh mesh) { }
}
