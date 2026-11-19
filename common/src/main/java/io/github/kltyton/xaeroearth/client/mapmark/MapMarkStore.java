package io.github.kltyton.xaeroearth.client.mapmark;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.world.phys.Vec3;

/** The client owns immutable map snapshots; the disk executor serializes reads and writes. */
final class MapMarkStore implements AutoCloseable {
    private static final int LIMIT = 1024;
    private static final long FILE_LIMIT = 16L << 20;
    private static final Gson JSON = new Gson();
    private final Path path;
    private final ExecutorService disk = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "Earth map marks");
        thread.setDaemon(true);
        return thread;
    });
    private final CompletableFuture<Map<MapScope, List<MapMark>>> initial;
    private Map<MapScope, List<MapMark>> marks;
    private int count;

    MapMarkStore(Path path) {
        this.path = path;
        initial = CompletableFuture.supplyAsync(this::read, disk);
    }

    boolean ready() {
        if (marks != null) return true;
        if (!initial.isDone() || initial.isCompletedExceptionally()) return false;
        marks = initial.join();
        count = marks.values().stream().mapToInt(List::size).sum();
        return true;
    }

    List<MapMark> get(MapScope scope) {
        return scope != null && ready() ? marks.getOrDefault(scope, List.of()) : List.of();
    }

    boolean add(MapScope scope, MapMark mark) {
        if (scope == null || !ready()) return false;
        var group = new ArrayList<>(get(scope));
        int existing = -1;
        for (int i = 0; i < group.size(); i++) if (group.get(i).id().equals(mark.id())) { existing = i; break; }
        if (existing >= 0) group.set(existing, mark);
        else {
            if (count >= LIMIT) return false;
            group.add(mark); count++;
        }
        update(scope, group);
        return true;
    }

    void remove(MapScope scope, UUID id) {
        if (scope == null || !ready()) return;
        var group = new ArrayList<>(get(scope));
        if (group.removeIf(mark -> mark.id().equals(id))) {
            count--;
            update(scope, group);
        }
    }

    private void update(MapScope scope, List<MapMark> group) {
        var snapshot = new HashMap<>(marks);
        if (group.isEmpty()) snapshot.remove(scope); else snapshot.put(scope, List.copyOf(group));
        var saved = Map.copyOf(snapshot);
        marks = saved;
        disk.execute(() -> write(saved));
    }

    private Map<MapScope, List<MapMark>> read() {
        if (!Files.exists(path)) return Map.of();
        try (var reader = Files.newBufferedReader(path)) {
            if (Files.size(path) > FILE_LIMIT) throw new IOException("Map mark file exceeds its size limit");
            var root = JSON.fromJson(reader, JsonObject.class);
            if (root == null || root.get("format").getAsInt() != 1) throw new IOException("Unknown map mark format");
            var loaded = new HashMap<MapScope, List<MapMark>>();
            int entries = 0;
            for (var entry : root.getAsJsonArray("marks")) {
                if (++entries > LIMIT) throw new IOException("Too many map marks");
                var object = entry.getAsJsonObject();
                var scope = new MapScope(object.get("world").getAsString(), object.get("dimension").getAsString(),
                        object.get("subworld").getAsString(), object.get("layer").getAsInt());
                var points = new ArrayList<Vec3>();
                for (var value : object.getAsJsonArray("points")) {
                    if (points.size() >= MapMark.POINT_LIMIT) throw new IOException("Too many map mark points");
                    var point = value.getAsJsonArray();
                    points.add(new Vec3(point.get(0).getAsDouble(), point.get(1).getAsDouble(), point.get(2).getAsDouble()));
                }
                var mark = new MapMark(UUID.fromString(object.get("id").getAsString()),
                        MapMark.Kind.valueOf(object.get("kind").getAsString()), points, object.get("text").getAsString());
                loaded.computeIfAbsent(scope, unused -> new ArrayList<>()).add(mark);
            }
            loaded.replaceAll((scope, value) -> List.copyOf(value));
            return Map.copyOf(loaded);
        } catch (IOException | RuntimeException failure) {
            LogUtils.getLogger().error("Cannot load Earth map marks from {}", path, failure);
            // Refuse edits so a format or I/O failure cannot overwrite existing annotations.
            throw new java.util.concurrent.CompletionException(failure);
        }
    }

    private void write(Map<MapScope, List<MapMark>> snapshot) {
        var root = new JsonObject();
        root.addProperty("format", 1);
        var array = new JsonArray();
        root.add("marks", array);
        snapshot.forEach((scope, marks) -> marks.forEach(mark -> {
            var row = new JsonObject();
            row.addProperty("world", scope.world());
            row.addProperty("dimension", scope.dimension());
            row.addProperty("subworld", scope.subworld());
            row.addProperty("layer", scope.layer());
            row.addProperty("id", mark.id().toString());
            row.addProperty("kind", mark.kind().name());
            row.addProperty("text", mark.text());
            var points = new JsonArray();
            for (Vec3 point : mark.points()) {
                var vertex = new JsonArray();
                vertex.add(point.x); vertex.add(point.y); vertex.add(point.z);
                points.add(vertex);
            }
            row.add("points", points);
            array.add(row);
        }));
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (var writer = Files.newBufferedWriter(temporary)) { JSON.toJson(root, writer); }
            if (Files.size(temporary) > FILE_LIMIT) throw new IOException("Map mark file would exceed its size limit");
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException failure) {
            LogUtils.getLogger().error("Cannot save Earth map marks to {}", path, failure);
        }
    }

    @Override public void close() {
        disk.shutdown();
        try {
            if (!disk.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
                LogUtils.getLogger().warn("Earth map mark writes are still pending at shutdown");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
