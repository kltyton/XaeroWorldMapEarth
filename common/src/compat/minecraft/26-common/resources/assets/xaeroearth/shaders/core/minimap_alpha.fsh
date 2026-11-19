#version 330
out vec4 fragColor;
void main() {
    gl_FragDepth = EARTH_FAR_DEPTH;
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
