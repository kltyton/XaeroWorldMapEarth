package io.github.kltyton.xaeroearth.client.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Incremental 3D search with continuous collision checks between waypoints. */
final class FlyingRoute {
    private static final double LEG = 48;
    private static final double SWEEP = 18;
    private static final int MAX_NODES = 1800;

    record Plan(List<Vec3> steps, boolean arrives) {}
    private record Node(BlockPos cell, Vec3 point, Node parent, double traveled, double remaining)
            implements Comparable<Node> {
        @Override public int compareTo(Node other) {
            return Double.compare(traveled + remaining * 1.15, other.traveled + other.remaining * 1.15);
        }
    }

    private final ClientLevel world;
    private final Entity mover;
    private final AABB hull;
    private final int grid;
    private final boolean water;
    private final Vec3 origin, destination, goal;
    private final PriorityQueue<Node> open = new PriorityQueue<>();
    private final Map<BlockPos, Double> costs = new HashMap<>();
    private Node frontier;
    private int expanded;

    FlyingRoute(ClientLevel world, Entity mover, Vec3 origin, Vec3 destination) {
        this(world, mover, origin, destination, false);
    }

    FlyingRoute(ClientLevel world, Entity mover, Vec3 origin, Vec3 destination, boolean water) {
        this.world = world;
        this.mover = mover;
        this.water = water;
        this.grid = water ? 1 : 3;
        this.origin = origin;
        this.destination = destination;
        hull = mover.getBoundingBox().deflate(0.002).inflate(0.10, 0, 0.10)
                .move(-mover.getX(), -mover.getY(), -mover.getZ());
        double distance = origin.distanceTo(destination);
        goal = origin.lerp(destination, Math.min(1, LEG / Math.max(0.001, distance)));
        var first = new Node(BlockPos.ZERO, origin, null, 0, origin.distanceTo(goal));
        open.add(first);
        costs.put(first.cell, first.traveled);
    }

    Plan advance() {
        long deadline = System.nanoTime() + 2_000_000L;
        for (int work = 0; !open.isEmpty() && expanded < MAX_NODES; work++) {
            if (work > 0 && (work >= 16 || System.nanoTime() >= deadline)) return null;
            Node here = open.remove();
            if (here.traveled > costs.getOrDefault(here.cell, Double.POSITIVE_INFINITY)) continue;
            if ((here.parent == null || here.remaining <= SWEEP) && clear(here.point, goal)) {
                return path(here, goal, goal.distanceToSqr(destination) < 1e-6);
            }
            if (here.parent != null && here.point.distanceTo(destination) + 2 < origin.distanceTo(destination)
                    && (frontier == null || here.point.distanceTo(destination) < frontier.point.distanceTo(destination)))
                frontier = here;
            expanded++;
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos cell = here.cell.offset(dx, dy, dz);
                Vec3 point = origin.add(cell.getX() * grid, cell.getY() * grid, cell.getZ() * grid);
                if (point.distanceToSqr(origin) > (LEG + grid) * (LEG + grid)) continue;
                double traveled = here.traveled + point.distanceTo(here.point) + Math.abs(dy) * 0.12;
                if (traveled >= costs.getOrDefault(cell, Double.POSITIVE_INFINITY) || !clear(here.point, point)) continue;
                costs.put(cell, traveled);
                open.add(new Node(cell, point, here, traveled, point.distanceTo(goal)));
            }
        }
        return frontier == null ? new Plan(List.of(), false) : path(frontier, null, false);
    }

    private Plan path(Node end, Vec3 finalPoint, boolean arrives) {
        var points = new ArrayList<Vec3>();
        if (finalPoint != null) points.add(finalPoint);
        for (Node node = end; node.parent != null; node = node.parent)
            if (!node.point.equals(finalPoint)) points.add(node.point);
        Collections.reverse(points);
        var simplified = new ArrayList<Vec3>();
        Vec3 anchor = origin;
        for (int i = 0; i < points.size();) {
            int far = i;
            for (int j = i + 1; j < points.size(); j++) {
                if (anchor.distanceToSqr(points.get(j)) <= SWEEP * SWEEP && clear(anchor, points.get(j))) far = j;
            }
            anchor = points.get(far);
            simplified.add(anchor);
            i = far + 1;
        }
        return new Plan(List.copyOf(simplified), arrives);
    }

    boolean clear(Vec3 from, Vec3 to) {
        if (!NavigationCollision.clear(world, mover, hull, from, to)) return false;
        if (!water) return true;
        // Check the same continuous corridor for the medium as well as for obstacles.
        int checks = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 3));
        for (int i = 0; i <= checks; i++) {
            var sample = from.lerp(to, i / (double) checks);
            if (!SwimmingNavigation.waterAt(world, sample.add(0, 0.02, 0))) return false;
        }
        return true;
    }
}
