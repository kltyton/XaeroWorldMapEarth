package io.github.kltyton.xaeroearth.client.bridge;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import com.mojang.logging.LogUtils;
import com.sighs.apricityui.chunkmap.AuiNativeTerrainRenderer.Surface;
import com.sighs.apricityui.chunkmap.AuiChunkTiles;
import com.sighs.apricityui.chunkmap.AuiNativeTileSession;
import com.sighs.apricityui.chunkmap.AuiMapCamera;
import io.github.kltyton.xaeroearth.client.mixin.GuiMapAccess;
import java.nio.ByteBuffer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.joml.Vector4f;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.dimension.DimensionType;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;
import xaero.map.region.texture.RegionTexture;

/** Copies the native visible map caches. Xaero retains loading, permissions and cave selection. */
public final class XaeroMapBridge implements AutoCloseable {
    private static final int TEXTURE_COPY_BATCH = 8;
    private static final int SURFACE_TEXELS = 1024 * 1024;
    private record Bounds(long minX, long minZ, long maxX, long maxZ) {
        boolean contains(Bounds other) {
            return minX <= other.minX && minZ <= other.minZ && maxX >= other.maxX && maxZ >= other.maxZ;
        }
        boolean intersects(Key key) {
            return key.x() < maxX && key.z() < maxZ
                    && key.x() + 64L * key.step() > minX && key.z() + 64L * key.step() > minZ;
        }
        Bounds padded() {
            return new Bounds(Math.floorDiv(minX, 16) * 16 - 16, Math.floorDiv(minZ, 16) * 16 - 16,
                    Math.floorDiv(maxX + 15, 16) * 16 + 16, Math.floorDiv(maxZ + 15, 16) * 16 + 16);
        }
    }
    private record Scene(MapProcessor processor, String world, String dimension, String multiworld, int cave) { }
    private record Key(int level, int regionX, int regionZ, int textureX, int textureZ) {
        int step() { return 1 << level; }
        long x() { return (regionX * 512L + textureX * 64L) * step(); }
        long z() { return (regionZ * 512L + textureZ * 64L) * step(); }
        Key parent(int parentLevel) {
            long px = Math.floorDiv(x(), 64L << parentLevel), pz = Math.floorDiv(z(), 64L << parentLevel);
            return new Key(parentLevel, (int) Math.floorDiv(px, 8), (int) Math.floorDiv(pz, 8),
                    (int) Math.floorMod(px, 8), (int) Math.floorMod(pz, 8));
        }
    }
    private record Tile(Key key, int version, byte[] rgba, byte[] light, int[] heights, int[] unmapped, WeakReference<RegionTexture<?>> identity) { }
    private record Stamp(int version, WeakReference<RegionTexture<?>> identity) { }
    private record Request(Key key, int version, int[] heights, byte[] rgba, WeakReference<RegionTexture<?>> identity) { }
    private final ExecutorService conversion = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Earth map cache conversion"); thread.setDaemon(true); return thread;
    });
    private final LinkedHashMap<Key, Tile> tiles = new LinkedHashMap<>(128, 0.75f, true);
    private final Set<Key> reading = new HashSet<>();
    private final ByteBuffer readback = MemoryUtil.memAlloc(16384);
    private Set<Key> required = Set.of();
    private Scene scene;
    private long generation, lastCopy, lastPublish;
    private int scanCursor;
    private boolean closed, dirty;
    private volatile Surface surface;
    private CompletableFuture<Surface> publishing;
    private long publishGeneration, surfaceRevision;
    private AuiNativeTileSession models;
    private Map<AuiChunkTiles.Tile, Integer> visibleModels = Map.of();
    private long modelRevision = -1, mappedRevision = -1, tileRevision;
    private float brightness = Float.NaN;
    private Bounds viewport, visibleViewport, publishingViewport;
    private int terrainMinY = Integer.MAX_VALUE, terrainMaxY = Integer.MIN_VALUE;

    /** Selects the scene before the caller constructs its current camera. */
    public boolean prepareFrame(GuiMap map) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Read mapped terrain on the client thread");
        if (closed) return false;
        MapProcessor processor = map.getMapProcessor();
        synchronized (processor.renderThreadPauseSync) {
            if (processor.isRenderingPaused() || processor.isWaitingForWorldUpdate()
                    || !processor.isMapWorldUsable() || processor.isCurrentMapLocked()) return false;
            float currentBrightness = processor.getBrightness();
            if (currentBrightness != brightness) { brightness = currentBrightness; dirty = true; }
            Scene selected = new Scene(processor, processor.getCurrentWorldId(), processor.getCurrentDimId(),
                    processor.getCurrentMWId(), processor.getCurrentCaveLayer());
            if (!selected.equals(scene)) {
                scene = selected; generation++; tiles.clear(); reading.clear(); surface = null; dirty = true; scanCursor = 0;
                modelRevision = mappedRevision = -1;
                models = null; visibleModels = Map.of();
                viewport = visibleViewport = null;
                terrainMinY = Integer.MAX_VALUE; terrainMaxY = Integer.MIN_VALUE;
            }
            updateModels(map);
        }
        return true;
    }

    /** Reads the native caches within the same camera frustum used by the GPU passes. */
    public boolean capture(GuiMap map, AuiMapCamera camera) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Read mapped terrain on the client thread");
        if (closed || scene == null || scene.processor != map.getMapProcessor()) return false;
        MapProcessor processor = scene.processor;
        synchronized (processor.renderThreadPauseSync) {
            if (processor.isRenderingPaused() || !matches(scene, processor)) return false;
            Scene selected = scene;
            visibleViewport = bounds(camera);
            Bounds nextViewport = visibleViewport == null ? null : visibleViewport.padded();
            if (!Objects.equals(viewport, nextViewport)) { viewport = nextViewport; dirty = true; }
            long now = System.nanoTime();
            if (viewport != null && now - lastCopy >= 100_000_000L) {
                List<Key> visible = new ArrayList<>();
                var access = (GuiMapAccess) map;
                // GuiMap's draw buffers are transient. Query the existing cache hierarchy for the current viewport.
                for (int level = 3; level >= 0; level--) {
                    int span = 512 << level;
                    int minX = (int) Math.floorDiv(viewport.minX, span);
                    int maxX = (int) Math.floorDiv(viewport.maxX - 1, span);
                    int minZ = (int) Math.floorDiv(viewport.minZ, span);
                    int maxZ = (int) Math.floorDiv(viewport.maxZ - 1, span);
                    for (int rz = minZ; rz <= maxZ; rz++) for (int rx = minX; rx <= maxX; rx++) {
                        var region = processor.getLeveledRegion(selected.cave, rx, rz, level);
                        if (region != null) addKeys(region, visible, viewport);
                    }
                }
                required = Set.copyOf(visible);
                // Coarse coverage fills first, followed by its finer cached textures.
                visible.sort(Comparator.comparingInt(Key::level).reversed().thenComparingDouble(key ->
                        Math.hypot(key.x() - access.earth$cameraX(), key.z() - access.earth$cameraZ())));
                if (!visible.isEmpty()) {
                    java.util.Collections.rotate(visible, -(scanCursor % visible.size()));
                    scanCursor = (scanCursor + TEXTURE_COPY_BATCH) % visible.size();
                }
                lastCopy = now;
                long ticket = generation;
                Map<Key, Stamp> versions = new java.util.HashMap<>();
                tiles.forEach((key, tile) -> versions.put(key, new Stamp(tile.version, tile.identity)));
                Set<Key> inFlight = Set.copyOf(reading);
                copyVisible(selected, ticket, visible, versions, inFlight, processor);
            }
            finishPublish();
            updateModels(map);
            if (publishing == null && dirty && viewport != null && !tiles.isEmpty() && now - lastPublish >= 100_000_000L) {
                dirty = false; lastPublish = now; publishGeneration = generation;
                Bounds frameViewport = viewport;
                publishingViewport = frameViewport;
                List<Tile> snapshot = new ArrayList<>();
                for (Tile tile : tiles.values()) if (frameViewport.intersects(tile.key)) snapshot.add(tile);
                // This immutable copy is the only map data consumed by the background compositor.
                long revision = ++surfaceRevision;
                float frameBrightness = brightness;
                publishing = CompletableFuture.supplyAsync(() -> composite(snapshot, frameViewport, frameBrightness, revision), conversion);
            }
        }
        return true;
    }

    private Bounds bounds(AuiMapCamera camera) {
        var dimension = scene.processor.getMapWorld().getCurrentDimension();
        var world = scene.processor.getWorld();
        var type = world != null && world.dimension().equals(dimension.getDimId()) ? world.dimensionType() : null;
        double minY = models != null ? models.minY() : type != null ? type.minY()
                : terrainMinY != Integer.MAX_VALUE ? terrainMinY : DimensionType.MIN_Y;
        double maxY = models != null ? models.maxY() : type != null ? type.minY() + type.height()
                : terrainMaxY != Integer.MIN_VALUE ? terrainMaxY + 1 : DimensionType.MAX_Y + 1;
        if (terrainMinY != Integer.MAX_VALUE) minY = Math.min(minY, terrainMinY);
        if (terrainMaxY != Integer.MIN_VALUE) maxY = Math.max(maxY, terrainMaxY);
        minY -= camera.y(); maxY -= camera.y();
        var inverse = camera.projectionMatrix().mul(camera.viewMatrix()).invert();
        Vector4f[] corners = new Vector4f[8];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = inverse.transform(new Vector4f((i & 1) == 0 ? -1 : 1,
                    (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1, 1));
            corners[i].div(corners[i].w);
        }
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        // The clipped frustum's X/Z extrema lie on its twelve edges or their height-plane intersections.
        for (int i = 0; i < corners.length; i++) for (int axis = 1; axis <= 4; axis <<= 1) {
            if ((i & axis) != 0) continue;
            Vector4f a = corners[i], b = corners[i | axis];
            double start = 0, end = 1, dy = b.y - a.y;
            if (dy == 0) {
                if (a.y < minY || a.y > maxY) continue;
            } else {
                double first = (minY - a.y) / dy, last = (maxY - a.y) / dy;
                start = Math.max(0, Math.min(first, last));
                end = Math.min(1, Math.max(first, last));
                if (start > end) continue;
            }
            double x0 = a.x + (b.x - a.x) * start, x1 = a.x + (b.x - a.x) * end;
            double z0 = a.z + (b.z - a.z) * start, z1 = a.z + (b.z - a.z) * end;
            minX = Math.min(minX, Math.min(x0, x1)); maxX = Math.max(maxX, Math.max(x0, x1));
            minZ = Math.min(minZ, Math.min(z0, z1)); maxZ = Math.max(maxZ, Math.max(z0, z1));
        }
        if (!Double.isFinite(minX)) return null;
        return new Bounds((long) Math.floor(camera.x() + minX), (long) Math.floor(camera.z() + minZ),
                (long) Math.ceil(camera.x() + maxX) + 1, (long) Math.ceil(camera.z() + maxZ) + 1);
    }

    private void updateModels(GuiMap map) {
        var current = io.github.kltyton.xaeroearth.client.EarthClient.modelsFor(map);
        if (models != current) {
            models = current; visibleModels = Map.of(); modelRevision = -1; mappedRevision = -1;
        }
        if (models == null) {
            return;
        }
        if (modelRevision == models.revision() && mappedRevision == tileRevision) return;
        var visible = new java.util.HashMap<AuiChunkTiles.Tile, Integer>();
        for (var tile : models.versions().keySet()) {
            int minX = tile.x() * 32, minZ = tile.z() * 32;
            int mask = 0, available = models.availableMask(tile);
            for (int q = 0; q < 4; q++) {
                if ((available & (1 << q)) == 0) continue;
                int x0 = (q & 1) * 16, z0 = (q >> 1) * 16;
                if (mappedQuarter(minX + x0, minZ + z0)) mask |= 1 << q;
            }
            if (mask != 0) visible.put(tile, mask);
        }
        visibleModels = Map.copyOf(visible); modelRevision = models.revision(); mappedRevision = tileRevision;
    }

    private static void addKeys(LeveledRegion<?> region, List<Key> keys, Bounds viewport) {
        if (!region.hasTextures()) return;
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
            var texture = region.getTexture(x, z);
            Key key = new Key(region.getLevel(), region.getRegionX(), region.getRegionZ(), x, z);
            if (viewport.intersects(key) && texture != null && texture.getGlColorTexture() > 0) keys.add(key);
        }
    }

    private void copyVisible(Scene selected, long ticket, List<Key> visible, Map<Key, Stamp> versions,
                             Set<Key> inFlight, MapProcessor current) {
        List<Request> result = new ArrayList<>();
        try {
            if (current != selected.processor || !matches(selected, current)) return;
            for (Key key : visible) {
                if (result.size() >= TEXTURE_COPY_BATCH) break;
                if (inFlight.contains(key)) continue;
                LeveledRegion<?> region = current.getLeveledRegion(selected.cave, key.regionX, key.regionZ, key.level);
                if (region == null || !region.hasTextures()) continue;
                if (region instanceof MapRegion leaf) {
                    // Texture uploads hold the region before its writer pause lock.
                    synchronized (leaf) {
                        synchronized (leaf.writerThreadPauseSync) { copyTexture(key, region.getTexture(key.textureX, key.textureZ), versions, result); }
                    }
                } else synchronized (region) { copyTexture(key, region.getTexture(key.textureX, key.textureZ), versions, result); }
            }
        } finally {
            if (!closed && ticket == generation && selected.equals(scene) && matches(selected, selected.processor))
                for (Request request : result) installOrRead(selected, ticket, request);
        }
    }

    private void copyTexture(Key key, RegionTexture<?> texture, Map<Key, Stamp> versions, List<Request> result) {
        if (texture == null || texture.shouldUpload() || !texture.isUploaded()) return;
        int version = texture.getTextureVersion();
        Stamp cached = versions.get(key);
        if (cached != null && cached.version == version && cached.identity.get() == texture) return;
        int[] heights = new int[4096];
        boolean any = false;
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            int height = texture.getHeight(x, z);
            int top = texture.getTopHeight(x, z);
            if (top != 32767 && (height == 32767 || top > height)) height = top;
            heights[z * 64 + x] = height;
            any |= height != 32767;
        }
        if (!any) {
            result.add(new Request(key, version, heights, new byte[16384], new WeakReference<>(texture)));
            return;
        }
        ByteBuffer colors = texture.getDirectColorBuffer();
        byte[] rgba = null;
        if (colors != null && texture.getColorBufferFormat() == GL11.GL_RGBA && colors.capacity() >= 16384) {
            rgba = new byte[16384]; colors.duplicate().clear().get(rgba);
        }
        result.add(new Request(key, version, heights, rgba, new WeakReference<>(texture)));
    }

    private void installOrRead(Scene selected, long ticket, Request request) {
        Tile cached = tiles.get(request.key);
        if (cached != null && cached.version == request.version && cached.identity.get() == request.identity.get()
                || reading.contains(request.key)) return;
        MapProcessor processor = selected.processor;
        synchronized (processor.renderThreadPauseSync) {
            if (!matches(selected, processor) || processor.isRenderingPaused()) return;
            var region = processor.getLeveledRegion(selected.cave, request.key.regionX, request.key.regionZ, request.key.level);
            if (region == null || !region.hasTextures()) return;
            var texture = region.getTexture(request.key.textureX, request.key.textureZ);
            if (texture != request.identity.get() || texture == null || texture.shouldUpload()
                    || !texture.isUploaded() || texture.getTextureVersion() != request.version) return;
            if (request.rgba != null) { install(request, request.rgba); return; }
            int gpu = texture.getGlColorTexture();
            if (gpu <= 0) return;
            int active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int row = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH), rows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
            int pixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS), alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            reading.add(request.key);
            try {
                GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
                GlStateManager._bindTexture(gpu);
                readback.clear();
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback);
                byte[] rgba = new byte[16384];
                readback.get(rgba);
                if (!closed && ticket == generation && selected.equals(scene) && matches(selected, processor)
                        && !texture.shouldUpload() && texture.isUploaded()
                        && texture.getTextureVersion() == request.version) install(request, rgba);
            } finally {
                reading.remove(request.key);
                GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
                GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, row);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, rows);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, pixels);
                GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, alignment);
                GlStateManager._bindTexture(bound);
                GlStateManager._activeTexture(active);
            }
        }
    }

    private void install(Request request, byte[] rgba) {
        byte[] light = new byte[request.heights.length];
        // Native leaf alpha stores block light; only an all-zero texel is unmapped.
        for (int i = 0; i < request.heights.length; i++) {
            int pixel = i * 4;
            light[i] = rgba[pixel + 3];
            boolean mapped = (rgba[pixel] | rgba[pixel + 1] | rgba[pixel + 2] | rgba[pixel + 3]) != 0;
            rgba[pixel + 3] = (byte) (mapped && request.heights[i] != 32767 ? 255 : 0);
            if (rgba[pixel + 3] != 0) {
                terrainMinY = Math.min(terrainMinY, request.heights[i]);
                terrainMaxY = Math.max(terrainMaxY, request.heights[i] + 1);
            }
        }
        int[] unmapped = new int[65 * 65];
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            int value = rgba[(z * 64 + x) * 4 + 3] == 0 ? 1 : 0;
            int index = (z + 1) * 65 + x + 1;
            unmapped[index] = value + unmapped[index - 1] + unmapped[index - 65] - unmapped[index - 66];
        }
        tiles.put(request.key, new Tile(request.key, request.version, rgba, light, request.heights, unmapped, request.identity));
        tileRevision++;
        while (tiles.size() > Math.max(1024, required.size() + 16)) tiles.remove(tiles.keySet().iterator().next());
        dirty = true;
    }

    private static boolean matches(Scene selected, MapProcessor processor) {
        return processor.isMapWorldUsable() && !processor.isCurrentMapLocked() && !processor.isWaitingForWorldUpdate()
                && Objects.equals(selected.world, processor.getCurrentWorldId())
                && Objects.equals(selected.dimension, processor.getCurrentDimId())
                && Objects.equals(selected.multiworld, processor.getCurrentMWId())
                && selected.cave == processor.getCurrentCaveLayer();
    }

    private static Surface composite(List<Tile> tiles, Bounds viewport, float brightness, long revision) {
        long minX = viewport.minX, minZ = viewport.minZ, maxX = viewport.maxX, maxZ = viewport.maxZ;
        int step = 1;
        while (((maxX - minX + step - 1) / step) * ((maxZ - minZ + step - 1) / step) > SURFACE_TEXELS) step <<= 1;
        minX = Math.floorDiv(minX, step) * step; minZ = Math.floorDiv(minZ, step) * step;
        int width = (int) ((maxX - minX + step - 1) / step), depth = (int) ((maxZ - minZ + step - 1) / step);
        byte[] rgba = new byte[width * depth * 4]; int[] heights = new int[width * depth];
        tiles.sort(Comparator.comparingInt((Tile tile) -> tile.key.level).reversed());
        for (Tile tile : tiles) {
            int firstX = Math.max(0, (int) ((tile.key.x() - minX) / step));
            int firstZ = Math.max(0, (int) ((tile.key.z() - minZ) / step));
            int endX = Math.min(width, (int) ((tile.key.x() + 64L * tile.key.step() - minX + step - 1) / step));
            int endZ = Math.min(depth, (int) ((tile.key.z() + 64L * tile.key.step() - minZ + step - 1) / step));
            for (int z = firstZ; z < endZ; z++) for (int x = firstX; x < endX; x++) {
                int tx = (int) ((minX + x * (long) step - tile.key.x()) / tile.key.step());
                int tz = (int) ((minZ + z * (long) step - tile.key.z()) / tile.key.step());
                if (tx < 0 || tz < 0 || tx >= 64 || tz >= 64) continue;
                int source = tz * 64 + tx, destination = z * width + x;
                if (tile.rgba[source * 4 + 3] == 0) continue;
                System.arraycopy(tile.rgba, source * 4, rgba, destination * 4, 4);
                float illumination = Math.max(brightness, Byte.toUnsignedInt(tile.light[source]) / 255.0F);
                for (int channel = 0; channel < 3; channel++)
                    rgba[destination * 4 + channel] = (byte) Math.round(Byte.toUnsignedInt(tile.rgba[source * 4 + channel]) * illumination);
                heights[destination] = tile.heights[source];
            }
        }
        return new Surface(minX, minZ, width, depth, step, rgba, heights, revision);
    }

    private void finishPublish() {
        if (publishing == null || !publishing.isDone()) return;
        try {
            Surface ready = publishing.join();
            if (publishGeneration == generation && visibleViewport != null && publishingViewport.contains(visibleViewport)) {
                surface = ready;
            } else dirty = true;
        } catch (java.util.concurrent.CompletionException exception) {
            LogUtils.getLogger().error("Cannot composite mapped Xaero terrain", exception.getCause());
        } finally { publishing = null; }
    }

    public Surface surface() { return surface; }
    public AuiNativeTileSession models() { return models; }
    public Map<AuiChunkTiles.Tile, Integer> visibleModels() { return visibleModels; }
    public long sceneKey() { return generation; }
    public double presentationUnit() { return 1; }
    public float brightness() { return brightness; }
    public int heightAt(double x, double z, int unknown) {
        return heightAt(x, z, unknown, false);
    }

    private int heightAt(double x, double z, int unknown, boolean leafOnly) {
        int worldX = (int) Math.floor(x), worldZ = (int) Math.floor(z);
        var tile = new AuiChunkTiles.Tile(Math.floorDiv(worldX, 32), Math.floorDiv(worldZ, 32));
        int quadrant = (Math.floorMod(worldZ, 32) / 16) * 2 + Math.floorMod(worldX, 32) / 16;
        if (!leafOnly && models != null && (visibleModels.getOrDefault(tile, 0) & (1 << quadrant)) != 0)
            return models.heightAt(x, z, unknown);
        return mappedHeightAt(x, z, unknown, leafOnly);
    }

    private int mappedHeightAt(double x, double z, int unknown, boolean leafOnly) {
        for (int level = 0; level <= (leafOnly ? 0 : 3); level++) {
            int step = 1 << level;
            long tileX = (long) Math.floor(x / (64 * step)), tileZ = (long) Math.floor(z / (64 * step));
            Key key = new Key(level, (int) Math.floorDiv(tileX, 8), (int) Math.floorDiv(tileZ, 8),
                    (int) Math.floorMod(tileX, 8), (int) Math.floorMod(tileZ, 8));
            Tile tile = tiles.get(key);
            if (tile == null) continue;
            int index = (int) ((z - key.z()) / step) * 64 + (int) ((x - key.x()) / step);
            if (tile.heights[index] == 32767 || tile.rgba[index * 4 + 3] == 0) continue;
            return tile.heights[index];
        }
        return unknown;
    }

    private boolean mappedQuarter(int x, int z) {
        for (int level = 0; level <= 3; level++) {
            int step = 1 << level;
            int tx = Math.floorDiv(x, 64 * step), tz = Math.floorDiv(z, 64 * step);
            Key key = new Key(level, Math.floorDiv(tx, 8), Math.floorDiv(tz, 8), Math.floorMod(tx, 8), Math.floorMod(tz, 8));
            Tile tile = tiles.get(key);
            if (tile == null) continue;
            int x0 = (int) ((x - key.x()) / step), z0 = (int) ((z - key.z()) / step);
            int x1 = x0 + 16 / step, z1 = z0 + 16 / step;
            int[] sums = tile.unmapped;
            return sums[z1 * 65 + x1] - sums[z1 * 65 + x0] - sums[z0 * 65 + x1] + sums[z0 * 65 + x0] == 0;
        }
        return false;
    }

    @Override public void close() {
        if (closed) return;
        MemoryUtil.memFree(readback);
        closed = true; generation++; tiles.clear(); reading.clear();
        surface = null; models = null; visibleModels = Map.of();
        if (publishing != null) publishing.cancel(false);
        conversion.shutdownNow();
    }
}
