#version 330
uniform usamplerBuffer TerrainVertices;
uniform isamplerBuffer MaterialIds;
uniform int MaterialCount;
uniform int VertexCount;
uniform int VerticesPerFace;
flat out uvec4 VertexHead;
flat out uvec4 VertexTail;
flat out uint Material;
flat out vec2 SpriteCenter;
flat out uint Tangent;
flat out uint MidBlock;

uint signedBytes(vec4 value) {
    ivec4 bits = ivec4(round(clamp(value, -127.0, 127.0)));
    uvec4 bytes = uvec4(bits) & 255u;
    return bytes.x | (bytes.y << 8u) | (bytes.z << 16u) | (bytes.w << 24u);
}

void main() {
    int vertex = gl_VertexID;
    int first = (vertex / VerticesPerFace) * VerticesPerFace;
    int fourth = min(first + (VerticesPerFace == 4 ? 3 : 4), VertexCount - 1);
    uvec4 a = texelFetch(TerrainVertices, first * 2 + 1);
    uvec4 b = texelFetch(TerrainVertices, min(first + 1, VertexCount - 1) * 2 + 1);
    uvec4 c = texelFetch(TerrainVertices, min(first + 2, VertexCount - 1) * 2 + 1);
    uvec4 d = texelFetch(TerrainVertices, fourth * 2 + 1);
    uint source = (a.w >> 24u) | ((b.w >> 24u) << 8u) | ((c.w >> 24u) << 16u) | ((d.w >> 24u) << 24u);
    int state = int(source >> 1u) - 1;
    bool hasSource = state >= 0;
    VertexHead = texelFetch(TerrainVertices, vertex * 2);
    VertexTail = texelFetch(TerrainVertices, vertex * 2 + 1);
    uint packedLight = VertexTail.z;
    uint block = (packedLight >> (hasSource ? 12u : 0u)) & 15u;
    uint sky = (packedLight >> (hasSource ? 28u : 16u)) & 15u;
    VertexHead.w = (VertexHead.w & 0x00ffffffu) | (((packedLight >> 4u) & 255u) << 24u);
    VertexTail.z = (block << 4u) | (sky << 20u);
    VertexTail.w &= 0x00ffffffu;
    int material = state >= 0 && state < MaterialCount ? texelFetch(MaterialIds, state).r : -1;
    Material = (uint(material) & 65535u) | ((source & 1u) << 16u);

    vec2 u0 = uintBitsToFloat(a.xy), u1 = uintBitsToFloat(b.xy), u2 = uintBitsToFloat(c.xy);
    SpriteCenter = (u0 + u1 + u2 + uintBitsToFloat(d.xy)) * 0.25;
    vec3 p0 = uintBitsToFloat(texelFetch(TerrainVertices, first * 2).xyz);
    vec3 p1 = uintBitsToFloat(texelFetch(TerrainVertices, min(first + 1, VertexCount - 1) * 2).xyz);
    vec3 p2 = uintBitsToFloat(texelFetch(TerrainVertices, min(first + 2, VertexCount - 1) * 2).xyz);
    vec3 p3 = uintBitsToFloat(texelFetch(TerrainVertices, fourth * 2).xyz);
    vec3 normal = vec3(ivec3(VertexTail.w << 24u, VertexTail.w << 16u, VertexTail.w << 8u) >> 24);
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
    Tangent = signedBytes(vec4(tangent, handedness) * 127.0);
    vec3 center = floor((p0 + p1 + p2 + p3) * 0.25 - normal * 0.001) + vec3(0.5);
    ivec3 offset = ivec3((center - uintBitsToFloat(VertexHead.xyz)) * 64.0);
    uvec3 bytes = uvec3(offset) & 255u;
    uint emission = state >= 0 && state < MaterialCount ? (uint(material) >> 16u) & 255u : 0u;
    MidBlock = bytes.x | (bytes.y << 8u) | (bytes.z << 16u) | (emission << 24u);
    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
}
