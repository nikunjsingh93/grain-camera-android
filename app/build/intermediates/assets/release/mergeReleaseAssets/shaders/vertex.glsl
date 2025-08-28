attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vec4 tc = vec4(aTexCoord, 0.0, 1.0);
    tc = uTexMatrix * tc;
    vTexCoord = tc.xy;
}
