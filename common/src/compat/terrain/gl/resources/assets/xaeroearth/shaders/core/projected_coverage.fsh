#version 150

uniform sampler2D TileDepth;
out vec4 fragColor;

void main() {
    float depth = texelFetch(TileDepth, ivec2(gl_FragCoord.xy), 0).r;
    fragColor = vec4(0.0, 0.0, 0.0, depth < 1.0 ? 1.0 : 0.0);
}
