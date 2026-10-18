#version 330 core
// 镜内超分辨率重建：Catmull-Rom 双三次插值 + 8 邻域自适应锐化（CAS 同族思路）。
// 线性渐变上输出与源画面逐通道一致（平滑区不产生新边缘、不闪烁）；
// 只有方块/纹理硬边缘处提升局部对比，结果钳制在邻域 min/max 内，不出现白边光晕。
uniform sampler2D Scene;
uniform sampler2D Mask;
uniform vec2 Center;
uniform float Zoom;
uniform vec2 Texel;      // 1 / Scene 像素尺寸
uniform float Sharpness; // 0 = 仅双三次插值
uniform vec3 FogColor;   // 来源里"没画到"的像素（精确为 0）用雾色顶上
in vec2 uv;
layout(location = 0) out vec4 fragColor;

// Catmull-Rom 权重，对应基向量为 floor(px) 时的 -1/0/+1/+2 四个采样点。
vec4 crw(float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return vec4(-0.5 * t3 + t2 - 0.5 * t,
                1.5 * t3 - 2.5 * t2 + 1.0,
               -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                0.5 * t3 - 0.5 * t2);
}

// 4x4 可分离双三次采样：16 次取样，权重和恒为 1，线性函数上精确复现。
vec3 bicubic(vec2 s) {
    vec2 px = s / Texel - 0.5;
    vec2 b = floor(px);
    vec2 f = px - b;
    vec4 wx = crw(f.x);
    vec4 wy = crw(f.y);
    vec2 p0 = (b - 0.5) * Texel;
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 4; j++) {
        vec3 row = vec3(0.0);
        float y = p0.y + float(j) * Texel.y;
        for (int i = 0; i < 4; i++) {
            row += wx[i] * texture(Scene, vec2(p0.x + float(i) * Texel.x, y)).rgb;
        }
        sum += wy[j] * row;
    }
    return sum;
}

void main() {
    // 遮罩来自 TaCZ 实际模板，镜框和镜外像素不写入。
    if (texture(Mask, uv).r < 0.5) discard;
    // 采样映射：源画面的中心（= 相机光轴）对到分划板 Center 上，再按 Zoom 放大。
    // Zoom=1 时就退化成"整体平移"，画面一个像素对应屏幕一个像素（不插值、不模糊）；
    // Zoom>1 时以分划板为圆心缩小取样范围，也就是光学放大。
    // 取样点钳在画面内（半个像素的边）：缩放/平移之后落到画面外的那一窄条
    // 取边缘内容，绝不会因为取样越界而露出别处画面或一圈死黑。
    vec2 halfTexel = 0.5 / vec2(textureSize(Scene, 0));
    vec2 raw = vec2(0.5) + (uv - Center) / Zoom;
    vec2 s = clamp(raw, halfTexel, vec2(1.0) - halfTexel);
    vec3 base = bicubic(s);
    // 取样点落到画面外时用雾色填实，后坐力把镜片圆心拉开也不会露出原视野。
    if (raw.x < 0.0 || raw.x > 1.0 || raw.y < 0.0 || raw.y > 1.0) {
        fragColor = vec4(FogColor, 1.0);
        return;
    }
    // 8 邻域（源画面 1 像素半径）：低通参考 + 局部 min/max。
    vec3 lo = base;
    vec3 hi = base;
    vec3 lp = vec3(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            if (i == 0 && j == 0) continue;
            vec3 t = texture(Scene, s + vec2(float(i), float(j)) * Texel).rgb;
            lo = min(lo, t);
            hi = max(hi, t);
            lp += t;
        }
    }
    lp /= 8.0;
    // 非锐化掩蔽：平滑区 base≈lp 无变化；边缘处把被插值抹掉的对比度推回去。
    vec3 sharp = clamp(base + (base - lp) * Sharpness, lo, hi);
    // 来源里精确为 0 的像素意味着"那一遍没画到这里"：用雾色顶上，镜内不会出现死黑块。
    if (max(max(sharp.r, sharp.g), sharp.b) < (1.0 / 1024.0)) sharp = FogColor;
    fragColor = vec4(sharp, 1.0);
}
