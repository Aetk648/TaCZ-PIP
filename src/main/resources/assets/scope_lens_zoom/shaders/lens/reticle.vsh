#version 330 core
layout(location = 0) in vec4 ClipPosition;
layout(location = 1) in vec2 UV;
layout(location = 2) in vec4 Color;
out vec2 uv;
out vec4 vertexColor;
void main() {
    gl_Position = ClipPosition;
    uv = UV;
    vertexColor = Color;
}
