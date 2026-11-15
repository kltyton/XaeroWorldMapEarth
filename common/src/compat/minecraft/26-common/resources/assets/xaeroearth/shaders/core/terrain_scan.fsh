#version 330
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
layout(std140) uniform TerrainScanParameters {
    mat4 WorldFromClip;
    vec4 Origin;
    vec4 Direction;
    vec4 Scan;
};
in vec2 texCoord;
out vec4 fragColor;

#moj_import <xaeroearth:terrain_scan.glsl>

void main() {
    fragColor = scanColor(texCoord);
}
