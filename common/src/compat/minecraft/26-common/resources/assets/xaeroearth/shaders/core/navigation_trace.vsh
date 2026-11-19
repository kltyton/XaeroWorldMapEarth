#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec2 UV0;
out vec2 traceUv;
flat out int markTint;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    traceUv = UV0;
    markTint = UV0.x <= -9.5 ? int(UV0.y) : 0;
}
