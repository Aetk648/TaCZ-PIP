#version 330 core
// 时域累积结果输出：1:1 取累积缓冲，再做与源像素尺度一致的边缘自适应锐化。
// 锐化半径按源像素计（= Zoom 个缓冲像素），邻域 min/max 钳制，不产生白边。
uniform sampler2D Acc;
uniform sampler2D Mask;
uniform vec2 Texel;        // 1 / 累积缓冲像素尺寸
uniform float Zoom;
uniform float Sharpness;
in vec2 uv;
layout(location = 0) out vec4 fragColor;

void main() {
    if (texture(Mask, uv).r < 0.5) discard;
    vec3 base = texture(Acc, uv).rgb;
    vec2 r = Texel * Zoom;
    vec3 lo = base;
    vec3 hi = base;
    vec3 lp = vec3(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            if (i == 0 && j == 0) continue;
            vec3 t = texture(Acc, uv + vec2(float(i), float(j)) * r).rgb;
            lo = min(lo, t);
            hi = max(hi, t);
            lp += t;
        }
    }
    lp /= 8.0;
    fragColor = vec4(clamp(base + (base - lp) * Sharpness, lo, hi), 1.0);
}
