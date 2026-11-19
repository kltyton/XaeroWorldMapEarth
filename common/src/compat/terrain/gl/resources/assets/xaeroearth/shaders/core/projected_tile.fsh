#version 150

uniform sampler2D LowColor;
uniform sampler2D HighColor;
uniform sampler2D TileDepth;
uniform vec4 Rect;
uniform vec4 TileParams;
out vec4 fragColor;

void main() {
    vec2 origin = vec2(Rect.x, Rect.w - Rect.y - TileParams.z);
    vec2 uv = (gl_FragCoord.xy - origin + TileParams.w) / (TileParams.z + 2.0 * TileParams.w);
    bool seed = TileParams.w == 0.0;
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float depth = seed ? texelFetch(TileDepth, texel, 0).r : texture(TileDepth, uv).r;
    if (depth >= 1.0) discard;
    float shifted = depth + TileParams.y;
    if (shifted < 0.0 || shifted > 1.0) discard;
    gl_FragDepth = shifted;
    vec4 low = seed ? texelFetch(LowColor, texel, 0) : texture(LowColor, uv);
    vec4 high = seed ? texelFetch(HighColor, texel, 0) : texture(HighColor, uv);
    if (low.a <= 0.0) discard;
    fragColor = vec4(mix(low.rgb, high.rgb, TileParams.x) / low.a, 1.0);
}
