#version 330

uniform sampler2D SourceDepth;
out vec4 fragColor;

void main() {
    uint bits = floatBitsToUint(texelFetch(SourceDepth, ivec2(gl_FragCoord.xy), 0).r);
    uvec4 bytes = (uvec4(bits) >> uvec4(0u, 8u, 16u, 24u)) & uvec4(255u);
    fragColor = vec4(bytes) / 255.0;
}
