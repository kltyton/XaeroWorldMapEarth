#version 330
uniform sampler2D SurfaceColor;
uniform sampler2D SurfaceHeight;
uniform sampler2D Coverage;
layout(std140) uniform SurfaceParams { vec4 Grid; vec4 CoverageRegion; vec4 SurfaceOrigin; vec4 SurfaceExtra; };
in vec3 localPosition;
flat in ivec2 outlineCell;
in float outlineDepth;
out vec4 fragColor;
vec4 litCell(ivec2 cell) {
    vec4 color = texelFetch(SurfaceColor, cell, 0);
    int lightRow = textureSize(SurfaceHeight, 0).y - int(Grid.y);
    vec4 encoded = texelFetch(SurfaceHeight, cell + ivec2(0, lightRow), 0);
    float blockLight = encoded.r;
    if (SurfaceExtra.x > 0.5) {
        uvec4 bytes = uvec4(round(encoded * 255.0));
        blockLight = uintBitsToFloat(bytes.r | (bytes.g << 8u) | (bytes.b << 16u) | (bytes.a << 24u));
    }
    // Xaero's mapped surface provides block light. An exposed sample uses full sky light.
    float light = mix(blockLight, 1.0, clamp(Grid.w, 0.0, 1.0));
    color.rgb = floor(color.rgb * light * 255.0 + 0.5) / 255.0;
    return color;
}
vec4 litSurface(vec2 uv) {
    ivec2 last = ivec2(Grid.xy) - 1;
    vec2 position = uv * Grid.xy - 0.5;
    ivec2 base = ivec2(floor(position));
    vec2 part = fract(position);
    vec4 a = litCell(clamp(base, ivec2(0), last));
    vec4 b = litCell(clamp(base + ivec2(1, 0), ivec2(0), last));
    vec4 c = litCell(clamp(base + ivec2(0, 1), ivec2(0), last));
    vec4 d = litCell(clamp(base + ivec2(1, 1), ivec2(0), last));
    return mix(mix(a, b, part.x), mix(c, d, part.x), part.y);
}
bool detailedAt(ivec2 cell) {
    ivec2 size = textureSize(Coverage, 0);
    return all(greaterThanEqual(cell, ivec2(0))) && all(lessThan(cell, size))
            && texelFetch(Coverage, cell, 0).r > 0.75;
}
bool mappedCoarseAt(vec2 worldXZ, ivec2 cell) {
    if (detailedAt(cell)) return false;
    ivec2 sampleCell = ivec2(floor((worldXZ - SurfaceOrigin.xz) / Grid.z));
    return all(greaterThanEqual(sampleCell, ivec2(0))) && all(lessThan(sampleCell, ivec2(Grid.xy)))
            && texelFetch(SurfaceColor, sampleCell, 0).a > 0.0;
}
bool touchesCoarseEdge(vec2 worldXZ, ivec2 cell) {
    vec2 local = (worldXZ + CoverageRegion.xy) - vec2(cell) * 16.0;
    const float overlap = 0.25;
    if (local.x < overlap && mappedCoarseAt(worldXZ - vec2(overlap, 0.0), cell - ivec2(1, 0))) return true;
    if (local.y < overlap && mappedCoarseAt(worldXZ - vec2(0.0, overlap), cell - ivec2(0, 1))) return true;
    if (local.x > 16.0 - overlap && mappedCoarseAt(worldXZ + vec2(overlap, 0.0), cell + ivec2(1, 0))) return true;
    return local.y > 16.0 - overlap && mappedCoarseAt(worldXZ + vec2(0.0, overlap), cell + ivec2(0, 1));
}
void main() {
    gl_FragDepth = gl_FragCoord.z;
    vec2 worldXZ = localPosition.xz;
    if (outlineCell.x >= 0) {
        // Sample the owner cell even when the rasterized edge lies exactly on its boundary.
        worldXZ = SurfaceOrigin.xz + (vec2(outlineCell) + 0.5) * Grid.z;
    }
    ivec2 cell = ivec2(floor((worldXZ + CoverageRegion.xy) / 16.0));
    // Keep the outer explored-map skirt (outlineCell.x >= 0) even on detailed border chunks so low
    // oblique camera angles never look through culled outer walls into hollow underground caves.
    if (outlineCell.x < 0 && detailedAt(cell)) {
        if (!touchesCoarseEdge(worldXZ, cell)) discard;
        // Leave the backing just inside the device's far depth plane.
        gl_FragDepth = SurfaceExtra.y > 0.5 ? 0.999999 : 0.000001;
    }
    if (outlineCell.x >= 0) {
        vec3 color = litCell(outlineCell).rgb;
        if (outlineDepth < 0.0) {
            fragColor = vec4(color * mix(1.0, 0.72, smoothstep(0.0, 1.0, -1.0 - outlineDepth)), 1.0);
            return;
        }
        vec3 rock = vec3(0.25, 0.24, 0.22) * mix(0.55, 1.0, clamp(Grid.w, 0.0, 1.0));
        fragColor = vec4(mix(color * 0.67, rock, smoothstep(0.0, 0.65, outlineDepth)), 1.0);
        return;
    }
    vec4 color = litSurface((localPosition.xz - SurfaceOrigin.xz) / (Grid.xy * Grid.z));
    if (color.a == 0.0) discard;
    vec3 normal = normalize(cross(dFdy(localPosition), dFdx(localPosition)));
    float shade = 0.72 + 0.28 * abs(normal.y);
    // Unmapped texels carry zero RGB. Recover mapped color from the filtered coverage at shorelines.
    fragColor = vec4((color.rgb / color.a) * shade, 1.0);
}
