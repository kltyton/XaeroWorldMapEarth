#version 150
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform mat4 WorldFromClip;
uniform vec4 Origin;
uniform vec4 Direction;
uniform vec4 Scan;
in vec2 texCoord;
out vec4 fragColor;

#moj_import <xaeroearth:terrain_scan.glsl>

void main() {
    fragColor = scanColor(texCoord);
}
