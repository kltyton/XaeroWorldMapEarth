#version 150
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform sampler2D SurfaceColor;
uniform sampler2D SurfaceHeight;
uniform vec4 Grid;
uniform vec4 CoverageRegion;
uniform vec4 SurfaceOrigin;
out vec3 localPosition;
const ivec2 CORNERS[6] = ivec2[6](ivec2(0, 0), ivec2(0, 1), ivec2(1, 1), ivec2(0, 0), ivec2(1, 1), ivec2(1, 0));
float cornerHeight(ivec2 corner) {
    ivec2 last = ivec2(Grid.xy) - 1;
    float sum = 0.0;
    float samples = 0.0;
    for (int z = -1; z <= 0; z++) {
        for (int x = -1; x <= 0; x++) {
            ivec2 sampleCell = clamp(corner + ivec2(x, z), ivec2(0), last);
            if (texelFetch(SurfaceColor, sampleCell, 0).a == 0.0) continue;
            sum += texelFetch(SurfaceHeight, sampleCell, 0).r;
            samples += 1.0;
        }
    }
    return sum / samples;
}
void main() {
    int cell = gl_VertexID / 6;
    ivec2 sampleCell = ivec2(cell % int(Grid.x), cell / int(Grid.x));
    if (texelFetch(SurfaceColor, sampleCell, 0).a == 0.0) {
        localPosition = vec3(0.0);
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    ivec2 corner = sampleCell + CORNERS[gl_VertexID % 6];
    localPosition = vec3(float(corner.x) * Grid.z, cornerHeight(corner), float(corner.y) * Grid.z) + SurfaceOrigin.xyz;
    gl_Position = ProjMat * ModelViewMat * vec4(localPosition, 1.0);
}
