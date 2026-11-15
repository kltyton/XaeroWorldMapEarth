bool depthIsSky(float depth) {
    return Scan.w > 0.5 ? depth <= 0.0 : depth >= 1.0;
}

vec3 calculateWorldPosition(vec2 uv, float depth) {
    vec2 ndc = uv * 2.0 - 1.0;
    float clipZ = mix(depth * 2.0 - 1.0, depth, Direction.w);
    vec4 point = WorldFromClip * vec4(ndc, clipZ, 1.0);
    return point.xyz / point.w;
}

float neighborDifference(ivec2 pixel, ivec2 size, float centerBand) {
    pixel = clamp(pixel, ivec2(0), size - ivec2(1));
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (depthIsSky(depth)) return 0.0;
    vec2 uv = (vec2(pixel) + 0.5) / vec2(size);
    float dist = distance(calculateWorldPosition(uv, depth), Origin.xyz);
    return abs(floor(dist / Scan.z) - centerBand);
}

vec4 scanColor(vec2 uv) {
    ivec2 size = textureSize(SceneDepth, 0);
    ivec2 pixel = clamp(ivec2(uv * vec2(size)), ivec2(0), size - ivec2(1));
    vec4 scene = texelFetch(SceneColor, pixel, 0);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (depthIsSky(depth)) return scene;
    vec3 delta = calculateWorldPosition((vec2(pixel) + 0.5) / vec2(size), depth) - Origin.xyz;
    float dist = length(delta);
    float wake = Scan.x - dist;
    float thickness = Scan.z * 5.0;
    float groundDistance = length(delta.xz);
    float footMask = smoothstep(0.65, 1.95, groundDistance);
    float footDarkness = Scan.y * 0.19 * (1.0 - footMask)
                       * (1.0 - smoothstep(1.5, 4.0, Scan.x)) * (1.0 - smoothstep(0.5, 2.0, abs(delta.y)));
    scene.rgb *= 1.0 - footDarkness;
    if (wake < -0.65 || wake > thickness) return scene;
    float sector = 1.0;
    if (Direction.z >= -1.0) {
        float heading = dot(delta.xz, Direction.xy) / max(groundDistance, 0.01);
        sector = smoothstep(Direction.z, min(1.0, Direction.z + 0.08), heading);
    }
    if (sector <= 0.0) return scene;
    float strength = sector * Scan.y * footMask;

    float band = floor(dist / Scan.z);
    float difference = neighborDifference(pixel + ivec2(-1, 0), size, band)
                     + neighborDifference(pixel + ivec2(1, 0), size, band)
                     + neighborDifference(pixel + ivec2(0, -1), size, band)
                     + neighborDifference(pixel + ivec2(0, 1), size, band);
    float line = smoothstep(0.45, 1.35, difference);
    float frontOffset = wake * 4.3;
    float front = exp(-frontOffset * frontOffset);
    float trail = smoothstep(0.26, 0.95, wake) * (1.0 - smoothstep(thickness * 0.40, thickness, wake));
    float darkenOffset = (wake - 0.27) * 1.7;
    float darken = strength * (0.17 * trail + 0.26 * exp(-darkenOffset * darkenOffset));
    vec3 color = scene.rgb * (1.0 - darken);
    color = mix(color, vec3(0.02, 0.69, 1.0), strength * trail * (0.10 + 0.80 * line));
    color = mix(color, vec3(0.94, 0.99, 1.0), strength * front);
    return vec4(color, scene.a);
}
