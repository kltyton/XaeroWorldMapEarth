#version 330
layout(std140) uniform ProjectedTileParams { vec4 Rect; vec4 TileParams; vec4 DepthFlags; };
uniform sampler2D LowColor;
uniform sampler2D HighColor;
uniform sampler2D EncodedDepth;
out vec4 fragColor;

void main() {
    vec2 origin = vec2(Rect.x, Rect.w - Rect.y - TileParams.z);
    vec2 uv = (gl_FragCoord.xy - origin + TileParams.w) / (TileParams.z + 2.0 * TileParams.w);
    bool seed = TileParams.w == 0.0;
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 encoded = seed ? texelFetch(EncodedDepth, texel, 0) : texture(EncodedDepth, uv);
    uvec4 bytes = uvec4(round(encoded * 255.0));
    float depth = uintBitsToFloat(bytes.x | (bytes.y << 8u) | (bytes.z << 16u) | (bytes.w << 24u));
    if (DepthFlags.x > 0.5 ? depth <= 0.0 : depth >= 1.0) discard;
    float shifted = depth + TileParams.y;
    if (shifted < 0.0 || shifted > 1.0) discard;
    vec4 low = seed ? texelFetch(LowColor, texel, 0) : texture(LowColor, uv);
    vec4 high = seed ? texelFetch(HighColor, texel, 0) : texture(HighColor, uv);
    if (low.a <= 0.0) discard;
    gl_FragDepth = shifted;
    fragColor = vec4(mix(low.rgb, high.rgb, TileParams.x) / low.a, 1.0);
}
