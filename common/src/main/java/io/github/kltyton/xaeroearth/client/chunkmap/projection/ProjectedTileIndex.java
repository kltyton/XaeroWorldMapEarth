package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTiles;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Tracks which world meshes can affect each visible projected image tile on the render thread. */
public final class ProjectedTileIndex {
    public record Mesh(Source source, int mask, DetailedGeometry.Bounds bounds,
                       boolean animated, BitSet solidMaterialStates) {
        public Mesh(ChunkTiles.Tile tile, String contentKey, int mask, DetailedGeometry.Bounds bounds,
                    boolean animated, BitSet solidMaterialStates) {
            this(new Source(tile, contentKey), mask, bounds, animated, solidMaterialStates);
        }
        public Mesh(ChunkTiles.Tile tile, String contentKey, int mask, DetailedGeometry.Bounds bounds, boolean animated) {
            this(tile, contentKey, mask, bounds, animated, null);
        }

        public ChunkTiles.Tile tile() { return source.tile(); }
        public String contentKey() { return source.contentKey(); }
    }
    public record Source(ChunkTiles.Tile tile, String contentKey) { }
    private static final Comparator<Source> DISK_SOURCE_ORDER = Comparator
            .comparingInt((Source source) -> source.tile().x())
            .thenComparingInt(source -> source.tile().z()).thenComparing(Source::contentKey);

    /** An immutable dependency snapshot that can be hashed away from the render thread. */
    public static final class DiskKey {
        private final ProjectedGrid.View view;
        private final ProjectedGrid.Cell cell;
        private final String scope, resources;
        private final Map<Source, Integer> members;
        private volatile String fingerprint;

        private DiskKey(ProjectedGrid.View view, ProjectedGrid.Cell cell, String scope, String resources,
                        Map<Source, Integer> members) {
            this.view = view;
            this.cell = cell;
            this.scope = scope;
            this.resources = resources;
            this.members = members;
        }

        public String fingerprint() {
            String known = fingerprint;
            if (known != null) return known;
            MessageDigest digest = digest();
            try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                output.writeUTF("projected-opaque-v2");
                writeSlotIdentity(output, view, cell, scope);
                output.writeUTF(resources);
                var meshes = new ArrayList<>(members.keySet());
                meshes.sort(DISK_SOURCE_ORDER);
                output.writeInt(meshes.size());
                for (var source : meshes) {
                    output.writeInt(source.tile().x());
                    output.writeInt(source.tile().z());
                    output.writeUTF(source.contentKey());
                    output.writeInt(members.get(source));
                }
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
            known = HexFormat.of().formatHex(digest.digest());
            fingerprint = known;
            return known;
        }
    }
    private record Extent(DetailedGeometry.Bounds sourceBounds, ProjectedGrid.Bounds projected,
                          ProjectedGrid.Depth depth) {
        boolean matches(Mesh mesh) {
            return sourceBounds.equals(mesh.bounds());
        }

        static Extent project(ProjectedGrid grid, Mesh mesh) {
            var face = mesh.bounds();
            return new Extent(face, cover(grid, mesh.tile(), face), grid.depthRange(mesh.tile(), face));
        }

        private static ProjectedGrid.Bounds cover(ProjectedGrid grid, ChunkTiles.Tile tile, DetailedGeometry.Bounds bounds) {
            double x = tile.x() * 32.0, z = tile.z() * 32.0;
            return grid.covering(x + bounds.minX(), bounds.minY(), z + bounds.minZ(),
                    x + bounds.maxX(), bounds.maxY(), z + bounds.maxZ());
        }
    }
    private record Footprint(Extent extent, int mask, ProjectedGrid.Bounds bounds) { }

    private final Map<Source, Footprint> sources = new HashMap<>();
    private final Map<ProjectedGrid.Cell, Set<Source>> contributors = new HashMap<>();
    private final Map<ProjectedGrid.Cell, Map<Source, Integer>> contributorMasks = new HashMap<>();
    private final Map<ProjectedGrid.Cell, ProjectedGrid.Depth> depthRanges = new HashMap<>();
    private final Map<ProjectedGrid.Cell, Long> revisions = new HashMap<>();
    private ProjectedGrid.View view;
    private ProjectedGrid.Bounds visibleBounds;
    private List<Mesh> indexedMeshes = List.of();
    private long revision;

    void reset() {
        sources.clear(); contributors.clear(); contributorMasks.clear(); depthRanges.clear(); revisions.clear();
        view = null; visibleBounds = null;
        indexedMeshes = List.of();
        revision++;
    }

    public void update(ProjectedGrid grid, List<Mesh> meshes) {
        if (!grid.view().equals(view)) {
            reset();
            view = grid.view();
        }
        var visible = grid.visible();
        boolean sameViewport = visible.equals(visibleBounds);
        if (sameViewport && indexedMeshes.equals(meshes)) return;
        var active = new HashSet<Source>();
        for (Mesh mesh : meshes) {
            if (mesh.mask() == 0 || mesh.animated()) continue;
            var sourceKey = mesh.source();
            active.add(sourceKey);
            var previous = sources.get(sourceKey);
            boolean sameExtent = previous != null && previous.extent().matches(mesh);
            boolean sameMask = previous != null && previous.mask() == mesh.mask();
            if (sameViewport && sameExtent && sameMask) continue;
            // A camera translation only moves the visible cell rectangle. Keep the mesh's
            // projected bounds and depth until the geometry or projection itself changes.
            var extent = sameExtent ? previous.extent() : Extent.project(grid, mesh);
            var bounds = intersect(extent.projected(), visible);
            var next = new Footprint(extent, mesh.mask(), bounds);
            if (next.equals(previous)) continue;
            sources.put(sourceKey, next);
            var unchanged = sameExtent && sameMask && previous.bounds() != null && bounds != null
                    ? intersect(previous.bounds(), bounds) : null;
            if (previous != null) remove(sourceKey, previous.bounds(), unchanged);
            add(sourceKey, bounds, unchanged);
        }
        var iterator = sources.entrySet().iterator();
        while (iterator.hasNext()) {
            var source = iterator.next();
            if (active.contains(source.getKey())) continue;
            remove(source.getKey(), source.getValue().bounds(), null);
            iterator.remove();
        }
        revisions.keySet().removeIf(cell -> cell.x() < visible.minX() || cell.x() > visible.maxX()
                || cell.y() < visible.minY() || cell.y() > visible.maxY());
        visibleBounds = visible;
        indexedMeshes = List.copyOf(meshes);
    }

    public long revision(ProjectedGrid.Cell cell) { return revisions.getOrDefault(cell, 0L); }
    public boolean hasContributors(ProjectedGrid.Cell cell) { return contributors.containsKey(cell); }
    private Set<Source> contributorSources(ProjectedGrid.Cell cell) {
        var found = contributors.get(cell);
        return found == null ? Set.of() : found;
    }

    Iterable<Source> members(ProjectedGrid.Cell cell) { return contributorSources(cell); }

    public Map<Source, Integer> contributorMasks(ProjectedGrid.Cell cell) {
        if (!contributors.containsKey(cell)) return Map.of();
        return contributorMasks.computeIfAbsent(cell, key -> {
            var masks = new HashMap<Source, Integer>();
            for (var source : contributorSources(key)) masks.put(source, sources.get(source).mask());
            return Map.copyOf(masks);
        });
    }

    public boolean containsDepth(Iterable<Source> members, ProjectedGrid grid, double x, double y, double z) {
        double translation = grid.depthTranslation(x, y, z);
        for (var source : members) {
            var footprint = sources.get(source);
            if (footprint == null || !ProjectedGrid.containsDepth(footprint.extent().depth(), translation)) return false;
        }
        return true;
    }

    public boolean containsDepth(ProjectedGrid.Cell cell, ProjectedGrid grid, double x, double y, double z) {
        var range = depthRange(cell);
        return range != null && ProjectedGrid.containsDepth(range, grid.depthTranslation(x, y, z));
    }

    ProjectedGrid.Depth depthRange(ProjectedGrid.Cell cell) {
        if (!contributors.containsKey(cell)) return null;
        return depthRanges.computeIfAbsent(cell, key -> {
            ProjectedGrid.Depth combined = null;
            for (var source : contributorSources(key)) {
                var depth = sources.get(source).extent().depth();
                combined = combined == null ? depth : combined.include(depth);
            }
            return combined;
        });
    }

    /** Sources represented by pictures still need geometry for their missing cells or uncovered quadrants. */
    public Map<Source, List<ProjectedGrid.Cell>> missingPictureCells(
            Map<ProjectedGrid.Cell, Map<Source, Integer>> images, Set<Source> resident) {
        return missingPictureCells(images, resident, Function.identity());
    }

    <T> Map<Source, List<T>> missingPictureCells(Map<ProjectedGrid.Cell, Map<Source, Integer>> images,
            Set<Source> resident, Function<ProjectedGrid.Cell, T> project) {
        var missing = new HashMap<Source, List<T>>(Math.min(resident.size(), 64) * 2);
        boolean complete = images.size() == contributors.size();
        if (complete) {
            for (var cell : contributors.entrySet()) {
                var shown = images.get(cell.getKey());
                var required = contributorMasks(cell.getKey());
                if (shown == null || !coversAll(shown, required)) {
                    complete = false;
                    break;
                }
            }
        }
        for (var image : images.values()) for (var source : image.keySet()) {
            if (resident.contains(source)) missing.putIfAbsent(source, List.of());
        }
        if (missing.isEmpty() || complete) return missing;
        for (var cell : contributors.entrySet()) {
            var shown = images.get(cell.getKey());
            var required = contributorMasks(cell.getKey());
            if (shown != null && coversAll(shown, required)) continue;
            T projected = null;
            for (var member : required.entrySet()) {
                Source source = member.getKey();
                var cells = missing.get(source);
                if (cells == null) continue;
                int requiredMask = member.getValue();
                if (shown != null && (shown.getOrDefault(source, 0) & requiredMask) == requiredMask) continue;
                if (projected == null) {
                    projected = project.apply(cell.getKey());
                    if (projected == null) break;
                }
                if (cells.isEmpty()) {
                    cells = new ArrayList<>();
                    missing.put(source, cells);
                }
                cells.add(projected);
            }
        }
        return missing;
    }

    private static boolean coversAll(Map<Source, Integer> shown, Map<Source, Integer> required) {
        if (shown == required) return true;
        if (shown.size() < required.size()) return false;
        for (var entry : required.entrySet()) {
            int mask = entry.getValue();
            if ((shown.getOrDefault(entry.getKey(), 0) & mask) != mask) return false;
        }
        return true;
    }

    /** An already visible picture within one cell of the viewport edge can need live geometry on the next pan. */
    boolean touchesViewportEdge(Source source) {
        var footprint = sources.get(source);
        if (footprint == null || footprint.bounds() == null) return true;
        var bounds = footprint.bounds();
        return bounds.minX() <= visibleBounds.minX() + 1 || bounds.minY() <= visibleBounds.minY() + 1
                || bounds.maxX() >= visibleBounds.maxX() - 1 || bounds.maxY() >= visibleBounds.maxY() - 1;
    }

    /** One replaceable disk slot per world scope, view and projected cell. */
    public String slot(ProjectedGrid.Cell cell, String scope) {
        if (view == null) throw new IllegalStateException("Projected view has not been indexed");
        MessageDigest digest = digest();
        try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            output.writeUTF("projected-slot-v1");
            writeSlotIdentity(output, view, cell, scope);
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Stable across sessions; all overlapping geometry participates in the image identity. */
    public DiskKey diskKey(ProjectedGrid.Cell cell, String scope, String resources) {
        if (view == null) throw new IllegalStateException("Projected view has not been indexed");
        return new DiskKey(view, cell, scope, resources, contributorMasks(cell));
    }

    private static void writeSlotIdentity(DataOutputStream output, ProjectedGrid.View view, ProjectedGrid.Cell cell,
                                          String scope) throws IOException {
        output.writeUTF(scope);
        output.writeInt(view.width()); output.writeInt(view.height());
        for (int bits : view.matrixBits()) output.writeInt(bits);
        output.writeLong(cell.x()); output.writeLong(cell.y());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void add(Source source, ProjectedGrid.Bounds bounds, ProjectedGrid.Bounds retained) {
        if (bounds == null) return;
        for (long y = bounds.minY(); y <= bounds.maxY(); y++) for (long x = bounds.minX(); x <= bounds.maxX(); x++) {
            if (contains(retained, x, y)) continue;
            var cell = new ProjectedGrid.Cell(x, y);
            contributors.computeIfAbsent(cell, ignored -> new HashSet<>()).add(source);
            changed(cell);
        }
    }

    private void remove(Source source, ProjectedGrid.Bounds bounds, ProjectedGrid.Bounds retained) {
        if (bounds == null) return;
        for (long y = bounds.minY(); y <= bounds.maxY(); y++) for (long x = bounds.minX(); x <= bounds.maxX(); x++) {
            if (contains(retained, x, y)) continue;
            var cell = new ProjectedGrid.Cell(x, y);
            var members = contributors.get(cell);
            if (members != null && members.remove(source)) {
                if (members.isEmpty()) contributors.remove(cell);
                changed(cell);
            }
        }
    }

    private void changed(ProjectedGrid.Cell cell) {
        contributorMasks.remove(cell);
        depthRanges.remove(cell);
        revisions.put(cell, ++revision);
    }

    private static boolean contains(ProjectedGrid.Bounds bounds, long x, long y) {
        return bounds != null && x >= bounds.minX() && x <= bounds.maxX()
                && y >= bounds.minY() && y <= bounds.maxY();
    }

    private static ProjectedGrid.Bounds intersect(ProjectedGrid.Bounds first, ProjectedGrid.Bounds second) {
        long minX = Math.max(first.minX(), second.minX()), minY = Math.max(first.minY(), second.minY());
        long maxX = Math.min(first.maxX(), second.maxX()), maxY = Math.min(first.maxY(), second.maxY());
        return minX > maxX || minY > maxY ? null : new ProjectedGrid.Bounds(minX, minY, maxX, maxY);
    }
}
