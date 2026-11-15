#version 330
layout(std140) uniform NavigationParameters { vec4 RouteAnimation; };
in vec2 traceUv;
out vec4 fragColor;

void main() {
    if (traceUv.x < 0.0) {
        float pulse = 0.84 + 0.16 * sin(RouteAnimation.x * 5.0);
        fragColor = vec4(mix(vec3(0.0, 0.70, 1.0), vec3(0.91, 1.0, 1.0), pulse),
                         (1.0 - abs(traceUv.y) * 0.22) * pulse);
        return;
    }
    float crossSection = abs(traceUv.y);
    float edgeGlow = 1.0 - smoothstep(0.28, 1.0, crossSection);
    float core = 1.0 - smoothstep(0.10, 0.38, crossSection);
    float pulse = 0.5 + 0.5 * cos(6.283185 * (traceUv.x * 0.55 - RouteAnimation.x * 1.65));
    float alpha = edgeGlow * (0.30 + 0.60 * core + 0.10 * pulse);
    vec3 color = mix(vec3(0.01, 0.39, 1.0), vec3(0.39, 0.92, 1.0), core * (0.75 + 0.25 * pulse));
    fragColor = vec4(color, alpha);
}
