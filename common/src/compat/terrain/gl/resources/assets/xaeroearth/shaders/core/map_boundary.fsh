#version 150
uniform sampler2D TerrainDepth;
uniform sampler2D BoundaryField;
uniform mat4 WorldFromClip;
uniform vec4 BoundaryOrigin;
uniform vec4 BoundaryView;
uniform vec4 BoundaryRay;
in vec2 uv;
out vec4 fragColor;

float edgeDistance(ivec2 cell) {
    ivec2 bounded = clamp(cell, ivec2(0), ivec2(BoundaryView.xy) - ivec2(1));
    return texelFetch(BoundaryField, bounded + ivec2(0, int(BoundaryOrigin.w)), 0).r;
}

bool insideField(vec2 cell) {
    return all(greaterThanEqual(cell, vec2(0))) && all(lessThan(cell, BoundaryView.xy));
}

float interpolatedDistance(vec2 cell) {
    if (edgeDistance(ivec2(cell)) == 0.0) return 0.0;
    vec2 sampleAt = cell - 0.5;
    ivec2 low = ivec2(floor(sampleAt));
    vec2 f = fract(sampleAt);
    return mix(mix(edgeDistance(low), edgeDistance(low + ivec2(1, 0)), f.x),
            mix(edgeDistance(low + ivec2(0, 1)), edgeDistance(low + ivec2(1, 1)), f.x), f.y);
}

float surfaceFloor(vec2 cell) {
    ivec2 last = ivec2(BoundaryView.xy) - ivec2(1);
    ivec2 low = ivec2(floor(cell - 0.5));
    float floorY = texelFetch(BoundaryField, clamp(ivec2(cell), ivec2(0), last), 0).r - 1.0;
    for (int dz = 0; dz <= 1; dz++) {
        for (int dx = 0; dx <= 1; dx++) {
            ivec2 sampleCell = clamp(low + ivec2(dx, dz), ivec2(0), last);
            if (edgeDistance(sampleCell) > 0.0) {
                floorY = min(floorY, texelFetch(BoundaryField, sampleCell, 0).r - 1.0);
            }
        }
    }
    return floorY;
}

void main() {
    float raw = texelFetch(TerrainDepth, ivec2(uv * vec2(textureSize(TerrainDepth, 0))), 0).r;
    if (raw >= 1.0) discard;
    float clipZ = raw * 2.0 - 1.0;
    vec4 world = WorldFromClip * vec4(uv * 2.0 - 1.0, clipZ, 1.0);
    vec2 cell = (world.xz / world.w - BoundaryOrigin.xy) / BoundaryOrigin.z;
    if (!insideField(cell)) discard;
    float distance = interpolatedDistance(cell);
    float surface = surfaceFloor(cell);
    float below = surface - (world.y / world.w + BoundaryRay.x) - max(6.0, 1.5 * BoundaryOrigin.z);
    if (below > 0.0 && distance < BoundaryView.z * 0.55) {
        // Keep the ray-lift strictly on the outer border skirt so interior cliffs, mountains,
        // valleys, cave mouths, and foreground tree trunks are never fogged at low camera pitch.
        vec2 liftBlocks = BoundaryRay.yz * below;
        float liftLen = length(liftBlocks);
        float maxLift = BoundaryView.z * 0.55;
        if (liftLen > maxLift) liftBlocks *= maxLift / liftLen;
        vec2 lifted = clamp(cell + liftBlocks / BoundaryOrigin.z, vec2(0.0), BoundaryView.xy - vec2(1.0));
        distance = min(distance, interpolatedDistance(lifted));
    }
    float inner = min(4.0, BoundaryView.z * 0.08);
    float edge = 1.0 - smoothstep(inner, BoundaryView.z, distance);
    // Square the falloff so only the immediate outer border reaches full mist opacity,
    // preventing foreground explored terrain near the border from becoming an opaque cutout.
    float veil = edge * edge;
    if (veil < 0.002) discard;

    vec2 page = (uv - 0.5) * vec2(BoundaryView.w, 1.0);
    float wash = 1.0 - smoothstep(0.0, 0.95, length(page * vec2(0.72, 1.13) - vec2(0.0, 0.10)));
    vec3 ink = mix(vec3(0.23, 0.31, 0.40), vec3(0.44, 0.54, 0.63), wash);
    fragColor = vec4(ink, veil);
}
