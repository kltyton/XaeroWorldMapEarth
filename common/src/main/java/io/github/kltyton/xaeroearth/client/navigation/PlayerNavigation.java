package io.github.kltyton.xaeroearth.client.navigation;

import io.github.kltyton.xaeroearth.client.render.navigation.TerrainScan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Steers the normal client movement controls, including a mount under the player's control. */
public final class PlayerNavigation {
    private static final double ARRIVAL_RADIUS = 0.40;
    private static final double PLANNING_HORIZON = 224;
    private static ClientLevel level;
    private static LocalPlayer player;
    private static Entity mover;
    private static BlockPos goal;
    private static List<Vec3> route = List.of();
    private static WalkingRoute search, continuation;
    private static FlightNavigation flight;
    private static SwimmingNavigation swim;
    private static int swimRetry, swimEnterWait;
    private static final List<WalkingRoute.Edge> blockedSteps = new ArrayList<>(4);
    private static Vec3 destination, previousStep;
    private static Vec3 detourOrigin;
    private static boolean endsAtDestination, submergedDestination;
    private static int next, ticksWithoutProgress, replans;
    private static int initialSearchDelay, initialChunkWait;
    private static int continuationDelay, continuationFailures, frontierWait;
    private static double bestDistance;
    private static float yawSpeed, priorYaw, nextYaw, priorPitch, nextPitch;
    private static boolean automatic, sprintRequested, sprintOwned, forward, jump, descend, airborne;
    private static boolean strafeLeft, strafeRight;
    private static int rocketCooldown, hudCooldown;
    private static Vec3 previousAirPosition;
    private static double closestAirDistance;
    private static int mountJumpCooldown;
    public static final int FORWARD_KEY = 0, LEFT_KEY = 1, BACK_KEY = 2, RIGHT_KEY = 3;
    private static final boolean[] heldDirections = new boolean[4];
    private static final long[] lastPress = new long[4];
    private static int heldTicks;
    private static String lastEndReason = "";
    private static String lastControlReleaseReason = "NONE";
    private static int continuationStitches;
    private static boolean waitingForChunks;

    public record Diagnostics(String endReason, String lastReleaseReason, double remainingConfirmed,
                              boolean continuationActive, int continuationStitches, boolean waitingForChunks,
                              int routeSteps, int nextStep, boolean endsAtDestination) { }

    public static String lastEndReason() { return lastEndReason; }

    public static Diagnostics diagnostics() {
        double ahead = mover == null || route.isEmpty() ? 0.0 : remainingConfirmedDistance(mover.position());
        return new Diagnostics(lastEndReason, lastControlReleaseReason, ahead, continuation != null,
                continuationStitches, waitingForChunks, route.size(), next, endsAtDestination);
    }

    public static boolean start(BlockPos target) {
        return start(target, true);
    }

    public static boolean start(BlockPos target, boolean autopilot) {
        Minecraft mc = Minecraft.getInstance();
        boolean sprinting = mc.player != null && (mc.player.isSprinting() || mc.options.keySprint.isDown());
        cancel();
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()
                || !NavigationPlatform.withinBorder(mc.level, new AABB(target))) return false;
        Entity vehicle = mc.player.getVehicle();
        if (vehicle != null && vehicle.getControllingPassenger() != mc.player) {
            if (autopilot) return false;
            vehicle = null;
        }
        level = mc.level;
        player = mc.player;
        mover = vehicle == null ? player : vehicle;
        automatic = autopilot;
        sprintRequested = sprinting;
        goal = target.immutable();
        lastEndReason = "ACTIVE";
        destination = Vec3.atBottomCenterOf(goal);
        priorYaw = nextYaw = player.getYRot();
        priorPitch = nextPitch = player.getXRot();
        NavigationTrace.start(level, mover, destination);
        TerrainScan.start(level, player.position(), player.getYRot());
        return true;
    }

    public static void tick(boolean screenOpen) {
        if (!screenOpen) TerrainScan.tick(Minecraft.getInstance().level);
        if (goal == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player != player || !player.isAlive()
                || player.isSpectator() || !mover.isAlive()
                || (mover == player ? automatic && player.isPassenger()
                : player.getVehicle() != mover || mover.getControllingPassenger() != player)) {
            cancel();
            return;
        }
        if (screenOpen && automatic) {
            heldTicks = 0;
            Arrays.fill(heldDirections, false);
            Arrays.fill(lastPress, 0);
            releaseControls(mc, "SCREEN_OPEN");
            priorYaw = nextYaw = player.getYRot();
            priorPitch = nextPitch = player.getXRot();
            yawSpeed = 0;
            return;
        }
        if (automatic) {
            int pressed = 0;
            for (int i = 0; i < heldDirections.length; i++) if (heldDirections[i]) pressed |= 1 << i;
            heldTicks = pressed == 0 ? 0 : heldTicks + 1;
            if (heldTicks >= 12) {
                cancel();
                // The original press was consumed while navigation owned movement.
                if ((pressed & (1 << FORWARD_KEY)) != 0) mc.options.keyUp.setDown(true);
                if ((pressed & (1 << LEFT_KEY)) != 0) mc.options.keyLeft.setDown(true);
                if ((pressed & (1 << BACK_KEY)) != 0) mc.options.keyDown.setDown(true);
                if ((pressed & (1 << RIGHT_KEY)) != 0) mc.options.keyRight.setDown(true);
                return;
            }
        }
        priorYaw = nextYaw = player.getYRot();
        priorPitch = nextPitch = player.getXRot();
        Vec3 position = mover.position();
        if (detourOrigin != null && position.distanceToSqr(detourOrigin) >= 16) {
            detourOrigin = null;
            replans = 0;
            blockedSteps.clear();
        }
        if (hudCooldown-- <= 0 && !screenOpen) {
            NavigationPlatform.distance(mc, automatic, NavigationTrace.remainingBlocks(position));
            hudCooldown = 9;
        }
        if (mover == player && (player.isFallFlying() || player.getAbilities().flying)) {
            if (!airborne) {
                swim = null;
                resetPlan(mc);
                updateDestination(Vec3.atBottomCenterOf(goal));
                airborne = true;
                previousAirPosition = position;
                closestAirDistance = position.distanceTo(destination);
                flight = new FlightNavigation(position);
            }
            if (FlightNavigation.reached(previousAirPosition, position, destination)) {
                finish();
                return;
            }
            previousAirPosition = position;
            double remaining = position.distanceTo(destination);
            if (remaining < closestAirDistance - 0.2) {
                closestAirDistance = remaining;
                ticksWithoutProgress = 0;
            } else if (automatic && ++ticksWithoutProgress > 100) {
                fail();
                return;
            }
            rocketCooldown = flight.tick(mc, level, player, destination, automatic, rocketCooldown);
            return;
        }
        boolean swimming = mover.isInWater();
        boolean grounded = NavigationPlatform.onGround(mover) || swimming;
        if (airborne) {
            flight = null;
            resetPlan(mc);
            airborne = false;
        }
        boolean submergedTarget = mover == player && SwimmingNavigation.submerged(level, goal);
        if (submergedTarget != submergedDestination) {
            submergedDestination = submergedTarget;
            swim = null;
            swimRetry = swimEnterWait = 0;
            resetPlan(mc);
        }
        if (swimRetry > 0) swimRetry--;
        if (swimming && submergedTarget && (swim != null || swimRetry == 0
                && SwimmingNavigation.waterAt(level, position.add(0, 0.02, 0)))) {
            if (swim == null) {
                resetPlan(mc);
                swim = new SwimmingNavigation();
                updateDestination(Vec3.atBottomCenterOf(goal));
            }
            swimEnterWait = 0;
            var result = swim.tick(level, mover, destination, automatic);
            switch (result.state()) {
                case ARRIVED -> { finish(); return; }
                case BLOCKED -> {
                    swim = null;
                    swimRetry = 80;
                    resetPlan(mc);
                }
                case PLANNING -> {
                    if (automatic) fly(mc, false, false, false);
                    return;
                }
                case MOVING -> {
                    if (automatic) steerSwim(mc, position, result.step());
                    return;
                }
            }
        } else if (swim != null) {
            swim = null;
            resetPlan(mc);
        }
        if (automatic) steerPitch(0);
        if (search == null) {
            if (!grounded) return;
            if (initialSearchDelay > 0) {
                initialSearchDelay--;
                return;
            }
            if (automatic && mover == player && player.isEyeInFluid(FluidTags.WATER)) {
                jump = true;
                mc.options.keyJump.setDown(true);
                return;
            }
            AABB footprint = mover.getBoundingBox().minmax(player.getBoundingBox()).deflate(0.002)
                    .move(-position.x, -position.y, -position.z);
            Vec3 walkGoal = submergedTarget ? SwimmingNavigation.surface(level, goal) : Vec3.atBottomCenterOf(goal);
            search = new WalkingRoute(level, mover, footprint, position, walkGoal, blockedSteps);
            updateDestination(submergedTarget ? Vec3.atBottomCenterOf(goal) : search.destination());
            releaseControls(mc, waitingForChunks ? "INITIAL_UNLOADED_CHUNK" : "INITIAL_SEARCH");
        }
        if (route.isEmpty()) {
            var plan = search.advance(false, false);
            if (plan == null) return;
            route = plan.steps();
            if (route.isEmpty()) {
                if (plan.arrives()) {
                    if (submergedTarget) enterWater(mc, swimming);
                    else finish();
                } else if (plan.waitingForChunks()) {
                    waitingForChunks = true;
                    search = null;
                    initialSearchDelay = 2;
                    releaseControls(mc, "INITIAL_UNLOADED_CHUNK");
                    if (++initialChunkWait > 100) fail();
                } else {
                    fail();
                }
                return;
            }
            waitingForChunks = false;
            initialChunkWait = 0;
            endsAtDestination = plan.arrives();
            if (endsAtDestination && !submergedTarget) updateDestination(route.get(route.size() - 1));
            previousStep = position;
            next = 0;
            bestDistance = Double.POSITIVE_INFINITY;
        }
        // Keep look-ahead continuation advancing every tick, even while airborne over grass steps,
        // so the next multi-chunk segment is stitched before the mover approaches the current frontier.
        extendRoute();
        if (!grounded) {
            // Keep the lead trace continuously anchored to the mover's horizontal path during step jumps.
            Vec3 traceStart = new Vec3(position.x,
                    Mth.clamp(position.y, Math.min(previousStep.y, route.get(next).y), Math.max(previousStep.y, route.get(next).y)),
                    position.z);
            NavigationTrace.route(traceStart, previousStep, route, next);
            if (automatic) {
                if (strafeLeft) mc.options.keyLeft.setDown(false);
                if (strafeRight) mc.options.keyRight.setDown(false);
                strafeLeft = strafeRight = false;
                Vec3 landing = route.get(next);
                boolean exitingWater = !mover.isInWater() && SwimmingNavigation.waterAt(level, position.add(0, -0.35, 0));
                boolean stopOnLanding = (next + 1 == route.size() && endsAtDestination)
                        || (next + 1 < route.size() && sharpTurn(previousStep, landing, route.get(next + 1)));
                if (stopOnLanding && position.y >= landing.y - 0.05 && passedWaypoint(position, landing)) {
                    yawSpeed = 0;
                    jump = false;
                    mc.options.keyJump.setDown(false);
                    forward = false;
                    mc.options.keyUp.setDown(false);
                    if (sprintOwned) NavigationPlatform.routeSprint(mc, false);
                    sprintOwned = false;
                } else if (exitingWater || (!forward && landing.y > position.y - 0.35
                        && WalkingRoute.horizontal(position, landing) > 0.25)) {
                    // When vaulting out of water onto a shore, feet leave the water just before
                    // reaching the ledge. Keep steering and pushing forward onto the bank instead
                    // of bobbing vertically in place.
                    float delta = WalkingRoute.horizontal(position, landing) > 0.05
                            ? steer(heading(position, landing)) : 0;
                    forward = Math.abs(delta) < 40.0F;
                    mc.options.keyUp.setDown(forward);
                    boolean boostVault = exitingWater && landing.y > position.y + 0.05 && mover.horizontalCollision
                            && !(mover == player && player.getAbilities().mayfly);
                    jump = boostVault;
                    mc.options.keyJump.setDown(boostVault);
                } else {
                    // Keep the committed takeoff heading and geometry until the mover has landed.
                    // Steering at a waypoint beneath the mover can otherwise reverse the camera mid-jump.
                    yawSpeed = 0;
                    jump = false;
                    mc.options.keyJump.setDown(false);
                }
            }
            return;
        }
        search.refreshFooting();
        while (next + 1 < route.size() && canAdvance(position)) advanceStep(position, next + 1);
        // Only cut across a corner when there is ground and room for the entire mover.
        for (int candidate = Math.min(next + 4, route.size() - 1); candidate > next; candidate--) {
            if (WalkingRoute.horizontal(position, route.get(candidate)) > 3.6
                    || !search.flat(position, route.get(candidate))) continue;
            advanceStep(position, candidate);
            break;
        }
        Vec3 step = route.get(next);
        boolean last = next + 1 == route.size();
        boolean crossed = passedWaypoint(position, step);
        boolean nearLeg = !offSegment(position, step, 0.70);
        boolean atHeight = Math.abs(position.y - step.y) < heightTolerance();
        if (last && (reached(step, endsAtDestination ? 0.65 : ARRIVAL_RADIUS)
                || crossed && atHeight && nearLeg)) {
            if (endsAtDestination && submergedTarget) enterWater(mc, swimming);
            else if (endsAtDestination) finish();
            else {
                releaseControls(mc, waitingForChunks ? "FRONTIER_UNLOADED_CHUNK" : "FRONTIER_WAITING_CONTINUATION");
                if (!waitingForChunks && ++frontierWait > 100 && continuation == null && continuationFailures >= 2) fail();
            }
            return;
        }
        // An obsolete waypoint cannot guide us backwards. Rejoin the following leg only
        // through verified terrain, or plan a new approach from the actual feet.
        if (!search.standing(step) || crossed && (atHeight || !nearLeg || passedBy(position, step, 0.25))) {
            boolean rejoined = false;
            for (int candidate = next + 1; candidate < route.size() && candidate <= next + 8; candidate++) {
                Vec3 point = route.get(candidate);
                if (WalkingRoute.horizontal(position, point) > 6 || !search.traversable(position, point)) continue;
                advanceStep(position, candidate);
                rejoined = true;
                break;
            }
            if (!rejoined) {
                requestDetour(mc, step);
                return;
            }
            step = route.get(next);
            last = next + 1 == route.size();
            crossed = passedWaypoint(position, step);
            nearLeg = !offSegment(position, step, 0.70);
            atHeight = Math.abs(position.y - step.y) < heightTolerance();
        }
        if (!automatic) {
            if (offSegment(position, step, 1.25) || !search.traversable(position, step)) resetPlan(mc);
            else NavigationTrace.route(position, previousStep, route, next);
            return;
        }
        double x = step.x - position.x;
        double z = step.z - position.z;
        double distance = x * x + z * z;
        double height = step.y - position.y;
        if (offSegment(position, step, 0.70)
                && !search.traversable(position, step)) {
            requestDetour(mc, step);
            return;
        }
        // A streamed corridor can change after planning. Check the part the mover is about
        // to enter so a new obstruction does not wait for the lack-of-progress timeout.
        if (Math.abs(height) <= 0.002 && distance > 0.25
                && !search.flat(position, position.lerp(step, Math.min(1, 1.25 / Math.sqrt(distance))))) {
            requestDetour(mc, step);
            return;
        }
        NavigationTrace.route(position, previousStep, route, next);
        double remaining = distance + height * height;
        if (remaining < bestDistance - 0.005) {
            bestDistance = remaining;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > 38) {
            requestDetour(mc, step);
            return;
        }
        Vec3 corner = lookAhead(position, step);
        Vec3 aim = corner == step ? forwardAim(position, step) : corner;
        float delta = WalkingRoute.horizontal(position, aim) > 0.01 ? steer(heading(position, aim)) : 0;
        double remainingInDirection = (x * mover.getDeltaMovement().x + z * mover.getDeltaMovement().z)
                / Math.max(0.001, Math.sqrt(distance));
        boolean coast = last && endsAtDestination && Math.abs(height) < 0.25 && remainingInDirection > 0
                && Math.sqrt(distance) < 0.4 + remainingInDirection;
        double speed = Math.sqrt(mover.getDeltaMovement().horizontalDistanceSqr());
        boolean braking = !last && corner == step && atHeight && speed > 0.10
                && WalkingRoute.horizontal(position, step) < 0.32 + 3 * speed
                && sharpTurn(previousStep, step, route.get(next + 1));
        forward = Math.abs(delta) < 22.0F && !coast && !braking;
        strafeLeft = strafeRight = false;
        if (!swimming && !coast && atHeight && Math.abs(delta) >= 22 && Math.abs(delta) < 112.5F) {
            boolean moveForward = Math.abs(delta) < 67.5F;
            boolean moveLeft = delta < 0;
            double yaw = Math.toRadians(player.getYRot());
            double dx = (moveForward ? -Math.sin(yaw) : 0) + (moveLeft ? Math.cos(yaw) : -Math.cos(yaw));
            double dz = (moveForward ? Math.cos(yaw) : 0) + (moveLeft ? Math.sin(yaw) : -Math.sin(yaw));
            double length = Math.hypot(dx, dz);
            Vec3 probe = position.add(dx * 0.45 / length, 0, dz * 0.45 / length);
            if (search.flat(position, probe)) {
                forward = moveForward;
                strafeLeft = moveLeft;
                strafeRight = !moveLeft;
            }
        }
        boolean climbAhead = mover.horizontalCollision && search.climbAhead(position, step);
        jump = swimming || forward && Math.abs(delta) < 16.0F && (nearLeg || climbAhead) && !crossed
                && jumpForStep(position, step, height, climbAhead);
        mc.options.keyUp.setDown(forward);
        mc.options.keyLeft.setDown(strafeLeft);
        mc.options.keyRight.setDown(strafeRight);
        boolean sprint = sprintRequested && mover == player && forward
                && (player.getAbilities().mayfly || player.getFoodData().getFoodLevel() > 6)
                && !player.isUsingItem() && !player.isShiftKeyDown();
        if (sprint || sprintOwned) NavigationPlatform.routeSprint(mc, sprint);
        sprintOwned = sprint;
        // A pair of synthetic jump presses can toggle Creative flight after a failed takeoff.
        // Use the player's normal grounded jump impulse without opening the flight-toggle window.
        boolean creativeGroundJump = jump && !swimming && mover == player && player.getAbilities().mayfly;
        mc.options.keyJump.setDown(jump && !creativeGroundJump);
        if (creativeGroundJump) player.jumpFromGround();
    }

    private static double remainingConfirmedDistance(Vec3 position) {
        double ahead = 0;
        Vec3 from = position;
        for (int i = next; i < route.size(); i++) {
            Vec3 to = route.get(i);
            ahead += from.distanceTo(to);
            from = to;
        }
        return ahead;
    }

    private static void extendRoute() {
        if (endsAtDestination || route.isEmpty()) return;
        Vec3 end = route.get(route.size() - 1);
        double ahead = remainingConfirmedDistance(mover.position());
        if (continuation == null) {
            if (ahead > PLANNING_HORIZON || continuationDelay-- > 0) return;
            continuation = search.continueFrom(end, blockedSteps);
        }
        boolean urgent = ahead < 14.0;
        var plan = continuation.advance(true, urgent);
        if (plan == null) return;
        if (!submergedDestination && (!plan.steps().isEmpty() || plan.arrives())) {
            var approach = plan.steps();
            updateDestination(plan.arrives() && !approach.isEmpty()
                    ? approach.get(approach.size() - 1) : continuation.destination());
        }
        continuation = null;
        if (plan.steps().isEmpty()) {
            endsAtDestination = plan.arrives();
            waitingForChunks = plan.waitingForChunks();
            if (waitingForChunks) {
                continuationDelay = 2;
            } else {
                continuationDelay = 4;
                continuationFailures++;
            }
            return;
        }
        waitingForChunks = false;
        var incoming = plan.steps();
        int startIndex = !incoming.isEmpty() && incoming.get(0).distanceToSqr(end) < 1e-4 ? 1 : 0;
        var joined = new ArrayList<Vec3>(route.size() - next + incoming.size() - startIndex);
        joined.addAll(route.subList(next, route.size()));
        for (int i = startIndex; i < incoming.size(); i++) joined.add(incoming.get(i));
        route = List.copyOf(joined);
        next = 0;
        frontierWait = 0;
        continuationFailures = 0;
        continuationStitches++;
        endsAtDestination = plan.arrives();
    }

    private static void updateDestination(Vec3 point) {
        if (point.equals(destination)) return;
        destination = point;
        NavigationTrace.updateDestination(point);
        hudCooldown = 0;
    }

    /** Aim at the tangent of the current leg, with bounded lateral correction at short waypoints. */
    private static Vec3 forwardAim(Vec3 position, Vec3 step) {
        double dx = step.x - previousStep.x, dz = step.z - previousStep.z;
        double length = Math.hypot(dx, dz);
        if (length < 0.01) return step;
        dx /= length;
        dz /= length;
        double along = (position.x - previousStep.x) * dx + (position.z - previousStep.z) * dz;
        double look = Math.max(0, along) + 1.25;
        return previousStep.add(dx * look, 0, dz * look);
    }

    private static Vec3 lookAhead(Vec3 position, Vec3 step) {
        if (next + 1 >= route.size() || WalkingRoute.horizontal(position, step) > 1.2) return step;
        Vec3 following = route.get(next + 1);
        double length = WalkingRoute.horizontal(step, following);
        if (length <= 0.01) return step;
        Vec3 look = step.lerp(following, Math.min(1, 0.65 / length));
        return search.flat(position, look) ? look : step;
    }

    private static boolean canAdvance(Vec3 position) {
        Vec3 step = route.get(next);
        Vec3 following = route.get(next + 1);
        if (Math.abs(position.y - step.y) >= heightTolerance()) return false;
        double distance = WalkingRoute.horizontal(step, following);
        if (distance > 1.4 && Math.abs(following.y - step.y) <= 0.002)
            following = step.lerp(following, 1.4 / distance);
        return (reached(step, ARRIVAL_RADIUS) || passedWaypoint(position, step) && !offSegment(position, step, 0.70))
                && search.traversable(position, following);
    }

    private static boolean sharpTurn(Vec3 from, Vec3 corner, Vec3 to) {
        double incomingX = corner.x - from.x, incomingZ = corner.z - from.z;
        double outgoingX = to.x - corner.x, outgoingZ = to.z - corner.z;
        double scale = Math.hypot(incomingX, incomingZ) * Math.hypot(outgoingX, outgoingZ);
        return scale > 0.001 && incomingX * outgoingX + incomingZ * outgoingZ < 0.65 * scale;
    }

    private static boolean passedWaypoint(Vec3 position, Vec3 step) {
        return passedBy(position, step, 0);
    }

    private static boolean passedBy(Vec3 position, Vec3 step, double margin) {
        Vec3 direction = step.subtract(previousStep);
        return (position.x - step.x) * direction.x + (position.z - step.z) * direction.z
                > margin * Math.hypot(direction.x, direction.z);
    }

    private static boolean offSegment(Vec3 position, Vec3 end, double tolerance) {
        Vec3 start = previousStep;
        double dx = end.x - start.x, dz = end.z - start.z;
        double length = dx * dx + dz * dz;
        if (length < 0.0001) return false;
        double t = Mth.clamp(((position.x - start.x) * dx + (position.z - start.z) * dz) / length, 0, 1);
        return Math.hypot(position.x - (start.x + dx * t), position.z - (start.z + dz * t)) > tolerance;
    }

    static float heading(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(from.x - to.x, to.z - from.z));
    }

    static void fly(Minecraft minecraft, boolean advance, boolean ascend, boolean lower) {
        forward = advance;
        jump = ascend;
        descend = lower;
        minecraft.options.keyUp.setDown(advance);
        minecraft.options.keyJump.setDown(ascend);
        minecraft.options.keyShift.setDown(lower);
    }

    private static void steerSwim(Minecraft minecraft, Vec3 from, Vec3 to) {
        double horizontal = WalkingRoute.horizontal(from, to);
        double height = to.y - from.y;
        float delta = horizontal > 0.08 ? steer(heading(from, to)) : 0;
        steerPitch((float) Mth.clamp(-Math.toDegrees(Math.atan2(height, Math.max(0.08, horizontal))), -80, 80));
        double vertical = height - mover.getDeltaMovement().y * 1.5;
        fly(minecraft, horizontal > 0.20 && Math.abs(delta) < 22, vertical > 0.20, vertical < -0.20);
        boolean sprint = forward && sprintRequested && !descend
                && (player.getAbilities().mayfly || player.getFoodData().getFoodLevel() > 6);
        if (sprint || sprintOwned) NavigationPlatform.routeSprint(minecraft, sprint);
        sprintOwned = sprint;
    }

    private static void enterWater(Minecraft minecraft, boolean swimming) {
        if (!automatic) return;
        if (!swimming || swimEnterWait++ >= 60) {
            fail();
            return;
        }
        if (swimRetry == 0) fly(minecraft, false, false, true);
        else releaseControls(minecraft);
    }

    static float steer(float targetYaw) {
        float current = player.getYRot();
        float delta = Mth.wrapDegrees(targetYaw - current);
        float desiredSpeed = Math.copySign(Math.min(20.0F, (float) Math.sqrt(8.0F * Math.abs(delta))), delta);
        yawSpeed += Mth.clamp(desiredSpeed - yawSpeed, -3.8F, 3.8F);
        if (Math.abs(delta) < 0.25F || Math.signum(delta) == Math.signum(yawSpeed)
                && Math.abs(yawSpeed) > Math.abs(delta)) yawSpeed = delta;
        float yaw = current + yawSpeed;
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        nextYaw = yaw;
        return delta - yawSpeed;
    }

    static void steerPitch(float targetPitch) {
        float pitch = player.getXRot();
        nextPitch = pitch + Mth.clamp(targetPitch - pitch, -8, 8);
        player.setXRot(nextPitch);
    }

    public static float cameraYaw(Entity entity, float partialTick, float vanillaYaw) {
        return active() && entity == player
                ? Mth.lerp(Mth.clamp(partialTick, 0, 1), priorYaw, nextYaw)
                : vanillaYaw;
    }

    public static float cameraPitch(Entity entity, float partialTick, float vanillaPitch) {
        return active() && entity == player
                ? Mth.lerp(Mth.clamp(partialTick, 0, 1), priorPitch, nextPitch)
                : vanillaPitch;
    }

    private static void advanceStep(Vec3 position, int index) {
        previousStep = position;
        next = index;
        frontierWait = 0;
        bestDistance = Double.POSITIVE_INFINITY;
        ticksWithoutProgress = 0;
    }

    private static void requestDetour(Minecraft mc, Vec3 step) {
        if (++replans > 8) { fail(); return; }
        Vec3 feet = mover.position();
        if (detourOrigin == null) detourOrigin = feet;
        double distance = feet.distanceTo(step);
        Vec3 blocked = distance <= 1.25 ? step : feet.lerp(step, 1.25 / distance);
        blockedSteps.add(new WalkingRoute.Edge(feet, blocked));
        // Keep the existing trace visible during brief detour replanning so the blue line does not blink blank.
        resetPlan(mc, false, "DETOUR_REPLAN");
    }

    private static void resetPlan(Minecraft mc) {
        resetPlan(mc, true, "RESET_PLAN");
    }

    private static void resetPlan(Minecraft mc, boolean clearTrace, String reason) {
        releaseControls(mc, reason);
        search = continuation = null;
        route = List.of();
        next = ticksWithoutProgress = 0;
        initialSearchDelay = initialChunkWait = 0;
        continuationDelay = continuationFailures = frontierWait = 0;
        waitingForChunks = false;
        if (clearTrace) NavigationTrace.clearRoute();
    }

    private static void fail() {
        lastEndReason = "UNREACHABLE";
        finishWithReason("UNREACHABLE");
        playerMessage("xaeroearth.navigation.unreachable");
    }

    private static boolean jumpForStep(Vec3 position, Vec3 step, double height, boolean climbAhead) {
        boolean needsJump = height > NavigationPlatform.stepHeight(mover) + 0.002;
        double dx = step.x - position.x, dz = step.z - position.z;
        double distance = Math.hypot(dx, dz);
        var bounds = mover.getBoundingBox();
        // The block's corner and the mover's box can meet before a fixed distance
        // to the block center, especially on diagonals and with a wider mount.
        double reachX = 0.5 + (bounds.maxX - bounds.minX) * 0.5;
        double reachZ = 0.5 + (bounds.maxZ - bounds.minZ) * 0.5;
        double contact = (Math.abs(dx) * reachX + Math.abs(dz) * reachZ) / Math.max(0.001, distance);
        double takeoffDistance = Math.max(1.06, contact + (mover.horizontalCollision ? 0.28 : 0.16));
        boolean atTakeoff = climbAhead || distance <= takeoffDistance;
        if (!(mover instanceof PlayerRideableJumping mount) || !mount.canJump())
            return needsJump && NavigationPlatform.onGround(mover) && atTakeoff;
        if (mountJumpCooldown > 0) {
            mountJumpCooldown--;
            return false;
        }
        // The vanilla riding controller charges while held and fires on release.
        if (jump && player.getJumpRidingScale() >= 0.8F) {
            mountJumpCooldown = 12;
            return false;
        }
        return needsJump && (jump || atTakeoff);
    }

    private static boolean reached(Vec3 pos, double radius) {
        double x = pos.x - mover.getX();
        double z = pos.z - mover.getZ();
        return x * x + z * z < radius * radius && Math.abs(mover.getY() - pos.y) < heightTolerance();
    }

    private static double heightTolerance() { return mover.isInWater() ? 1.0 : 0.35; }

    public static void cancel() {
        TerrainScan.stop();
        finishWithReason("CANCELLED");
    }

    private static void finish() {
        finishWithReason("ARRIVED");
    }

    private static void finishWithReason(String reason) {
        if (goal == null) return;
        lastEndReason = reason;
        Minecraft minecraft = Minecraft.getInstance();
        releaseControls(minecraft);
        NavigationPlatform.clearDistance(minecraft);
        level = null; player = null; mover = null; goal = null;
        route = List.of();
        search = continuation = null; flight = null; swim = null; swimRetry = swimEnterWait = 0;
        blockedSteps.clear(); detourOrigin = previousStep = destination = null;
        NavigationTrace.stop();
        next = 0; ticksWithoutProgress = 0; mountJumpCooldown = 0; replans = 0;
        initialSearchDelay = initialChunkWait = 0;
        continuationDelay = continuationFailures = frontierWait = 0;
        continuationStitches = 0;
        waitingForChunks = false;
        lastControlReleaseReason = reason;
        yawSpeed = 0;
        automatic = sprintRequested = airborne = submergedDestination = false;
        previousAirPosition = null;
        rocketCooldown = hudCooldown = 0;
        heldTicks = 0;
        Arrays.fill(heldDirections, false);
        Arrays.fill(lastPress, 0);
    }

    public static boolean active() { return goal != null && automatic; }

    public static boolean requestSprint() {
        if (!active()) return false;
        sprintRequested = true;
        return true;
    }

    public static boolean cancelWithEscape() {
        if (goal == null) return false;
        cancel();
        return true;
    }

    /** Consume directional input during automatic movement and hand over on a double press. */
    public static boolean manualMovement(int direction, int action) {
        if (!active()) return false;
        if (action == GLFW.GLFW_PRESS) {
            long now = System.nanoTime();
            if (lastPress[direction] != 0 && now - lastPress[direction] <= 400_000_000L) {
                cancel();
                return false;
            }
            lastPress[direction] = now;
            heldDirections[direction] = true;
        } else if (action == GLFW.GLFW_RELEASE) {
            heldDirections[direction] = false;
        }
        return true;
    }

    private static void releaseControls(Minecraft minecraft) {
        releaseControls(minecraft, "RELEASE");
    }

    private static void releaseControls(Minecraft minecraft, String reason) {
        if (forward || strafeLeft || strafeRight || jump || descend || sprintOwned) {
            lastControlReleaseReason = reason;
        }
        if (forward) minecraft.options.keyUp.setDown(false);
        if (strafeLeft) minecraft.options.keyLeft.setDown(false);
        if (strafeRight) minecraft.options.keyRight.setDown(false);
        if (jump) minecraft.options.keyJump.setDown(false);
        if (descend) minecraft.options.keyShift.setDown(false);
        if (sprintOwned) NavigationPlatform.routeSprint(minecraft, false);
        forward = jump = descend = strafeLeft = strafeRight = sprintOwned = false;
    }

    public static void playerMessage(String key) {
        NavigationPlatform.message(Minecraft.getInstance(), key);
    }

    private PlayerNavigation() {}
}
