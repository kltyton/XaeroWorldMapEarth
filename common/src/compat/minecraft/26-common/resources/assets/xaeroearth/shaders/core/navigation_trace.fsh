#version 330
layout(std140) uniform NavigationParameters { vec4 RouteAnimation; };
in vec2 traceUv;
flat in int markTint;
out vec4 fragColor;

void main() {
    if (traceUv.x <= -9.5) {
        int style = int(-traceUv.x + 0.5);
        if (style >= 20) {
            vec3 rgb = vec3((markTint >> 16) & 255, (markTint >> 8) & 255, markTint & 255) / 255.0;
            fragColor = vec4(rgb, float(style - 20) / 255.0);
        }
        else if (style == 10) fragColor = vec4(0.33, 0.90, 1.0, 0.97);
        else if (style == 11) fragColor = vec4(0.68, 0.44, 1.0, 0.96);
        else if (style == 12) fragColor = vec4(0.64, 0.37, 1.0, 0.28);
        else if (style == 13) fragColor = vec4(1.0, 0.81, 0.33, 0.99);
        else if (style == 14) fragColor = vec4(0.38, 1.0, 0.61, 0.99);
        else if (style == 16) fragColor = vec4(0.44, 0.92, 0.91, 0.99);
        else if (style == 17 || style == 18) {
            vec3 owner = vec3((markTint >> 16) & 255, (markTint >> 8) & 255, markTint & 255) / 255.0;
            fragColor = vec4(owner, style == 17 ? 0.35 : 0.90);
        }
        else fragColor = vec4(1.0, 0.87, 0.53, 0.97);
        return;
    }
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
