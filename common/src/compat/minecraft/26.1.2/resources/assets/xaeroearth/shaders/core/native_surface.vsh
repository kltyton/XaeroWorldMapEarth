#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
uniform sampler2D SurfaceColor;
uniform sampler2D SurfaceHeight;
uniform sampler2D Coverage;
layout(std140) uniform SurfaceParams { vec4 Grid; vec4 CoverageRegion; vec4 SurfaceOrigin; };
out vec3 localPosition;
flat out ivec2 outlineCell;
out float outlineDepth;
const ivec2 CORNERS[6] = ivec2[6](ivec2(0, 0), ivec2(0, 1), ivec2(1, 1), ivec2(0, 0), ivec2(1, 1), ivec2(1, 0));
float heightAt(ivec2 cell) {
    uvec4 bytes = uvec4(round(texelFetch(SurfaceHeight, cell, 0) * 255.0));
    return uintBitsToFloat(bytes.r | (bytes.g << 8u) | (bytes.b << 16u) | (bytes.a << 24u));
}
float cornerHeight(ivec2 corner) {
    ivec2 last = ivec2(Grid.xy) - 1;
    float sum = 0.0;
    float samples = 0.0;
    for (int z = -1; z <= 0; z++) {
        for (int x = -1; x <= 0; x++) {
            ivec2 sampleCell = clamp(corner + ivec2(x, z), ivec2(0), last);
            if (texelFetch(SurfaceColor, sampleCell, 0).a == 0.0) continue;
            sum += heightAt(sampleCell);
            samples += 1.0;
        }
    }
    return sum / samples;
}
const ivec2 EDGE_START[4] = ivec2[4](ivec2(0, 0), ivec2(0, 1), ivec2(1, 1), ivec2(1, 0));
const ivec2 EDGE_END[4] = ivec2[4](ivec2(0, 1), ivec2(1, 1), ivec2(1, 0), ivec2(0, 0));
const ivec2 EDGE_VERTICES[6] = ivec2[6](ivec2(0, 0), ivec2(0, 1), ivec2(1, 1), ivec2(0, 0), ivec2(1, 1), ivec2(1, 0));
bool detailed(ivec2 surfaceCell) {
    vec2 worldXZ = SurfaceOrigin.xz + (vec2(surfaceCell) + 0.5) * Grid.z;
    ivec2 cell = ivec2(floor((worldXZ + CoverageRegion.xy) / 16.0));
    ivec2 size = textureSize(Coverage, 0);
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, size))) return false;
    return texelFetch(Coverage, cell, 0).r > 0.75;
}
float detailedEdgeHeight(ivec2 corner, ivec2 inside, int edge) {
    ivec2 tangent = edge % 2 == 0 ? ivec2(0, 1) : ivec2(1, 0);
    ivec2 second = edge % 2 == 0 ? ivec2(inside.x, corner.y) : ivec2(corner.x, inside.y);
    ivec2 size = ivec2(Grid.xy);
    float height = 0.0;
    float samples = 0.0;
    for (int i = 0; i < 2; i++) {
        ivec2 p = second - tangent * i;
        if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size))) continue;
        if (texelFetch(SurfaceColor, p, 0).a == 0.0 || !detailed(p)) continue;
        height += heightAt(p);
        samples += 1.0;
    }
    return samples == 0.0 ? heightAt(inside) : height / samples;
}
void main() {
    int cell = gl_VertexID / 6;
    int columns = int(Grid.x);
    int rows = int(Grid.y);
    outlineCell = ivec2(-1);
    outlineDepth = 0.0;
    if (cell >= columns * rows) {
        int entry = cell - columns * rows;
        ivec2 address = ivec2(entry % columns, rows + entry / columns);
        int edgeCode = int(heightAt(address));
        int sampleIndex = edgeCode / 4;
        int edge = edgeCode % 4;
        outlineCell = ivec2(sampleIndex % columns, sampleIndex / columns);
        bool join = cell >= int(CoverageRegion.w);
        ivec2 inside = outlineCell;
        if (join) {
            ivec2 opposite = outlineCell + (edge == 2 ? ivec2(1, 0) : ivec2(0, 1));
            bool ownerDetailed = detailed(outlineCell);
            if (ownerDetailed == detailed(opposite)) {
                localPosition = vec3(0.0);
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                return;
            }
            if (ownerDetailed) {
                outlineCell = opposite;
                edge = (edge + 2) % 4;
            } else inside = opposite;
        }
        ivec2 vertex = EDGE_VERTICES[gl_VertexID % 6];
        ivec2 start = outlineCell + EDGE_START[edge];
        ivec2 end = outlineCell + EDGE_END[edge];
        ivec2 corner;
        float height;
        if (join) {
            vec2 coarse = vec2(cornerHeight(start), cornerHeight(end));
            vec2 fine = vec2(detailedEdgeHeight(start, inside, edge), detailedEdgeHeight(end, inside, edge));
            // Face the lower side. Equal surfaces give a zero-height strip, including level water.
            bool reverse = fine.x + fine.y > coarse.x + coarse.y;
            int endpoint = reverse ? 1 - vertex.x : vertex.x;
            corner = endpoint == 0 ? start : end;
            height = vertex.y == 0 ? max(coarse[endpoint], fine[endpoint]) : min(coarse[endpoint], fine[endpoint]);
            outlineDepth = -1.0 - float(vertex.y);
        } else {
            corner = vertex.x == 0 ? start : end;
            height = cornerHeight(corner);
            outlineDepth = float(vertex.y);
            float faceHeight = clamp(Grid.z * 2.0 + 3.0, 5.0, 14.0);
            if (vertex.y == 1) height = min(height, max(SurfaceOrigin.w, height - faceHeight));
        }
        localPosition = vec3(float(corner.x) * Grid.z, height, float(corner.y) * Grid.z) + SurfaceOrigin.xyz;
        gl_Position = ProjMat * ModelViewMat * vec4(localPosition, 1.0);
        return;
    }
    int sampleIndex = code >> 4;
    int span = 1 << (code & 15);
    ivec2 sampleCell = ivec2(sampleIndex % columns, sampleIndex / columns);
    ivec2 corner = sampleCell + CORNERS[gl_VertexID % 6] * span;
    localPosition = vec3(float(corner.x) * Grid.z, cornerHeight(corner), float(corner.y) * Grid.z) + SurfaceOrigin.xyz;
    gl_Position = ProjMat * ModelViewMat * vec4(localPosition, 1.0);
}
