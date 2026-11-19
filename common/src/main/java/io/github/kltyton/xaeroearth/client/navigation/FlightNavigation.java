package io.github.kltyton.xaeroearth.client.navigation;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Follows a committed collision-checked flight corridor using native flight controls. */
final class FlightNavigation {
    private static final int BOOST_INTERVAL = 45;
    private static final double GLIDE_CORRECTION_TICKS = 6;
    private static final double PLANNING_HORIZON = 192;
    private static final float MAX_BOOST_DIVE = 32;
    private FlyingRoute planner;
    private FlyingRoute extension;
    private List<Vec3> steps = List.of();
    private int next, retryDelay, extensionDelay;
    private int validationDelay;
    private Vec3 anchor;
    private Vec3 landing;
    private boolean arrives;

    FlightNavigation(Vec3 origin) { anchor = origin; }

    static boolean reached(Vec3 previous, Vec3 current, Vec3 goal) {
        Vec3 motion = current.subtract(previous);
        double span = motion.lengthSqr();
        double progress = span > 0.00001 ? Mth.clamp(goal.subtract(previous).dot(motion) / span, 0, 1) : 0;
        return previous.lerp(current, progress).distanceToSqr(goal) < 1.15 * 1.15;
    }

    int tick(Minecraft minecraft, ClientLevel level, LocalPlayer player, Vec3 destination,
             boolean automatic, int boostCooldown) {
        Vec3 position = player.position();
        boolean gliding = player.isFallFlying();
        int cooldown = Math.max(0, boostCooldown - 1);
        if (planner == null && retryDelay-- <= 0) {
            anchor = position;
            planner = new FlyingRoute(level, player, anchor, destination);
        }
        if (steps.isEmpty()) {
            if (automatic && !gliding) PlayerNavigation.fly(minecraft, false, false, false);
            if (planner == null) return cooldown;
            var plan = planner.advance();
            if (plan == null) return cooldown;
            if (plan.steps().isEmpty()) {
                replan(8);
                return cooldown;
            }
            steps = plan.steps();
            arrives = plan.arrives();
        }
        if (!arrives) extend(level, player, position, destination);
        Vec3 aim = steps.get(next);
        double slack = gliding ? 1.8 : 0.7;
        Vec3 approach = aim.subtract(anchor);
        Vec3 fromAim = position.subtract(aim);
        if (automatic && gliding && arrives && next + 1 == steps.size()
                && steerLanding(level, player, destination, position, approach)) return cooldown;
        boolean passed = gliding && fromAim.lengthSqr() < 16 && fromAim.dot(approach) >= 0;
        if ((position.distanceToSqr(aim) < slack * slack || passed) && (!arrives || next + 1 < steps.size())) {
            if (next + 1 == steps.size()) {
                replan(0);
                return cooldown;
            }
            Vec3 following = steps.get(next + 1);
            if (planner.clear(position, following)) {
                anchor = aim;
                aim = following;
                next++;
            }
        }

        Vec3 leg = aim.subtract(anchor);
        double progress = Mth.clamp(position.subtract(anchor).dot(leg) / Math.max(1e-6, leg.lengthSqr()), 0, 1);
        Vec3 projection = anchor.lerp(aim, progress);
        double lookDistance = gliding ? Math.min(18, 6 + 8 * player.getDeltaMovement().length()) : 3;
        Vec3 lookAhead = projection.lerp(aim, Math.min(1, lookDistance / Math.max(0.001, projection.distanceTo(aim))));
        boolean offCourse = position.distanceToSqr(projection) > 4;
        if (offCourse && !planner.clear(position, lookAhead) || !planner.clear(projection, lookAhead)
                || validationDelay-- <= 0 && !validateRemaining(aim, projection)) {
            replan(0);
            if (automatic && !gliding) PlayerNavigation.fly(minecraft, false, false, false);
            return cooldown;
        }
        if (offCourse && !automatic) {
            replan(0);
            return cooldown;
        }
        NavigationTrace.airRoute(anchor, steps, next);
        if (!automatic) return cooldown;

        Vec3 direction = lookAhead.subtract(position);
        if (gliding) {
            // Cancel the part of the current velocity carrying the glider away from the corridor.
            Vec3 tangent = leg.normalize();
            Vec3 velocity = player.getDeltaMovement();
            Vec3 drift = velocity.subtract(tangent.scale(velocity.dot(tangent)));
            double correction = Math.min(GLIDE_CORRECTION_TICKS,
                    direction.length() / Math.max(0.05, velocity.length()));
            direction = direction.subtract(drift.scale(correction));
        }
        double horizontal = Math.sqrt(direction.horizontalDistanceSqr());
        float turn = horizontal > 0.15 ? PlayerNavigation.steer(PlayerNavigation.heading(position, position.add(direction))) : 0;
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.max(0.15, horizontal)));
        if (!gliding) {
            PlayerNavigation.steerPitch(Mth.clamp(pitch, -65, 65));
            double verticalVelocity = player.getDeltaMovement().y;
            double projectedY = position.y + verticalVelocity * 2.5;
            double verticalError = lookAhead.y - projectedY;
            // Vanilla uses jump for both thrust and the double-tap flight toggle.
            // Use its flight acceleration directly while autopilot owns movement.
            double acceleration = player.getAbilities().getFlyingSpeed() * 3;
            double verticalInput = Mth.clamp(verticalError * 0.5, -1, 1);
            player.setDeltaMovement(player.getDeltaMovement().add(0, verticalInput * acceleration, 0));
            Vec3 velocity = player.getDeltaMovement();
            double waypointDistance = WalkingRoute.horizontal(position, aim);
            double slowdown = Mth.clamp(waypointDistance / 3.0, 0.24, 1);
            if (Math.abs(turn) >= 19) slowdown *= 0.6;
            if (slowdown < 1) player.setDeltaMovement(velocity.x * slowdown, velocity.y, velocity.z * slowdown);
            PlayerNavigation.fly(minecraft, Math.abs(turn) < 19 && horizontal > 0.35, false, false);
            return cooldown;
        }

        float glidePitch = Mth.clamp(pitch, -48, 76);
        PlayerNavigation.steerPitch(glidePitch);
        // Rocket thrust follows the current look. A steep descent already supplies speed through gravity.
        if (cooldown > 0 || position.distanceToSqr(destination) <= 22 * 22 && destination.y <= position.y + 3
                || Math.abs(turn) >= 12
                || glidePitch > MAX_BOOST_DIVE || Math.abs(player.getXRot() - glidePitch) > 6
                || player.getDeltaMovement().lengthSqr() >= 0.85 || minecraft.gameMode == null
                || player.isUsingItem() || !planner.clear(position, position.add(player.getLookAngle().scale(13))))
            return cooldown;
        for (var hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).is(Items.FIREWORK_ROCKET)
                    && NavigationPlatform.useRocket(minecraft, player, hand)) return BOOST_INTERVAL;
        }
        var inventory = player.getInventory();
        int original = HotbarSelection.selected(inventory);
        for (int slot = 0; slot < 9; slot++) {
            if (slot == original || !inventory.getItem(slot).is(Items.FIREWORK_ROCKET)) continue;
            try {
                // Vanilla synchronizes the newly carried slot before sending the use packet.
                HotbarSelection.select(inventory, slot);
                return NavigationPlatform.useRocket(minecraft, player, InteractionHand.MAIN_HAND) ? BOOST_INTERVAL : 8;
            } finally {
                HotbarSelection.select(inventory, original);
            }
        }
        if (player.containerMenu == player.inventoryMenu) {
            for (int slot = 9; slot < 36; slot++) {
                if (!inventory.getItem(slot).is(Items.FIREWORK_ROCKET)) continue;
                HotbarSelection.swapWithInventory(minecraft, player, slot, original);
                try {
                    return player.getMainHandItem().is(Items.FIREWORK_ROCKET)
                            && NavigationPlatform.useRocket(minecraft, player, InteractionHand.MAIN_HAND) ? BOOST_INTERVAL : 8;
                } finally {
                    HotbarSelection.swapWithInventory(minecraft, player, slot, original);
                }
            }
        }
        return cooldown;
    }

    private boolean steerLanding(ClientLevel level, LocalPlayer player, Vec3 goal, Vec3 position, Vec3 approach) {
        if (landing == null) {
            Vec3 offset = position.subtract(goal);
            boolean passed = offset.x * approach.x + offset.z * approach.z > 0;
            if (!passed || offset.horizontalDistanceSqr() > 14 * 14 || Math.abs(offset.y) > 8) return false;
            Vec3 velocity = player.getDeltaMovement();
            var heading = new Vec3(velocity.x, 0, velocity.z).normalize();
            if (heading.lengthSqr() < 0.01) return false;
            var body = player.getBoundingBox().deflate(0.002).move(-position.x, -position.y, -position.z);
            var footing = new WalkingRoute(level, player, body, position, goal, List.of());
            if (!footing.standing(footing.destination())) return false;
            // A late approach can land ahead and finish with the ordinary ground navigator.
            // Do not turn a fast glider back through the target at ground level.
            for (int distance = 4; distance <= 12 && landing == null; distance += 4) {
                Vec3 probe = position.add(heading.scale(distance));
                for (Vec3 candidate : footing.landings(probe.x, probe.z, goal.y)) {
                    if (candidate.y <= position.y + 0.1 && planner.clear(position, candidate)) {
                        landing = candidate;
                        break;
                    }
                }
            }
        }
        if (landing == null) return false;
        Vec3 leg = landing.subtract(position);
        Vec3 velocity = player.getDeltaMovement();
        if (leg.x * velocity.x + leg.z * velocity.z < 0 || !planner.clear(position, landing)) {
            landing = null;
            return false;
        }
        PlayerNavigation.steer(PlayerNavigation.heading(position, landing));
        PlayerNavigation.steerPitch((float) Mth.clamp(-Math.toDegrees(Math.atan2(leg.y,
                Math.max(0.15, Math.sqrt(leg.horizontalDistanceSqr())))), -8, 58));
        NavigationTrace.airRoute(position, List.of(landing), 0);
        return true;
    }

    private void replan(int delay) {
        planner = extension = null;
        steps = List.of();
        landing = null;
        next = 0;
        retryDelay = delay;
        extensionDelay = 0;
        NavigationTrace.clearRoute();
    }

    private void extend(ClientLevel level, LocalPlayer player, Vec3 position, Vec3 destination) {
        if (extension == null) {
            if (extensionDelay > 0) {
                extensionDelay--;
                return;
            }
            double ahead = 0;
            Vec3 from = position;
            for (int i = next; i < steps.size(); i++) {
                Vec3 to = steps.get(i);
                ahead += from.distanceTo(to);
                if (ahead > PLANNING_HORIZON) return;
                from = to;
            }
            extension = new FlyingRoute(level, player, from, destination);
        }
        var plan = extension.advance();
        if (plan == null) return;
        extension = null;
        arrives = plan.arrives();
        if (plan.steps().isEmpty()) {
            extensionDelay = 8;
            return;
        }
        var continued = new ArrayList<Vec3>(steps.size() - next + plan.steps().size());
        continued.addAll(steps.subList(next, steps.size()));
        continued.addAll(plan.steps());
        steps = List.copyOf(continued);
        next = 0;
    }

    private boolean validateRemaining(Vec3 aim, Vec3 projection) {
        validationDelay = 7;
        if (!planner.clear(projection, aim)) return false;
        for (int i = next + 1; i < steps.size(); i++) {
            if (!planner.clear(steps.get(i - 1), steps.get(i))) return false;
        }
        return true;
    }
}
