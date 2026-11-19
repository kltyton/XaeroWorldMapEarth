#version 330
layout(std140) uniform MapFogParameters { vec4 FogView; };
in vec2 uv;
out vec4 fragColor;

void main() {
    vec2 page = (uv - 0.5) * vec2(FogView.x, 1.0);
    float wash = 1.0 - smoothstep(0.0, 0.95, length(page * vec2(0.72, 1.13) - vec2(0.0, 0.10)));
    vec3 edge = mix(vec3(0.23, 0.31, 0.40), vec3(0.075, 0.090, 0.115), FogView.y);
    vec3 light = mix(vec3(0.44, 0.54, 0.63), vec3(0.17, 0.20, 0.24), FogView.y);
    fragColor = vec4(mix(edge, light, wash), 1.0);
}
