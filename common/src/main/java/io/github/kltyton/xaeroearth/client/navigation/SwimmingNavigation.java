package io.github.kltyton.xaeroearth.client.navigation;

import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Keeps the submerged part of the route in water, using the same collision sweep as flight. */
final class SwimmingNavigation {
    enum State { PLANNING, MOVING, ARRIVED, BLOCKED }
    record Result(State state, Vec3 step) { }

    private FlyingRoute search;
    private List<Vec3> steps = List.of();
    private int next;
    private int stalledTicks;
    private double best = Double.POSITIVE_INFINITY;
    private Vec3 planningOrigin;
    private int ticksWithoutPlan;

    static boolean waterAt(ClientLevel level, Vec3 point) {
        var pos = new BlockPos(Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z));
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        var water = level.getFluidState(pos);
        return water.is(FluidTags.WATER) && point.y - pos.getY() < water.getHeight(level, pos);
    }

    static boolean submerged(ClientLevel level, BlockPos target) {
        var point = Vec3.atBottomCenterOf(target);
        return waterAt(level, point.add(0, 1.05, 0)) && waterAt(level, point.add(0, 0.05, 0));
    }

    static Vec3 surface(ClientLevel level, BlockPos target) {
        BlockPos position = target;
        while (!level.isOutsideBuildHeight(position.above())
                && level.getFluidState(position.above()).is(FluidTags.WATER)) position = position.above();
        Vec3 point = Vec3.atBottomCenterOf(position);
        return point.add(0, level.getFluidState(position).getHeight(level, position) - 0.10, 0);
    }

    Result tick(ClientLevel level, Entity mover, Vec3 goal, boolean automatic) {
        Vec3 position = mover.position();
        if (position.distanceToSqr(goal) < 0.55 * 0.55) return new Result(State.ARRIVED, goal);
        if (next < steps.size() && position.distanceToSqr(steps.get(next)) < 0.55 * 0.55) {
            next++;
            if (next == steps.size()) search = null;
            best = Double.POSITIVE_INFINITY;
            stalledTicks = 0;
        }
        if (next == steps.size()) {
            if (search == null) search = new FlyingRoute(level, mover, position, goal, true);
            var plan = search.advance();
            if (plan == null) return planning(position, automatic);
            if (plan.steps().isEmpty()) return new Result(State.BLOCKED, null);
            steps = plan.steps();
            next = 0;
        }
        Vec3 step = steps.get(next);
        double distance = position.distanceTo(step);
        if (!search.clear(position, position.lerp(step, Math.min(1, 1.2 / Math.max(0.001, distance))))) {
            reset();
            return planning(position, automatic);
        }
        planningOrigin = null;
        ticksWithoutPlan = 0;
        if (distance + 0.03 < best) {
            best = distance;
            stalledTicks = 0;
        } else if (automatic && ++stalledTicks > 36) return new Result(State.BLOCKED, null);
        NavigationTrace.airRoute(position, steps, next);
        return new Result(State.MOVING, step);
    }

    private Result planning(Vec3 position, boolean automatic) {
        if (planningOrigin == null || planningOrigin.distanceToSqr(position) >= 1) {
            planningOrigin = position;
            ticksWithoutPlan = 0;
        }
        return new Result(automatic && ++ticksWithoutPlan > 100 ? State.BLOCKED : State.PLANNING, null);
    }

    private void reset() {
        search = null;
        steps = List.of();
        next = 0;
        stalledTicks = 0;
        best = Double.POSITIVE_INFINITY;
        NavigationTrace.clearRoute();
    }
}
