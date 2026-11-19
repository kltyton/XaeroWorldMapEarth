package io.github.kltyton.xaeroearth.client.integration.create;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeMesh;
import io.github.kltyton.xaeroearth.client.chunkmap.ActiveMapResources;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class ContraptionTiles {
    record Baked(ChunkTiles.Tile tile, KuiNativeMesh mesh) { }
    private static final int TILE = 32, HALO = 1, SPAN = TILE + 2 * HALO;

    static CompletableFuture<List<Baked>> bake(ActiveMapResources resources, Map<BlockPos, BlockState> states,
                Map<BlockPos, BlockEntity> modelEntities, Holder<Biome> biome, Executor worker, BooleanSupplier cancelled) {
        return resources.renderer().thenComposeAsync(renderer -> {
            var groups = new HashMap<ChunkTiles.Tile, List<Map.Entry<BlockPos, BlockState>>>();
            for (var block : states.entrySet()) {
                var p = block.getKey();
                for (int z = Math.floorDiv(p.getZ() - HALO, TILE); z <= Math.floorDiv(p.getZ() + HALO, TILE); z++)
                    for (int x = Math.floorDiv(p.getX() - HALO, TILE); x <= Math.floorDiv(p.getX() + HALO, TILE); x++)
                        groups.computeIfAbsent(new ChunkTiles.Tile(x, z), unused -> new ArrayList<>()).add(block);
            }
            var jobs = new ArrayList<CompletableFuture<Baked>>();
            groups.forEach((tile, group) -> {
                if (cancelled.getAsBoolean()) return;
                int x0 = tile.x() * TILE - HALO, z0 = tile.z() * TILE - HALO;
                if (group.stream().noneMatch(b -> b.getKey().getX() > x0 && b.getKey().getX() <= x0 + TILE
                        && b.getKey().getZ() > z0 && b.getKey().getZ() <= z0 + TILE)) return;
                int low = group.stream().mapToInt(b -> b.getKey().getY()).min().orElseThrow();
                int high = group.stream().mapToInt(b -> b.getKey().getY()).max().orElseThrow() + 1;
                var snapshot = ChunkMapSnapshot.builder(x0, z0, SPAN, SPAN, 1, low, high);
                for (int z = 0; z < SPAN; z++) for (int x = 0; x < SPAN; x++)
                    snapshot.setBiome(x, z, biome, x0 + x, z0 + z);
                group.sort(java.util.Comparator.comparingInt(b -> b.getKey().getY()));
                for (var block : group) {
                    var p = block.getKey();
                    snapshot.addRun(p.getX() - x0, p.getZ() - z0, p.getY(), p.getY() + 1, block.getValue());
                }
                boolean[] known = new boolean[SPAN * SPAN];
                Arrays.fill(known, true);
                jobs.add(renderer.renderContraptionTile(snapshot.build(), tile, known, modelEntities, cancelled)
                        .thenApplyAsync(result -> {
                            try { return new Baked(tile, KuiNativeMesh.decode(result.model())); }
                            catch (IOException failure) { throw new UncheckedIOException(failure); }
                        }, worker));
            });
            return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).handle((unused, failure) -> {
                var completed = new ArrayList<Baked>();
                for (var job : jobs) if (!job.isCompletedExceptionally()) completed.add(job.join());
                if (failure != null || cancelled.getAsBoolean()) {
                    for (var mesh : completed) mesh.mesh().close();
                    if (failure != null) throw new java.util.concurrent.CompletionException(failure);
                    return List.<Baked>of();
                }
                return List.copyOf(completed);
            });
        }, worker);
    }
    private ContraptionTiles() { }
}
