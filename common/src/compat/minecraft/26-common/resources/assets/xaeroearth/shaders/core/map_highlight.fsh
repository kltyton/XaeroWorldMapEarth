#version 330
uniform sampler2D TerrainDepth;
uniform sampler2D HighlightField;
layout(std140) uniform MapHighlightParameters {
    mat4 HighlightFromClip;
    vec4 HighlightClip;
    vec4 HighlightGrid;
    vec4 HighlightOffset;
};
in vec2 uv;
out vec4 fragColor;

uint unpackPixel(vec4 value) {
    uvec4 bytes = uvec4(round(value * 255.0));
    return bytes.r | (bytes.g << 8u) | (bytes.b << 16u) | (bytes.a << 24u);
}

uint location(ivec2 chunk) {
    uint value = uint(chunk.x) * 0x9e3779b9u ^ uint(chunk.y) * 0x85ebca6bu;
    value = (value ^ (value >> 16u)) * 0x7feb352du;
    return value ^ (value >> 15u);
}

void main() {
    ivec2 pixel = ivec2(uv * vec2(textureSize(TerrainDepth, 0)));
    float depth = texelFetch(TerrainDepth, pixel, 0).r;
    if (abs(depth - HighlightClip.x) < 0.0000001) discard;
    float clipZ = mix(depth * 2.0 - 1.0, depth, HighlightClip.y);
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 hit = HighlightFromClip * vec4(ndc, clipZ, 1.0);
    vec4 nearHit = HighlightFromClip * vec4(ndc, HighlightClip.z, 1.0);
    if (abs(hit.w) < 0.000001 || abs(nearHit.w) < 0.000001) discard;
    vec3 point = hit.xyz / hit.w;
    vec3 ray = point - nearHit.xyz / nearHit.w;
    // Use the solid cell behind a visible face that lies exactly at a chunk boundary.
    point += ray * (0.015625 / max(length(ray), 0.015625));
    vec2 local = point.xz + HighlightOffset.xy;
    ivec2 chunk = ivec2(HighlightGrid.xy) + ivec2(floor(local / 16.0));
    uint mask = uint(HighlightGrid.z);
    uint slot = location(chunk) & mask;
    int columns = textureSize(HighlightField, 0).x / 3;
    for (int i = 0; i < int(HighlightGrid.w); i++) {
        ivec2 base = ivec2(int(slot) % columns * 3, int(slot) / columns);
        int address = int(unpackPixel(texelFetch(HighlightField, base + ivec2(2, 0), 0)));
        if (address == 0) break;
        ivec2 key = ivec2(int(unpackPixel(texelFetch(HighlightField, base, 0))),
                int(unpackPixel(texelFetch(HighlightField, base + ivec2(1, 0), 0))));
        if (all(equal(key, chunk))) {
            int index = abs(address);
            if (address < 0) {
                ivec2 block = ivec2(floor(mod(local, 16.0)));
                index += block.y * 16 + block.x;
            }
            int width = columns * 3;
            vec4 tint = texelFetch(HighlightField, ivec2(index % width, index / width), 0);
            if (tint.a == 0.0) discard;
            fragColor = vec4(tint.rgb, tint.a * (address < 0 ? 1.0 : 0.6));
            return;
        }
        slot = (slot + 1u) & mask;
    }
    discard;
}
