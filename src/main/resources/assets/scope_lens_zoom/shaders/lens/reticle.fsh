#version 330 core
uniform sampler2D Reticle;
uniform sampler2D Mask;
uniform vec2 OutputSize;
in vec2 uv;
in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;
void main() {
    if (texture(Mask, gl_FragCoord.xy / OutputSize).r < 0.5) discard;
    vec4 color = texture(Reticle, uv) * vertexColor;
    if (color.a < 0.1) discard;
    fragColor = color;
}
