package io.github.kltyton.xaeroearth.client.navigation;

import io.github.kltyton.xaeroearth.client.render.navigation.TerrainScan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
    private static final List<WalkingRoute.Edge> blockedSteps = new ArrayList<>(4);
    private static Vec3 destination, previousStep;
    private static Vec3 detourOrigin;
    private static boolean endsAtDestination;
    private static int next, ticksWithoutProgress, replans;
    private static int continuationDelay, continuationFailures, frontierWait;
    private static double bestDistance;
    private static float yawSpeed, priorYaw, nextYaw, priorPitch, nextPitch;
    private static boolean automatic, sprintRequested, sprintOwned, forward, jump, descend, airborne;
    private static int rocketCooldown, hudCooldown;
    private static Vec3 previousAirPosition;
    private static double closestAirDistance;
    private static int mountJumpCooldown;
    private static final int[] DIRECTION_KEYS = {GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D};
    private static final boolean[] heldDirections = new boolean[DIRECTION_KEYS.length];
    private static final long[] lastPress = new long[DIRECTION_KEYS.length];
    private static int heldTicks;

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
        boolean held = false;
        for (boolean direction : heldDirections) held |= direction;
        heldTicks = held ? heldTicks + 1 : 0;
        if (automatic && heldTicks >= 12) { cancel(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player != player || !player.isAlive()
                || player.isSpectator() || !mover.isAlive()
                || (mover == player ? automatic && player.isPassenger()
                : player.getVehicle() != mover || mover.getControllingPassenger() != player)) {
            cancel();
            return;
        }
        if (screenOpen && automatic) {
            releaseControls(mc);
            priorYaw = nextYaw = player.getYRot();
            priorPitch = nextPitch = player.getXRot();
            yawSpeed = 0;
            return;
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
            NavigationPlatform.distance(mc, automatic, (int) Math.ceil(position.distanceTo(destination)));
            hudCooldown = 9;
        }
        if (mover == player && (player.isFallFlying() || player.getAbilities().flying)) {
            if (!airborne) {
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
        boolean grounded = NavigationPlatform.onGround(mover);
        if (airborne) {
            flight = null;
            resetPlan(mc);
            airborne = false;
        }
        if (automatic) steerPitch(0);
        if (search == null) {
            if (!grounded) return;
            AABB footprint = mover.getBoundingBox().minmax(player.getBoundingBox()).deflate(0.002)
                    .move(-position.x, -position.y, -position.z);
            search = new WalkingRoute(level, mover, footprint, position, Vec3.atBottomCenterOf(goal), blockedSteps);
            updateDestination(search.destination());
            releaseControls(mc);
        }
        if (route.isEmpty()) {
            var plan = search.advance();
            if (plan == null) return;
            route = plan.steps();
            if (route.isEmpty()) {
                if (plan.arrives()) finish(); else fail();
                return;
            }
            endsAtDestination = plan.arrives();
            previousStep = position;
            next = 0;
            bestDistance = Double.POSITIVE_INFINITY;
        }
        if (!grounded) {
            if (automatic) {
                // Keep the committed takeoff heading and geometry until the mover has landed.
                // Steering at a waypoint beneath the mover can otherwise reverse the camera mid-jump.
                yawSpeed = 0;
                jump = false;
                mc.options.keyJump.setDown(false);
                Vec3 landing = route.get(next);
                if (position.y >= landing.y - 0.05 && passedWaypoint(position, landing)) {
                    forward = false;
                    mc.options.keyUp.setDown(false);
                    if (sprintOwned) NavigationPlatform.routeSprint(mc, false);
                    sprintOwned = false;
                }
            }
            return;
        }
        search.refreshFooting();
        extendRoute();
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
        boolean atHeight = Math.abs(position.y - step.y) < 0.35;
        if (last && (reached(step, endsAtDestination ? 0.65 : ARRIVAL_RADIUS)
                || crossed && atHeight && nearLeg)) {
            if (endsAtDestination) finish();
            else {
                releaseControls(mc);
                if (++frontierWait > 100 && continuation == null && continuationFailures >= 2) fail();
            }
            return;
        }
        // An obsolete waypoint cannot guide us backwards. Rejoin the following leg only
        // through verified terrain, or plan a new approach from the actual feet.
        if (!search.standing(step) || crossed && (atHeight || !nearLeg || passedBy(position, step, 0.25))) {
            requestDetour(mc, step);
            return;
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
        jump = forward && Math.abs(delta) < 16.0F && nearLeg && !crossed && jumpForStep(position, step, height);
        mc.options.keyUp.setDown(forward);
        mc.options.keyJump.setDown(jump);
        boolean sprint = sprintRequested && mover == player && forward && Math.abs(delta) < 15 && distance > 2.25 && !jump
                && (player.getAbilities().mayfly || player.getFoodData().getFoodLevel() > 6)
                && !player.isUsingItem() && !player.isShiftKeyDown() && !player.isInWater();
        if (sprint || sprintOwned) NavigationPlatform.routeSprint(mc, sprint);
        sprintOwned = sprint;
    }

    private static void extendRoute() {
        if (endsAtDestination) return;
        Vec3 end = route.get(route.size() - 1);
        if (continuation == null) {
            double ahead = 0;
            Vec3 from = mover.position();
            for (int i = next; i < route.size() && ahead <= PLANNING_HORIZON; i++) {
                Vec3 to = route.get(i);
                ahead += from.distanceTo(to);
                from = to;
            }
            if (ahead > PLANNING_HORIZON || continuationDelay-- > 0) return;
            continuation = search.continueFrom(end, blockedSteps);
        }
        var plan = continuation.advance();
        if (plan == null) return;
        if (!plan.steps().isEmpty() || plan.arrives()) updateDestination(continuation.destination());
        continuation = null;
        if (plan.steps().isEmpty()) {
            endsAtDestination = plan.arrives();
            continuationDelay = 10;
            continuationFailures++;
            return;
        }
        var joined = new ArrayList<Vec3>(route.size() - next + plan.steps().size());
        joined.addAll(route.subList(next, route.size()));
        joined.addAll(plan.steps());
        route = List.copyOf(joined);
        next = 0;
        frontierWait = 0;
        continuationFailures = 0;
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
        if (Math.abs(position.y - step.y) >= 0.35) return false;
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
        if (++replans > 4) { fail(); return; }
        if (detourOrigin == null) detourOrigin = mover.position();
        blockedSteps.add(new WalkingRoute.Edge(previousStep, step));
        resetPlan(mc);
    }

    private static void resetPlan(Minecraft mc) {
        releaseControls(mc);
        search = continuation = null;
        route = List.of();
        next = ticksWithoutProgress = 0;
        continuationDelay = continuationFailures = frontierWait = 0;
        // The trace belongs to the navigation session. Keep it until a replacement is ready.
    }

    private static void fail() {
        cancel();
        playerMessage("xaeroearth.navigation.unreachable");
    }

    private static boolean jumpForStep(Vec3 position, Vec3 step, double height) {
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
        boolean atTakeoff = distance <= takeoffDistance;
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
        return x * x + z * z < radius * radius && Math.abs(mover.getY() - pos.y) < 0.35;
    }

    public static void cancel() {
        TerrainScan.stop();
        finish();
    }

    private static void finish() {
        if (goal == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        releaseControls(minecraft);
        NavigationPlatform.clearDistance(minecraft);
        level = null; player = null; mover = null; goal = null;
        route = List.of();
        search = continuation = null; flight = null; blockedSteps.clear(); detourOrigin = previousStep = destination = null;
        NavigationTrace.stop();
        next = 0; ticksWithoutProgress = 0; mountJumpCooldown = 0; replans = 0;
        continuationDelay = continuationFailures = frontierWait = 0;
        yawSpeed = 0;
        automatic = sprintRequested = airborne = false;
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

    /** Consume movement input until Escape, a double press, or a sustained direction cancels navigation. */
    public static boolean manualKey(int key, int action) {
        if (goal == null) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            cancel();
            return true;
        }
        if (!automatic) return false;
        for (int i = 0; i < DIRECTION_KEYS.length; i++) {
            if (DIRECTION_KEYS[i] != key) continue;
            if (action == GLFW.GLFW_PRESS) {
                long now = System.nanoTime();
                if (lastPress[i] != 0 && now - lastPress[i] <= 400_000_000L) {
                    cancel();
                    return true;
                }
                lastPress[i] = now;
                heldDirections[i] = true;
            } else if (action == GLFW.GLFW_RELEASE) {
                heldDirections[i] = false;
            }
            return true;
        }
        return false;
    }

    private static void releaseControls(Minecraft minecraft) {
        if (forward) minecraft.options.keyUp.setDown(false);
        if (jump) minecraft.options.keyJump.setDown(false);
        if (descend) minecraft.options.keyShift.setDown(false);
        if (sprintOwned) NavigationPlatform.routeSprint(minecraft, false);
        forward = jump = descend = sprintOwned = false;
    }

    public static void playerMessage(String key) {
        NavigationPlatform.message(Minecraft.getInstance(), key);
    }

    private PlayerNavigation() {}
}
