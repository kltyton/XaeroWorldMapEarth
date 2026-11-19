#version 150

uniform sampler2D PackColor;
uniform sampler2D PackDepth;
uniform sampler2D CoarseColor;
uniform sampler2D CoarseHeight;
uniform sampler2D DetailCoverage;
uniform vec4 PackRaster;
uniform vec4 PackOrigin;
uniform mat4 PackWorldFromClip;
uniform vec4 CoarseSurface;
uniform vec4 CoarseGrid;
uniform vec4 CoarseDepth;
out vec4 color;

bool hasDetail(ivec2 chunk) {
    ivec2 size = textureSize(DetailCoverage, 0);
    return all(greaterThanEqual(chunk, ivec2(0))) && all(lessThan(chunk, size))
            && texelFetch(DetailCoverage, chunk, 0).r > 0.75;
}

bool hasCoarse(vec2 point, ivec2 neighbor) {
    if (hasDetail(neighbor)) return false;
    ivec2 cell = ivec2(floor((point - CoarseSurface.xy) / CoarseSurface.z));
    ivec2 size = textureSize(CoarseColor, 0);
    return all(greaterThanEqual(cell, ivec2(0))) && all(lessThan(cell, size))
            && texelFetch(CoarseColor, cell, 0).a > 0.0;
}

float coarseValue(ivec2 at) { return texelFetch(CoarseHeight, at, 0).r; }

vec3 blendLod(vec3 shaded, float depth, vec2 localPixel) {
    float width = CoarseGrid.z;
    if (width <= 0.0) return shaded;
    vec2 uv = localPixel / vec2(textureSize(PackColor, 0));
    vec4 position = PackWorldFromClip * vec4(uv * 2.0 - 1.0, depth * CoarseDepth.x + CoarseDepth.y, 1.0);
    vec3 local = position.xyz / position.w;
    vec2 chunkPosition = local.xz + CoarseGrid.xy;
    ivec2 chunk = ivec2(floor(chunkPosition / 16.0));
    if (!hasDetail(chunk)) return shaded;
    vec2 offset = chunkPosition - vec2(chunk) * 16.0;
    float edge = width;
    if (offset.x < edge && hasCoarse(local.xz - vec2(offset.x + 0.125, 0.0), chunk - ivec2(1, 0)))
        edge = offset.x;
    if (offset.y < edge && hasCoarse(local.xz - vec2(0.0, offset.y + 0.125), chunk - ivec2(0, 1)))
        edge = offset.y;
    if (16.0 - offset.x < edge && hasCoarse(local.xz + vec2(16.125 - offset.x, 0.0), chunk + ivec2(1, 0)))
        edge = 16.0 - offset.x;
    if (16.0 - offset.y < edge && hasCoarse(local.xz + vec2(0.0, 16.125 - offset.y), chunk + ivec2(0, 1)))
        edge = 16.0 - offset.y;
    if (edge >= width) return shaded;

    ivec2 size = textureSize(CoarseColor, 0);
    ivec2 cell = ivec2(floor((local.xz - CoarseSurface.xy) / CoarseSurface.z));
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, size))) return shaded;
    vec4 coarse = texelFetch(CoarseColor, cell, 0);
    // Raised foliage and exposed cliff walls still receive their complete material shading.
    if (coarse.a <= 0.0 || abs(coarseValue(cell) - (local.y + CoarseGrid.w)) > max(4.0, CoarseSurface.z * 2.0))
        return shaded;
    int lightRow = textureSize(CoarseHeight, 0).y - size.y;
    float light = mix(coarseValue(cell + ivec2(0, lightRow)), 1.0, clamp(CoarseSurface.w, 0.0, 1.0));
    return mix(coarse.rgb / coarse.a * light, shaded, smoothstep(0.0, width, edge));
}

void main() {
    vec2 local = gl_FragCoord.xy - PackOrigin.xy;
    ivec2 pixel = ivec2(floor(local));
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, textureSize(PackColor, 0)))) discard;
    ivec2 depthSize = textureSize(PackDepth, 0);
    ivec2 depthPixel = clamp(ivec2(floor(local * PackRaster.xy + PackRaster.zw)),
                            ivec2(0), depthSize - ivec2(1));
    float depth = texelFetch(PackDepth, depthPixel, 0).r;
    if (depth >= 1.0) discard;
    gl_FragDepth = depth;
    color = vec4(blendLod(texelFetch(PackColor, pixel, 0).rgb, depth, local), 1.0);
}
