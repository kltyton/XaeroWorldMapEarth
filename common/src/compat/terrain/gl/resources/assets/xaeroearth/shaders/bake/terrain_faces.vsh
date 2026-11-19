#version 150
uniform usamplerBuffer FaceVertices;
flat out uvec4 VertexHead;
flat out uvec4 VertexTail;

void main() {
    const int quadVertex[6] = int[6](0, 1, 2, 2, 3, 0);
    int source = (gl_VertexID / 6 * 4 + quadVertex[gl_VertexID % 6]) * 2;
    VertexHead = texelFetch(FaceVertices, source);
    VertexTail = texelFetch(FaceVertices, source + 1);
    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
}
