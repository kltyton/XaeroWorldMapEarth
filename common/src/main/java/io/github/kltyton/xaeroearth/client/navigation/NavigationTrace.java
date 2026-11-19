package io.github.kltyton.xaeroearth.client.navigation;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Builds depth-tested route geometry in world coordinates for the world and map cameras. */
public final class NavigationTrace {
    public static final int STRIDE = 20;
    private static ClientLevel level;
    private static Entity mover;
    private static final Mesh lead = new Mesh(), tail = new Mesh(), target = new Mesh();
    private static final double HEIGHT = 0.12;
    private static final double HALF_WIDTH = 0.20;
    private static final AABB TRACE_BODY = new AABB(-HALF_WIDTH, 0.04, -HALF_WIDTH,
            HALF_WIDTH, HEIGHT * 2 + 0.04, HALF_WIDTH);
    // Tight centerline probe for vertical step risers so a 1-block step face 0.05..0.18 blocks
    // away does not reject a valid stair/ledge climb or drop and leave a gap in the guide line.
    private static final AABB STEP_BODY = new AABB(-0.03, 0.04, -0.03, 0.03, HEIGHT * 2 + 0.04, 0.03);
    private static List<Vec3> cachedPoints;
    private static int cachedFirst;
    private static double remainingLegs;
    private static Vec3 destination;
    private static Vec3 leadAnchor;
    private static float leadLength;
    private static long directSegments, bridgedSegments, fallbackSegments, droppedUnloaded;

    static void start(ClientLevel world, Entity entity, Vec3 destination) {
        level = world;
        mover = entity;
        clearRoute();
        updateDestination(destination);
    }

    static void updateDestination(Vec3 destination) {
        NavigationTrace.destination = destination;
        target.reset();
        destinationMarker(destination);
    }

    static void route(Vec3 position, Vec3 previous, List<Vec3> points, int first) {
        Vec3 start = position;
        Vec3 firstPoint = points.get(first);
        if (cachedPoints != points || cachedFirst != first || leadAnchor == null
                || leadAnchor != firstPoint && leadAnchor.subtract(start).dot(firstPoint.subtract(previous)) <= 0) {
            rememberRoute(points, first);
            tail.reset();
            double legLength = WalkingRoute.horizontal(previous, firstPoint);
            double remaining = WalkingRoute.horizontal(start, firstPoint);
            // Keep the terrain mesh fixed while the player moves. Only rebuild the nearby lead.
            leadAnchor = Math.abs(firstPoint.y - previous.y) > 0.002 || legLength < 8 || remaining < 8
                    ? firstPoint : firstPoint.lerp(previous, Math.min(1, (remaining - 8) / legLength));
            leadLength = (float) WalkingRoute.horizontal(start, leadAnchor);
            float along = leadLength + (float) WalkingRoute.horizontal(leadAnchor, firstPoint);
            segment(tail, leadAnchor, firstPoint, leadLength, along);
            for (int i = first + 1; i < points.size(); i++) {
                Vec3 from = points.get(i - 1), to = points.get(i);
                float length = (float) WalkingRoute.horizontal(from, to);
                if (i > first + 1 || leadAnchor != firstPoint)
                    join(tail, i > first + 1 ? points.get(i - 2) : leadAnchor, from, to, along);
                segment(tail, from, to, along, along + length);
                along += length;
            }
        }
        lead.reset();
        segment(lead, start, leadAnchor, Math.max(0, leadLength - (float) WalkingRoute.horizontal(start, leadAnchor)), leadLength);
        if (leadAnchor != firstPoint) join(lead, start, leadAnchor, firstPoint, leadLength);
        else if (first + 1 < points.size()) join(lead, start, firstPoint, points.get(first + 1), leadLength);
    }

    static void airRoute(Vec3 start, List<Vec3> points, int first) {
        if (cachedPoints == points && cachedFirst == first) return;
        rememberRoute(points, first);
        leadAnchor = null;
        lead.reset(); tail.reset();
        float along = 0;
        for (int i = first; i < points.size(); i++) {
            Vec3 point = points.get(i);
            float distance = (float) start.distanceTo(point);
            if (distance >= 0.01F) {
                float end = along + distance;
                beam(lead, start, point, along, end);
                along = end;
            }
            start = point;
        }
    }

    private static void rememberRoute(List<Vec3> points, int first) {
        if (cachedPoints == points && cachedFirst == first) return;
        cachedPoints = points;
        cachedFirst = first;
        remainingLegs = 0;
        for (int i = first + 1; i < points.size(); i++)
            remainingLegs += points.get(i - 1).distanceTo(points.get(i));
    }

    static int remainingBlocks(Vec3 position) {
        if (destination == null) return 0;
        double distance = cachedPoints == null ? position.distanceTo(destination)
                : position.distanceTo(cachedPoints.get(cachedFirst)) + remainingLegs
                        + cachedPoints.get(cachedPoints.size() - 1).distanceTo(destination);
        return (int) Math.ceil(distance);
    }

    public static String distanceLabel(ClientLevel world) {
        return world == null || world != level || mover == null || destination == null ? null
                : I18n.get("xaeroearth.navigation.distance_short", remainingBlocks(mover.position()));
    }

    private static void destinationMarker(Vec3 destination) {
        Vec3 center = destination.add(0, 0.22, 0);
        for (int i = 0; i < 32; i++) {
            double a = 2 * Math.PI * i / 32, b = 2 * Math.PI * (i + 1) / 32;
            Vec3 u = new Vec3(Math.cos(a), 0, Math.sin(a)), v = new Vec3(Math.cos(b), 0, Math.sin(b));
            target.quad(center.add(u.scale(0.78)), center.add(u.scale(0.47)),
                    center.add(v.scale(0.47)), center.add(v.scale(0.78)), -2, -2);
        }
        Vec3 middle = center.add(0, 1.25, 0);
        Vec3 bottom = middle.add(0, -0.6, 0), top = middle.add(0, 0.6, 0);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2, b = (i + 1) * Math.PI / 2;
            Vec3 left = middle.add(Math.cos(a) * 0.42, 0, Math.sin(a) * 0.42);
            Vec3 right = middle.add(Math.cos(b) * 0.42, 0, Math.sin(b) * 0.42);
            target.vertex(bottom, -2, 0); target.vertex(left, -2, 0); target.vertex(right, -2, 0);
            target.vertex(top, -2, 0); target.vertex(right, -2, 0); target.vertex(left, -2, 0);
        }
    }

    private static void segment(Mesh mesh, Vec3 from, Vec3 to, float begin, float end) {
        double length = WalkingRoute.horizontal(from, to);
        if (from.distanceToSqr(to) < 0.0001) return;
        double dirX = length > 0.001 ? (to.x - from.x) / length : 1.0;
        double dirZ = length > 0.001 ? (to.z - from.z) / length : 0.0;
        int segments = Math.max(2, (int) Math.ceil(length / 0.16));
        double high = Math.max(from.y, to.y) + 0.55, low = Math.min(from.y, to.y) - 0.55;
        Vec3 previous = groundEndpoint(from);
        int lastSample = 0;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            Vec3 current = i == segments ? groundEndpoint(to) : groundSample(from.lerp(to, t), high, low);
            if (current == null) continue;
            // The walker's footprint can be supported when its center ray misses. Sweep the
            // beam between known samples so a narrow gap cannot remove a walkable leg.
            float start = begin + (end - begin) * lastSample / segments;
            float finish = begin + (end - begin) * i / segments;
            connectGround(mesh, previous, current, start, finish, dirX, dirZ);
            previous = current;
            lastSample = i;
        }
    }

    private static Vec3 groundEndpoint(Vec3 feet) {
        double surface = snowSurface(feet, feet.y + 0.45, feet.y - 0.25, feet.y);
        return new Vec3(feet.x, surface, feet.z);
    }

    private static Vec3 groundSample(Vec3 point, double high, double low) {
        var hit = level.clip(new ClipContext(new Vec3(point.x, high, point.z),
                new Vec3(point.x, low, point.z), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mover));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) return null;
        return new Vec3(point.x, snowSurface(point, high, low, hit.getLocation().y), point.z);
    }

    private static void connectGround(Mesh mesh, Vec3 from, Vec3 to, float begin, float end, double dirX, double dirZ) {
        if (traceClear(from, to)) {
            directSegments++;
            groundBeam(mesh, from, to, begin, end, dirX, dirZ);
            return;
        }
        double height = Math.max(from.y, to.y);
        if (bridgeStep(mesh, from, to, height, begin, end, dirX, dirZ, TRACE_BODY)
                || bridgeStep(mesh, from, to, height, begin, end, dirX, dirZ, STEP_BODY)
                || bridgeStep(mesh, from, to, height + 0.25, begin, end, dirX, dirZ, STEP_BODY)
                || bridgeStep(mesh, from, to, height + 0.55, begin, end, dirX, dirZ, STEP_BODY)) {
            bridgedSegments++;
            return;
        }
        // Only suppress output in genuinely unloaded chunks. A loaded segment belongs to a
        // WalkingRoute-verified walkable leg (real walls remain depth-occluded by GL_LEQUAL);
        // never drop a verified grass-step, snow-layer, or diagonal corner to an empty gap.
        AABB bounds = STEP_BODY.move(from).minmax(STEP_BODY.move(to));
        if (!NavigationCollision.loaded(level, bounds)) {
            droppedUnloaded++;
            return;
        }
        fallbackSegments++;
        emitStepBridge(mesh, from, to, height + 0.02, begin, end, dirX, dirZ);
    }

    private static boolean bridgeStep(Mesh mesh, Vec3 from, Vec3 to, double height, float begin, float end,
                                      double dirX, double dirZ, AABB probe) {
        Vec3 aboveFrom = new Vec3(from.x, height, from.z);
        Vec3 aboveTo = new Vec3(to.x, height, to.z);
        if (!NavigationCollision.clear(level, mover, probe, from, aboveFrom)
                || !NavigationCollision.clear(level, mover, probe, aboveFrom, aboveTo)
                || !NavigationCollision.clear(level, mover, probe, aboveTo, to)) return false;
        emitStepBridge(mesh, from, to, height, begin, end, dirX, dirZ);
        return true;
    }

    private static void emitStepBridge(Mesh mesh, Vec3 from, Vec3 to, double height, float begin, float end,
                                       double dirX, double dirZ) {
        Vec3 aboveFrom = new Vec3(from.x, height, from.z);
        Vec3 aboveTo = new Vec3(to.x, height, to.z);
        double rise = from.distanceTo(aboveFrom), span = aboveFrom.distanceTo(aboveTo), drop = aboveTo.distanceTo(to);
        double total = rise + span + drop;
        if (total < 0.0001) return;
        float raised = begin + (end - begin) * (float) (rise / total);
        float lowered = end - (end - begin) * (float) (drop / total);
        if (rise > 0.0001) groundBeam(mesh, from, aboveFrom, begin, raised, dirX, dirZ);
        if (span > 0.0001) groundBeam(mesh, aboveFrom, aboveTo, raised, lowered, dirX, dirZ);
        if (drop > 0.0001) groundBeam(mesh, aboveTo, to, lowered, end, dirX, dirZ);
    }

    private static boolean traceClear(Vec3 from, Vec3 to) {
        return NavigationCollision.clear(level, mover, TRACE_BODY, from, to);
    }

    private static void groundBeam(Mesh mesh, Vec3 from, Vec3 to, float begin, float end, double dirX, double dirZ) {
        beam(mesh, from.add(0, HEIGHT + 0.04, 0), to.add(0, HEIGHT + 0.04, 0), begin, end, dirX, dirZ);
    }

    private static double snowSurface(Vec3 position, double top, double bottom, double ground) {
        int x = (int) Math.floor(position.x), z = (int) Math.floor(position.z);
        int from = (int) Math.floor(top), until = (int) Math.floor(bottom);
        for (int y = from; y >= until; y--) {
            var state = level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
            if (state.is(Blocks.SNOW))
                return Math.max(ground, y + state.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS) / 8.0);
        }
        return ground;
    }

    private static void beam(Mesh mesh, Vec3 a, Vec3 b, float begin, float end) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double length = Math.hypot(dx, dz);
        double dirX = length > 0.001 ? dx / length : 1.0;
        double dirZ = length > 0.001 ? dz / length : 0.0;
        beam(mesh, a, b, begin, end, dirX, dirZ);
    }

    private static void beam(Mesh mesh, Vec3 a, Vec3 b, float begin, float end, double dirX, double dirZ) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double length = Math.hypot(dx, dz);
        double refX = length > 0.001 ? dx / length : dirX;
        double refZ = length > 0.001 ? dz / length : dirZ;
        double refLen = Math.hypot(refX, refZ);
        double nx = refLen > 0.001 ? refZ * HALF_WIDTH / refLen : HALF_WIDTH;
        double nz = refLen > 0.001 ? -refX * HALF_WIDTH / refLen : 0;
        mesh.quad(a.add(nx, 0, nz), a.add(-nx, 0, -nz),
                b.add(-nx, 0, -nz), b.add(nx, 0, nz), begin, end, nx, nz);
        if (length > 0.001) {
            Vec3 cross = new Vec3(-nz, 0, nx).cross(b.subtract(a)).normalize().scale(HEIGHT);
            mesh.quad(a.add(cross), a.subtract(cross), b.subtract(cross), b.add(cross), begin, end);
        } else {
            // Vertical riser: orient the perpendicular ribbon along the travel direction instead of fixed Z.
            double vx = refLen > 0.001 ? refX * HEIGHT / refLen : 0;
            double vz = refLen > 0.001 ? refZ * HEIGHT / refLen : HEIGHT;
            mesh.quad(a.add(vx, 0, vz), a.add(-vx, 0, -vz),
                    b.add(-vx, 0, -vz), b.add(vx, 0, vz), begin, end);
        }
    }

    private static void join(Mesh mesh, Vec3 from, Vec3 at, Vec3 to, float along) {
        double a = WalkingRoute.horizontal(from, at), b = WalkingRoute.horizontal(at, to);
        if (a < 0.01 || b < 0.01) return;
        double turn = (at.x - from.x) * (to.z - at.z) - (at.z - from.z) * (to.x - at.x);
        if (Math.abs(turn) < 0.001) return;
        double side = Math.copySign(HALF_WIDTH, turn);
        Vec3 center = groundEndpoint(at).add(0, HEIGHT + 0.04, 0);
        mesh.vertex(center, along, 0);
        double ax = (at.z - from.z) * side / a, az = (from.x - at.x) * side / a;
        double bx = (to.z - at.z) * side / b, bz = (at.x - to.x) * side / b;
        mesh.vertex(center.add(ax, 0, az), along, 1, ax, az);
        mesh.vertex(center.add(bx, 0, bz), along, 1, bx, bz);
    }

    static void clearRoute() {
        cachedPoints = null;
        leadAnchor = null;
        lead.reset();
        tail.reset();
    }

    static void stop() {
        level = null;
        mover = null;
        destination = null;
        clearRoute();
        target.reset();
    }

    public record Diagnostics(int leadVertices, int tailVertices, int targetVertices,
                              long directSegments, long bridgedSegments, long fallbackSegments, long droppedUnloaded) { }

    public static Diagnostics diagnostics() {
        return new Diagnostics(lead.count, tail.count, target.count,
                directSegments, bridgedSegments, fallbackSegments, droppedUnloaded);
    }

    public static int vertices(ClientLevel world) {
        return level == world && level != null ? lead.count + tail.count + target.count : 0;
    }

    public static double mapWidth(MapCamera camera, int targetWidth) {
        if (camera.mode().equals("street")) return 1;
        double blocksToPixels = camera.pixelsPerBlock() * targetWidth / camera.width();
        return Math.max(1, Math.min(12, 2.5 / (HALF_WIDTH * blocksToPixels)));
    }

    public static void write(ByteBuffer bytes, Vec3 camera, double width) {
        lead.write(bytes, camera, width - 1);
        tail.write(bytes, camera, width - 1);
        target.write(bytes, camera, 0);
        bytes.flip();
    }

    private static final class Mesh {
        private static final int COMPONENTS = 7;
        private double[] vertices = new double[COMPONENTS * 512];
        private int count;

        void reset() { count = 0; }

        void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, float begin, float end) {
            quad(a, b, c, d, begin, end, 0, 0);
        }

        void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, float begin, float end, double nx, double nz) {
            vertex(a, begin, -1, nx, nz); vertex(b, begin, 1, -nx, -nz); vertex(c, end, 1, -nx, -nz);
            vertex(a, begin, -1, nx, nz); vertex(c, end, 1, -nx, -nz); vertex(d, end, -1, nx, nz);
        }

        void vertex(Vec3 position, float u, float v) {
            vertex(position, u, v, 0, 0);
        }

        void vertex(Vec3 position, float u, float v, double sideX, double sideZ) {
            int index = count++ * COMPONENTS;
            if (index + COMPONENTS > vertices.length) vertices = Arrays.copyOf(vertices, vertices.length * 2);
            vertices[index] = position.x;
            vertices[index + 1] = position.y;
            vertices[index + 2] = position.z;
            vertices[index + 3] = u;
            vertices[index + 4] = v;
            vertices[index + 5] = sideX;
            vertices[index + 6] = sideZ;
        }

        void write(ByteBuffer bytes, Vec3 camera, double extraWidth) {
            for (int i = 0; i < count * COMPONENTS; i += COMPONENTS) {
                bytes.putFloat((float) (vertices[i] - camera.x + extraWidth * vertices[i + 5]));
                bytes.putFloat((float) (vertices[i + 1] - camera.y));
                bytes.putFloat((float) (vertices[i + 2] - camera.z + extraWidth * vertices[i + 6]));
                bytes.putFloat((float) vertices[i + 3]);
                bytes.putFloat((float) vertices[i + 4]);
            }
        }
    }

    private NavigationTrace() {}
}
