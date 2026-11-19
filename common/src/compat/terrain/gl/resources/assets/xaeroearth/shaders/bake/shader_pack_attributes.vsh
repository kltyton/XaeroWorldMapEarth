#version 330
uniform usamplerBuffer TerrainVertices;
uniform isamplerBuffer MaterialIds;
uniform int MaterialCount;
uniform int VertexCount;
flat out uvec2 LightAndColor;
flat out vec2 SpriteCenter;
flat out uvec2 TangentAndMidBlock;
flat out vec2 Material;

uint signedBytes(vec4 value) {
    ivec4 bits = ivec4(round(clamp(value, -127.0, 127.0)));
    uvec4 bytes = uvec4(bits) & 255u;
    return bytes.x | (bytes.y << 8u) | (bytes.z << 16u) | (bytes.w << 24u);
}

void main() {
    int vertex = gl_VertexID;
    int first = vertex - vertex % 6;
    uvec4 head = texelFetch(TerrainVertices, vertex * 2);
    uvec4 tail = texelFetch(TerrainVertices, vertex * 2 + 1);
    uvec4 a = texelFetch(TerrainVertices, first * 2 + 1);
    uvec4 b = texelFetch(TerrainVertices, min(first + 1, VertexCount - 1) * 2 + 1);
    uvec4 c = texelFetch(TerrainVertices, min(first + 2, VertexCount - 1) * 2 + 1);
    uvec4 d = texelFetch(TerrainVertices, min(first + 4, VertexCount - 1) * 2 + 1);
    uint source = (a.w >> 24u) | ((b.w >> 24u) << 8u) | ((c.w >> 24u) << 16u) | ((d.w >> 24u) << 24u);
    int state = int(source >> 1u) - 1;
    bool hasSource = state >= 0;
    uint blockLight = (tail.z >> (hasSource ? 12u : 0u)) & 15u;
    uint skyLight = (tail.z >> (hasSource ? 28u : 16u)) & 15u;
    LightAndColor.x = (blockLight << 4u) | (skyLight << 20u);
    LightAndColor.y = (head.w & 0x00ffffffu) | (((tail.z >> 4u) & 255u) << 24u);
    ivec2 material = state >= 0 && state < MaterialCount ? texelFetch(MaterialIds, state).rg : ivec2(-1, 0);
    Material = vec2(float(material.r), float(source & 1u));

    vec2 u0 = uintBitsToFloat(a.xy), u1 = uintBitsToFloat(b.xy), u2 = uintBitsToFloat(c.xy);
    SpriteCenter = (u0 + u1 + u2 + uintBitsToFloat(d.xy)) * 0.25;
    vec3 p0 = uintBitsToFloat(texelFetch(TerrainVertices, first * 2).xyz);
    vec3 p1 = uintBitsToFloat(texelFetch(TerrainVertices, min(first + 1, VertexCount - 1) * 2).xyz);
    vec3 p2 = uintBitsToFloat(texelFetch(TerrainVertices, min(first + 2, VertexCount - 1) * 2).xyz);
    vec3 p3 = uintBitsToFloat(texelFetch(TerrainVertices, min(first + 4, VertexCount - 1) * 2).xyz);
    vec3 normal = vec3(ivec3(tail.w << 24u, tail.w << 16u, tail.w << 8u) >> 24);
    normal = dot(normal, normal) > 0.0 ? normalize(normal) : vec3(0.0, 1.0, 0.0);
    vec2 uv1 = u1 - u0, uv2 = u2 - u0;
    float determinant = uv1.x * uv2.y - uv1.y * uv2.x;
    vec3 tangent = (p1 - p0) * uv2.y - (p2 - p0) * uv1.y;
    vec3 bitangent = (p2 - p0) * uv1.x - (p1 - p0) * uv2.x;
    if (abs(determinant) > 1e-12 && dot(tangent, tangent) > 1e-12) {
        tangent = normalize(tangent) * sign(determinant);
        bitangent *= sign(determinant);
    } else {
        tangent = normalize(cross(abs(normal.y) > 0.9 ? vec3(1, 0, 0) : vec3(0, 1, 0), normal));
        bitangent = cross(normal, tangent);
    }
    float handedness = dot(cross(normal, tangent), bitangent) < 0.0 ? -1.0 : 1.0;
    TangentAndMidBlock.x = signedBytes(vec4(tangent, handedness) * 127.0);
    vec3 center = floor((p0 + p1 + p2 + p3) * 0.25 - normal * 0.001) + vec3(0.5);
    TangentAndMidBlock.y = signedBytes(vec4((center - uintBitsToFloat(head.xyz)) * 64.0, float(material.g)));
    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
}
