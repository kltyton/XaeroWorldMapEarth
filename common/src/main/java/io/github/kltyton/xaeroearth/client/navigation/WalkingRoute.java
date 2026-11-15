package io.github.kltyton.xaeroearth.client.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Incremental A* over real collision surfaces in chunks already present on the client. */
final class WalkingRoute {
    private static final int RADIUS = 80;
    private static final int MAX_EXPANSIONS = 10_000;
    private static final float EPSILON = 0.002F;
    private static final double DROP = 1.25;
    private static final double MAX_SHORTCUT = 64;
    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1},{0,0}};

    record Plan(List<Vec3> steps, boolean arrives) {}
    record Edge(Vec3 from, Vec3 to) {}
    private record LandingProbe(double x, double y, double z) {}
    private record Node(Vec3 feet, Node parent, double cost, double estimate) implements Comparable<Node> {
        @Override public int compareTo(Node other) {
            int order = Double.compare(cost + estimate, other.cost + other.estimate);
            return order == 0 ? Double.compare(estimate, other.estimate) : order;
        }
    }

    private final ClientLevel level;
    private final Entity mover;
    private final AABB footprint;
    private final Vec3 start, selectedGoal, goal;
    private final double initialEstimate;
    private final double riseLimit;
    private final List<Edge> blockedSteps;
    private final PriorityQueue<Node> open = new PriorityQueue<>();
    private final Map<Vec3, Double> costs = new HashMap<>();
    private final Map<BlockPos, Boolean> safe = new HashMap<>();
    private final Map<LandingProbe, List<Vec3>> landingCache = new HashMap<>();
    private Node frontier;
    private int expanded;
    private boolean checkedDirect;

    WalkingRoute(ClientLevel level, Entity mover, AABB footprint, Vec3 start, Vec3 goal, List<Edge> blockedSteps) {
        this.level = level;
        this.mover = mover;
        this.footprint = footprint;
        this.blockedSteps = List.copyOf(blockedSteps);
        riseLimit = Math.max(1.25, NavigationPlatform.stepHeight(mover));
        this.start = start;
        selectedGoal = goal;
        this.goal = groundDestination(goal);
        initialEstimate = distance(start, this.goal);
        var first = new Node(start, null, 0, initialEstimate);
        open.add(first);
        costs.put(start, 0.0);
    }

    WalkingRoute continueFrom(Vec3 end, List<Edge> blockedSteps) {
        return new WalkingRoute(level, mover, footprint, end, selectedGoal, blockedSteps);
    }

    Vec3 destination() { return goal; }

    private Vec3 groundDestination(Vec3 selected) {
        BlockPos surface = new BlockPos(Mth.floor(selected.x), Mth.floor(selected.y - EPSILON), Mth.floor(selected.z));
        if (!level.getChunkSource().hasChunk(surface.getX() >> 4, surface.getZ() >> 4)) return selected;
        var hit = level.getBlockState(surface);
        if (!hit.is(BlockTags.LEAVES) && !(hit.is(Blocks.SNOW) && level.getBlockState(surface.below()).is(BlockTags.LEAVES)))
            return selected;
        Vec3 ground = groundBelow(selected);
        if (ground != null) return ground;
        for (int[] direction : DIRECTIONS) {
            if (direction[0] == 0 && direction[1] == 0) continue;
            ground = groundBelow(selected.add(direction[0], 0, direction[1]));
            if (ground != null) return ground;
        }
        return selected;
    }

    private Vec3 groundBelow(Vec3 selected) {
        for (double y = selected.y - 2; y >= selected.y - 24; y--) {
            Vec3 highest = null;
            for (Vec3 feet : landings(selected.x, selected.z, y)) {
                var supportingBlock = new BlockPos(Mth.floor(feet.x), Mth.floor(feet.y - EPSILON), Mth.floor(feet.z));
                var support = level.getBlockState(supportingBlock);
                if (support.is(Blocks.SNOW)) support = level.getBlockState(supportingBlock.below());
                if (support.isAir() || support.is(BlockTags.LEAVES) || support.is(BlockTags.LOGS)
                        || feet.y >= selected.y - EPSILON) continue;
                if (highest == null || feet.y > highest.y) highest = feet;
            }
            if (highest != null) return highest;
        }
        return null;
    }

    /** Returns null while searching. A completed empty plan means no safe progress is known. */
    Plan advance() {
        if (!checkedDirect) {
            checkedDirect = true;
            double length = horizontal(start, goal);
            boolean direct = length <= MAX_SHORTCUT;
            Vec3 target = direct ? goal : start.lerp(goal, MAX_SHORTCUT / length);
            for (Vec3 landing : landings(target.x, target.z, start.y)) {
                if ((!direct || Math.abs(landing.y - goal.y) <= 1.75) && flat(start, landing))
                    return new Plan(List.of(landing), direct);
            }
        }
        long deadline = System.nanoTime() + 2_000_000L;
        int work = 0;
        while (!open.isEmpty() && expanded < MAX_EXPANSIONS && work++ < 160) {
            if (work > 1 && System.nanoTime() >= deadline) return null;
            Node current = open.remove();
            if (current.cost > costs.getOrDefault(current.feet, Double.POSITIVE_INFINITY)) continue;
            Vec3 from = current.feet;
            double distanceToGoal = horizontal(from, goal);
            if (Math.abs(from.y - goal.y) <= 1.75 && distanceToGoal <= 1.5) {
                if (distanceToGoal < 0.35 && standing(from)) return plan(current, true);
                Vec3 bestLanding = null;
                for (Vec3 landing : landings(goal.x, goal.z, from.y)) {
                    double heightError = Math.abs(landing.y - goal.y);
                    if (heightError > 1.75 || (bestLanding != null && heightError >= Math.abs(bestLanding.y - goal.y))
                            || !traversable(from, landing)) continue;
                    bestLanding = landing;
                }
                if (bestLanding != null) return plan(new Node(bestLanding, current, current.cost + distanceToGoal, 0), true);
            }
            if (current.parent != null && current.estimate < initialEstimate - 0.75
                    && (frontier == null || current.estimate < frontier.estimate)) {
                frontier = current;
                if (horizontal(start, goal) > RADIUS && initialEstimate - current.estimate >= RADIUS * 0.8) {
                    return plan(current, false);
                }
            }
            expanded++;
            for (int[] direction : DIRECTIONS) {
                if (current.parent != null && direction[0] == 0 && direction[1] == 0) continue;
                double x = Math.floor(from.x) + 0.5 + direction[0];
                double z = Math.floor(from.z) + 0.5 + direction[1];
                if (Math.hypot(x - start.x, z - start.z) > RADIUS) continue;
                for (Vec3 to : landings(x, z, from.y)) {
                    double rise = to.y - from.y;
                    double step = horizontal(from, to) + Math.abs(rise) * (rise > 0.55 ? 1.6 : 0.65);
                    double cost = current.cost + step;
                    if (cost >= costs.getOrDefault(to, Double.POSITIVE_INFINITY) || !traversable(from, to)) continue;
                    costs.put(to, cost);
                    open.add(new Node(to, current, cost, distance(to, goal)));
                }
            }
        }
        if (!open.isEmpty() && expanded < MAX_EXPANSIONS) return null;
        return frontier == null ? new Plan(List.of(), false) : plan(frontier, false);
    }

    private Plan plan(Node end, boolean arrives) {
        var path = new ArrayDeque<Vec3>();
        for (Node node = end; node.parent != null; node = node.parent) path.addFirst(node.feet);
        if (path.size() < 2) return new Plan(List.copyOf(path), arrives);
        var steps = new ArrayList<>(path);
        var simplified = new ArrayList<Vec3>();
        Vec3 from = start;
        for (int i = 0; i < steps.size();) {
            int last = i;
            while (last + 1 < steps.size()
                    && horizontal(from, steps.get(last + 1)) <= MAX_SHORTCUT
                    && Math.abs(from.y - steps.get(last + 1).y) <= EPSILON) last++;
            while (last > i && !flat(from, steps.get(last))) last--;
            from = steps.get(last);
            simplified.add(from);
            i = last + 1;
        }
        return new Plan(List.copyOf(simplified), arrives);
    }

    private List<Vec3> landings(double x, double z, double y) {
        AABB at = footprint.move(x, y, z);
        AABB probe = new AABB(at.minX, y - DROP - EPSILON, at.minZ, at.maxX, y + riseLimit + EPSILON, at.maxZ);
        if (!loaded(probe)) return List.of();
        return landingCache.computeIfAbsent(new LandingProbe(x, y, z), key -> probeLandings(key, probe));
    }

    private List<Vec3> probeLandings(LandingProbe key, AABB probe) {
        var result = new ArrayList<Vec3>(3);
        for (var shape : level.getBlockCollisions(mover, probe)) {
            for (AABB box : shape.toAabbs()) {
                double top = box.maxY;
                if (top < key.y - DROP || top > key.y + riseLimit || box.maxX <= probe.minX || box.minX >= probe.maxX
                        || box.maxZ <= probe.minZ || box.minZ >= probe.maxZ) continue;
                var feet = new Vec3(key.x, top, key.z);
                if (!result.contains(feet) && standing(feet)) result.add(feet);
            }
        }
        return result;
    }

    boolean standing(Vec3 feet) {
        AABB body = footprint.move(feet);
        return clear(body) && safeFooting(body, feet.y)
                && level.getBlockCollisions(mover, body.move(0, -0.06, 0)).iterator().hasNext();
    }

    boolean traversable(Vec3 from, Vec3 to) {
        double change = to.y - from.y;
        if (change > riseLimit || change < -DROP || !standing(to)) return false;
        if (Math.abs(change) <= EPSILON) return flat(from, to);
        if (repeatsBlockedStep(from, to)) return false;
        double high = Math.max(from.y, to.y);
        var raisedFrom = new Vec3(from.x, high, from.z);
        var raisedTo = new Vec3(to.x, high, to.z);
        return clear(footprint.move(from).minmax(footprint.move(raisedFrom)))
                && clear(footprint.move(to).minmax(footprint.move(raisedTo)))
                && NavigationCollision.clear(level, mover, footprint, raisedFrom, raisedTo);
    }

    boolean flat(Vec3 from, Vec3 to) {
        if (Math.abs(to.y - from.y) > EPSILON || repeatsBlockedStep(from, to)
                || !NavigationCollision.clear(level, mover, footprint, from, to))
            return false;
        return supported(from, to);
    }

    private boolean repeatsBlockedStep(Vec3 from, Vec3 to) {
        Vec3 direction = to.subtract(from);
        double length = direction.lengthSqr();
        if (length < 0.0001) return false;
        for (Edge edge : blockedSteps) {
            if (from.distanceToSqr(edge.from) >= 0.8 * 0.8) continue;
            double fraction = edge.to.subtract(from).dot(direction) / length;
            if (fraction > 0 && fraction <= 1 && from.lerp(to, fraction).distanceToSqr(edge.to) < 0.25 * 0.25) return true;
        }
        return false;
    }

    private boolean supported(Vec3 from, Vec3 to) {
        int samples = Math.max(1, (int) Math.ceil(horizontal(from, to) / 0.24));
        for (int i = 1; i <= samples; i++) {
            Vec3 feet = from.lerp(to, i / (double) samples);
            AABB body = footprint.move(feet);
            if (!safeFooting(body, feet.y) || !level.getBlockCollisions(mover, body.move(0, -0.06, 0)).iterator().hasNext())
                return false;
        }
        return true;
    }

    private boolean safeFooting(AABB box, double feet) {
        int top = (int) Math.floor(feet + EPSILON), bottom = (int) Math.floor(feet - 0.06);
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                for (int y = bottom; y <= top; y++) {
                    var pos = new BlockPos(x, y, z);
                    if (!safe.computeIfAbsent(pos, this::safeBlock)) return false;
                }
            }
        }
        return true;
    }

    private boolean safeBlock(BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)
                && !state.is(Blocks.POWDER_SNOW) && !state.is(Blocks.MAGMA_BLOCK) && !state.is(Blocks.CAMPFIRE)
                && !state.is(Blocks.SOUL_CAMPFIRE) && !state.is(Blocks.CACTUS) && !state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private boolean clear(AABB box) {
        return loaded(box) && !level.getBlockCollisions(mover, box).iterator().hasNext();
    }

    private boolean loaded(AABB box) {
        return NavigationCollision.loaded(level, box);
    }

    void refreshFooting() { safe.clear(); }

    private static double distance(Vec3 from, Vec3 to) {
        return Math.hypot(horizontal(from, to), from.y - to.y);
    }

    static double horizontal(Vec3 from, Vec3 to) {
        return Math.hypot(from.x - to.x, from.z - to.z);
    }
}
