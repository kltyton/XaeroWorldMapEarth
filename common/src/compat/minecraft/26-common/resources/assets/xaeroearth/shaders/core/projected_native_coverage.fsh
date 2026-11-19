#version 330

uniform sampler2D SourceDepth;
out vec4 fragColor;

void main() {
    float depth = texelFetch(SourceDepth, ivec2(gl_FragCoord.xy), 0).r;
    fragColor = vec4(0.0, 0.0, 0.0, depth < 1.0 ? 1.0 : 0.0);
}
