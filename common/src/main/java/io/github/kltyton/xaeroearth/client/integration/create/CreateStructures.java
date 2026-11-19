package io.github.kltyton.xaeroearth.client.integration.create;

import com.mojang.logging.LogUtils;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTiles;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.FrustumIntersection;
import org.joml.Quaternionf;
import xaero.map.WorldMapSession;

/** Live Create models keep independent GPU ownership and use Create's camera-relative motion. */
public final class CreateStructures {
    private static final long UPLOAD_BYTES = 2L << 20;
    private record Pose(Vec3 origin, Quaternionf rotation) { }
    private record BakedTile(ChunkTiles.Tile tile, KuiNativeTerrainRenderer.GpuMesh mesh) { }
    public record Frame(List<KuiNativeTerrainRenderer.Draw> terrain, List<ContraptionBlockRender> blocks) {
        private static final Frame EMPTY = new Frame(List.of(), List.of());
    }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "Earth Create geometry");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<UUID, Structure> structures = new HashMap<>();
    private static boolean linked;
    private static ContraptionSource source;
    private static MapScope scope;
    private static ActiveMapResources activeResources;
    private static long tickTime;
    private static int ticks;

    public static void tick(Minecraft mc, ActiveMapResources resources) {
        if (mc.level == null || mc.player == null || resources == null || resources.isClosed()) {
            reset();
            return;
        }
        if (!linked) { linked = true; source = ContraptionSource.open(); }
        if (source == null) return;
        var session = WorldMapSession.getCurrentSession();
        var processor = session == null ? null : session.getMapProcessor();
        var selected = MapScope.selected(processor);
        if (selected == null || selected.layer() != Integer.MAX_VALUE || processor.getWorld() != mc.level
                || processor.isConsideringNetherFairPlay() || !processor.isCurrentMultiworldWritable()
                || processor.getMapWorld() == null || !mc.level.dimension().equals(processor.getMapWorld().getCurrentDimensionId())) {
            reset();
            return;
        }
        if (!selected.equals(scope) || resources != activeResources) {
            reset();
            scope = selected; activeResources = resources;
        }
        tickTime = System.nanoTime();
        var active = new HashSet<UUID>();
        int periodicScans = 0;
        try {
            // Remote worlds contribute only the entities already tracked by this client, under
            // the same current-map and subworld checks as the local world.
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity.isRemoved() || !source.accepts(entity)) continue;
                var snapshot = source.snapshot(entity);
                if (snapshot == null) continue;
                active.add(entity.getUUID());
                var structure = structures.computeIfAbsent(entity.getUUID(),
                        id -> new Structure(Math.floorMod(id.hashCode(), 40)));
                structure.entity = entity;
                structure.previous = pose(entity, 0);
                structure.next = pose(entity, 1);
                var biome = mc.level.getBiome(entity.blockPosition());
                Map<BlockPos, BlockState> states = structure.states;
                Map<BlockPos, CompoundTag> entityTags = structure.entityTags;
                boolean snapshotChanged = structure.snapshot != snapshot;
                boolean periodicDue = !snapshotChanged && periodicScans == 0 && (ticks + structure.scanPhase) % 40 == 0;
                if (snapshotChanged || periodicDue) {
                    if (periodicDue) periodicScans++;
                    var blocks = source.blocks(snapshot);
                    var captured = new HashMap<BlockPos, BlockState>();
                    var capturedEntities = new HashMap<BlockPos, CompoundTag>();
                    blocks.forEach((p, block) -> {
                        var state = ContraptionBlockState.state(block);
                        if (state.isAir()) return;
                        var position = ((BlockPos) p).immutable();
                        captured.put(position, state);
                        var data = ContraptionBlockState.data(block);
                        if (state.hasBlockEntity() && data != null) capturedEntities.put(position, data.copy());
                    });
                    structure.snapshot = snapshot;
                    if (!structure.states.equals(captured)) states = Map.copyOf(captured);
                    if (!structure.entityTags.equals(capturedEntities)) entityTags = Map.copyOf(capturedEntities);
                }
                boolean geometryChanged = structure.states != states || structure.entityTags != entityTags;
                if (geometryChanged || !biome.equals(structure.biome)) {
                    structure.cancelPending();
                    if (geometryChanged) {
                        var modelEntities = new HashMap<BlockPos, BlockEntity>();
                        for (var entry : entityTags.entrySet()) {
                            var position = entry.getKey();
                            var detached = ContraptionBlockEntities.load(mc.level, position, states.get(position), entry.getValue());
                            if (detached != null) modelEntities.put(position, detached);
                        }
                        structure.modelEntities = Map.copyOf(modelEntities);
                    }
                    structure.states = states;
                    structure.entityTags = entityTags;
                    structure.biome = biome;
                    structure.bakingSnapshot = snapshot;
                    var token = structure.cancelled;
                    structure.pending = ContraptionTiles.bake(resources, states, structure.modelEntities, biome, WORKER, token::get);
                } else if (structure.pending == null && structure.decoded == null && !structure.uploaded.isEmpty()) {
                    structure.renderSnapshot = snapshot;
                }
            }
            ticks++;
            structures.entrySet().removeIf(entry -> {
                if (active.contains(entry.getKey())) return false;
                entry.getValue().close();
                return true;
            });
        } catch (Throwable failure) {
            if (failure instanceof Error && !(failure instanceof LinkageError)) throw (Error) failure;
            LogUtils.getLogger().warn("Cannot capture the installed Create build for Earth's map", failure);
            source = null;
            reset();
        }
    }

    public static Frame frame(MapScope selected, MapCamera camera,
                              KuiNativeTerrainRenderer renderer, float brightness) {
        if (selected == null || !selected.equals(scope) || structures.isEmpty()) return Frame.EMPTY;
        var draws = new ArrayList<KuiNativeTerrainRenderer.Draw>();
        var blockEntities = new ArrayList<ContraptionBlockRender>();
        float tick = (float) Math.max(0, Math.min(1, (System.nanoTime() - tickTime) / 50_000_000.0));
        Matrix4f cameraView = camera.viewMatrix();
        var frustum = new FrustumIntersection(camera.viewportProjectionMatrix().mul(cameraView));
        long budget = UPLOAD_BYTES;
        for (var structure : structures.values()) {
            if (structure.entity.isRemoved()) continue;
            var box = structure.entity.getBoundingBox();
            if (!frustum.testAab((float) (box.minX - camera.x() - 2), (float) (box.minY - camera.y() - 2),
                    (float) (box.minZ - camera.z() - 2), (float) (box.maxX - camera.x() + 2),
                    (float) (box.maxY - camera.y() + 2), (float) (box.maxZ - camera.z() + 2))) continue;
            if (budget > 0) budget -= structure.install(renderer, budget);
            if (structure.previous == null || structure.next == null) continue;
            var position = structure.previous.origin.lerp(structure.next.origin, tick);
            var orientation = new Quaternionf(structure.previous.rotation).slerp(structure.next.rotation, tick);
            var view = new Matrix4f(cameraView).translate((float) (position.x - camera.x()), (float) (position.y - camera.y()),
                    (float) (position.z - camera.z())).rotate(orientation);
            for (BakedTile tile : structure.uploaded) {
                var modelView = new Matrix4f(view).translate(tile.tile.x() * 32.0F, 0, tile.tile.z() * 32.0F);
                draws.add(new KuiNativeTerrainRenderer.Draw(tile.mesh, modelView, 15, brightness));
            }
            if (structure.renderSnapshot != null && !structure.renderBlocksFailed) {
                try {
                    for (Object block : source.renderBlocks(structure.renderSnapshot)) {
                        if (block instanceof BlockEntity entity && !entity.isRemoved())
                            blockEntities.add(new ContraptionBlockRender(entity, position, orientation, tick));
                    }
                } catch (Throwable failure) {
                    if (failure instanceof Error && !(failure instanceof LinkageError)) throw (Error) failure;
                    structure.renderBlocksFailed = true;
                    LogUtils.getLogger().warn("Cannot read the animated Create block entities for Earth", failure);
                }
            }
        }
        return new Frame(draws, blockEntities);
    }

    private static Pose pose(Entity entity, float tick) throws Throwable {
        Vec3 p = source.transform(entity, Vec3.ZERO, tick);
        Vec3 x = source.transform(entity, new Vec3(1, 0, 0), tick).subtract(p);
        Vec3 y = source.transform(entity, new Vec3(0, 1, 0), tick).subtract(p);
        Vec3 z = source.transform(entity, new Vec3(0, 0, 1), tick).subtract(p);
        var basis = new Matrix3f(
                (float) x.x, (float) x.y, (float) x.z, (float) y.x, (float) y.y, (float) y.z,
                (float) z.x, (float) z.y, (float) z.z);
        return new Pose(p, new Quaternionf().setFromNormalized(basis));
    }

    public static void reset() {
        for (Structure structure : structures.values()) structure.close();
        structures.clear();
        scope = null; activeResources = null; ticks = 0;
    }

    private static final class Structure {
        final int scanPhase;
        Entity entity;
        Structure(int scanPhase) { this.scanPhase = scanPhase; }
        Object snapshot, bakingSnapshot, renderSnapshot;
        boolean renderBlocksFailed;
        Pose previous, next;
        Holder<Biome> biome;
        Map<BlockPos, BlockState> states = Map.of();
        Map<BlockPos, CompoundTag> entityTags = Map.of();
        Map<BlockPos, BlockEntity> modelEntities = Map.of();
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<List<ContraptionTiles.Baked>> pending;
        List<BakedTile> uploaded = List.of();
        List<ContraptionTiles.Baked> decoded;
        final List<BakedTile> staging = new ArrayList<>();
        int nextTile;

        long install(KuiNativeTerrainRenderer renderer, long budget) {
            if (decoded == null && (pending == null || !pending.isDone())) return 0;
            long bytes = 0;
            try {
                if (decoded == null) {
                    var completed = pending;
                    pending = null;
                    decoded = completed.join();
                }
                while (nextTile < decoded.size()) {
                    var tile = decoded.get(nextTile);
                    long size = tile.mesh().vertices().remaining();
                    if (size > budget - bytes && (bytes != 0 || budget < UPLOAD_BYTES)) break;
                    nextTile++;
                    try (var mesh = tile.mesh()) {
                        staging.add(new BakedTile(tile.tile(), renderer.upload(mesh)));
                        bytes += size;
                    }
                }
                if (nextTile == decoded.size()) {
                    uploaded.forEach(tile -> tile.mesh.close());
                    uploaded = List.copyOf(staging);
                    renderSnapshot = bakingSnapshot;
                    renderBlocksFailed = false;
                    staging.clear(); decoded = null; nextTile = 0;
                }
            } catch (RuntimeException failure) {
                clearStaging();
                LogUtils.getLogger().warn("Cannot bake a Create moving structure for Earth", failure);
            }
            return bytes;
        }

        void clearStaging() {
            if (decoded != null) for (; nextTile < decoded.size(); nextTile++) decoded.get(nextTile).mesh().close();
            staging.forEach(tile -> tile.mesh.close());
            staging.clear(); decoded = null; nextTile = 0;
        }

        void cancelPending() {
            cancelled.set(true);
            if (pending != null) pending.whenComplete((decoded, failure) -> {
                if (decoded != null) decoded.forEach(tile -> tile.mesh().close());
            });
            pending = null; cancelled = new AtomicBoolean();
            clearStaging();
        }

        void close() {
            cancelPending();
            uploaded.forEach(tile -> tile.mesh.close());
            uploaded = List.of();
        }
    }
    private CreateStructures() { }
}
