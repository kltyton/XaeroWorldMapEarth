package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.sighs.apricityui.chunkmap.AuiNativeMesh;
import com.sighs.apricityui.chunkmap.AuiNativeTerrainRenderer;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** Native map scene: owns geometry residency, coverage and surface passes; GPU mesh rendering is delegated to the AUI primitive. */
public final class NativeTerrainScene implements AutoCloseable {
    public record Surface(long x, long z, int width, int depth, int step, byte[] rgba, int[] heights, long revision) { }
    public record Statistics(int visible, int resident, int loading, long residentBytes, int drawCalls,
                             int minecraftFps, double minecraftFrameMillis, double cpuMillis) { }
    private static final long OFFSCREEN_LIMIT = 768L << 20, UPLOAD_BUDGET = 8L << 20;
    private static final int COVERAGE_SIZE = 200;
    private final AuiNativeTerrainRenderer delegate = new AuiNativeTerrainRenderer();
    private final ExecutorService decoding = Executors.newFixedThreadPool(
            Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 8), task -> {
                Thread thread = new Thread(task, "Earth native map geometry"); thread.setDaemon(true); return thread;
            });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);
    private final SurfaceRenderer surfaceRenderer = new SurfaceRenderer();
    private DynamicTexture coverage;
    private long residentBytes, coverageRevision, installedCoverageRevision = -1;
    private int coverageX = Integer.MIN_VALUE, coverageZ = Integer.MIN_VALUE;
    private RenderTarget frameTarget;
    private MapCamera frameCamera;
    private List<AuiNativeTerrainRenderer.Draw> frameDraws = List.of();
    private volatile Statistics statistics = new Statistics(0,0,0,0,0,0,0,0);

    public NativeTerrainScene() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Create map scenes on the client thread");
    }

    public boolean drawOpaque(MapCamera camera, Surface surface, ChunkTileSource source,
                              Map<ChunkTiles.Tile,Integer> masks, RenderTarget target, float brightness, boolean underground) {
        return drawOpaque(camera, surface, source, masks, target, brightness, underground, false);
    }

    public boolean drawOpaque(MapCamera camera, Surface surface, ChunkTileSource source,
                              Map<ChunkTiles.Tile,Integer> masks, RenderTarget target, float brightness,
                              boolean underground, boolean requirePrecise) {
        if (closed.get()) return false;
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Draw map scenes on the client thread");
        long started = System.nanoTime();
        suspend();
        var frustum = new FrustumIntersection(new Matrix4f(camera.projectionMatrix()).mul(camera.viewMatrix()));
        var wanted = new ArrayList<ChunkTiles.Tile>();
        var requestedKeys = new HashSet<String>();
        if (source != null) for (var tile : masks.keySet()) {
            String key = source.contentKey(tile);
            if (key == null) continue;
            Entry cached = entries.get(key);
            float minY = cached != null && cached.gpu != null ? cached.gpu.minY() : source.minY();
            float maxY = cached != null && cached.gpu != null ? cached.gpu.maxY() : source.maxY();
            if (frustum.testAab((float)(tile.x()*32.0-camera.x()), minY-(float)camera.y(),
                    (float)(tile.z()*32.0-camera.z()), (float)(tile.x()*32.0+32-camera.x()),
                    maxY-(float)camera.y(), (float)(tile.z()*32.0+32-camera.z()))) {
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
                scheduled.work = CompletableFuture.supplyAsync(() -> {
                    if (scheduled.abandoned || closed.get()) return null;
                    var data = source.tile(tile);
                    if (data == null || !key.equals(source.contentKey(tile))) return null;
                    try {
                        var mesh = AuiNativeMesh.decode(data.model());
                        if (closed.get() || scheduled.abandoned) { mesh.close(); return null; }
                        return mesh;
                    } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }, decoding);
                entries.put(key, scheduled); entry = scheduled; pending++;
            }
            if (entry == null || entry.failed) continue;
            if (entry.gpu == null && entry.work.isDone() && uploaded < UPLOAD_BUDGET) {
                try (var mesh = entry.work.join()) {
                    if (mesh == null) { entries.remove(key); continue; }
                    ByteBuffer vertices = mesh.vertices();
                    long bytes = vertices.remaining();
                    entry.gpu = delegate.upload(mesh);
                    uploaded += bytes; residentBytes += bytes; coverageRevision++;
                } catch (RuntimeException failure) {
                    entry.failed = true;
                    LogUtils.getLogger().error("Cannot upload Earth map tile {},{}", tile.x(), tile.z(), failure);
                } finally { entry.work = null; }
            }
            if (entry.gpu != null) geometries.add(new Placed(tile, masks.get(tile), entry.gpu));
        }
        trim(requestedKeys, source != null && source.persistentCache());
        if (requirePrecise && geometries.isEmpty() || surface == null && geometries.isEmpty()) return false;
        var draws = new ArrayList<AuiNativeTerrainRenderer.Draw>();
        for (Placed placed : geometries) {
            Matrix4f transform = new Matrix4f(camera.viewMatrix()).translate(
                    (float) (placed.tile.x() * 32.0 - camera.x()), (float) -camera.y(),
                    (float) (placed.tile.z() * 32.0 - camera.z()));
            draws.add(new AuiNativeTerrainRenderer.Draw(placed.mesh, transform, placed.mask, brightness));
        }
        frameCamera = camera; frameTarget = target; frameDraws = List.copyOf(draws);
        surfaceRenderer.update(surface);
        boolean surfaceDrawn = surface != null && surfaceRenderer.draw(camera, target, coverage.getTextureView(),
                coverageX, coverageZ, brightness, true, underground);
        if (!surfaceDrawn) clear(target, underground);
        delegate.drawOpaque(target, camera.nativeProjectionMatrix(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()),
                camera.mode().equals("street"), frameDraws);
        int calls = (surfaceDrawn ? 1 : 0) + (int)draws.stream().filter(draw -> draw.mesh().opaqueCount()>0).count()
                + (int)draws.stream().filter(draw -> draw.mesh().translucentCount()>0).count();
        statistics = new Statistics(geometries.size(),(int)entries.values().stream().filter(e -> e.gpu!=null).count(),
                (int)entries.values().stream().filter(e -> e.work!=null).count(),residentBytes,calls,
                Minecraft.getInstance().getFps(),Minecraft.getInstance().getFrameTimeNs()/1_000_000.0,(System.nanoTime()-started)/1_000_000.0);
        return true;
    }

    public void drawTransparent() {
        if (frameTarget == null || closed.get()) return;
        try {
            delegate.drawTransparent(frameTarget, frameCamera.nativeProjectionMatrix(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()),
                    frameCamera.mode().equals("street"), frameDraws);
        } finally { suspend(); }
    }

    private static void clear(RenderTarget target, boolean underground) {
        Optional<Vector4fc> color = Optional.of(new Vector4f(underground ? 0 : 0.46F, underground ? 0 : 0.67F,
                underground ? 0 : 0.82F, 1));
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Earth native terrain clear", target.getColorTextureView(), color,
                target.getDepthTextureView(), OptionalDouble.of(0))) { }
    }

    private void updateCoverage(MapCamera camera, ChunkTileSource source, Map<ChunkTiles.Tile,Integer> masks) {
        if (coverage == null) coverage = new DynamicTexture(() -> "AUI map coverage", COVERAGE_SIZE, COVERAGE_SIZE, true);
        int x=Math.floorDiv((int)Math.floor(camera.x()),16)-COVERAGE_SIZE/2;
        int z=Math.floorDiv((int)Math.floor(camera.z()),16)-COVERAGE_SIZE/2;
        var loaded = new ArrayList<Placed>();
        long key=coverageRevision;
        if (source!=null) for (var item:masks.entrySet()) {
            Entry entry=entries.get(source.contentKey(item.getKey()));
            if (entry==null || entry.gpu==null) continue;
            loaded.add(new Placed(item.getKey(),item.getValue(),entry.gpu));
            key+=31L*item.getKey().hashCode()+item.getValue();
        }
        if (coverageX==x && coverageZ==z && installedCoverageRevision==key) return;
        coverageX=x; coverageZ=z; installedCoverageRevision=key;
        var pixels=coverage.getPixels();
        for(int cz=0;cz<COVERAGE_SIZE;cz++) for(int cx=0;cx<COVERAGE_SIZE;cx++) pixels.setPixel(cx,cz,0);
        for(Placed placed:loaded) for(int q=0;q<4;q++) if((placed.mask&(1<<q))!=0) {
            int cx=placed.tile.x()*2+(q&1)-x,cz=placed.tile.z()*2+(q>>1)-z;
            if(cx>=0&&cx<COVERAGE_SIZE&&cz>=0&&cz<COVERAGE_SIZE) pixels.setPixel(cx,cz,0xFFFFFFFF);
        }
        coverage.upload();
    }

    private void trim(Set<String> active, boolean retain) {
        long bytes=entries.entrySet().stream().filter(e -> !active.contains(e.getKey())&&e.getValue().gpu!=null)
                .mapToLong(e -> e.getValue().gpu.bytes()).sum();
        var iterator=entries.entrySet().iterator();
        while(bytes>(retain?OFFSCREEN_LIMIT:0)&&iterator.hasNext()) {
            var item=iterator.next(); Entry entry=item.getValue();
            if(active.contains(item.getKey())||entry.gpu==null) continue;
            long removed=entry.gpu.bytes(); entry.gpu.close(); iterator.remove(); residentBytes-=removed; bytes-=removed;
        }
    }

    public void suspend() { frameTarget=null; frameDraws=List.of(); }
    public void resetSurface() { suspend(); surfaceRenderer.reset(); }
    public Statistics statistics() { return statistics; }

    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        suspend(); decoding.shutdownNow();
        for(Entry entry:entries.values()) {
            entry.abandoned=true;
            if(entry.gpu!=null) entry.gpu.close();
            if(entry.work!=null) entry.work.whenComplete((mesh,failure) -> {if(mesh!=null) mesh.close();});
        }
        entries.clear(); surfaceRenderer.close();
        if(coverage!=null) coverage.close();
        delegate.close();
    }

    private static final class Entry {
        CompletableFuture<AuiNativeMesh> work;
        AuiNativeTerrainRenderer.GpuMesh gpu;
        boolean failed;
        volatile boolean abandoned;
    }
    private record Placed(ChunkTiles.Tile tile, int mask, AuiNativeTerrainRenderer.GpuMesh mesh) { }
}
