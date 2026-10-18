#version 330 core
// 时域超分辨率累积（帧重建）：把本帧镜内观测和上一帧累积缓冲按相机运动对齐后融合。
// HistA/HistB 由 Java 侧用真实的世界投影矩阵算出：hist = A·uv + B 指向
// "当前显示在 uv 的内容，上一帧画在屏幕的哪里"。旋转对齐后多帧的亚像素采样可以拼进
// 同一块缓冲，有效分辨率随帧数提高。历史值先钳制到本帧观测的局部色域内再融合，
// 钳制修正越大说明内容变了（新物体、开火火光、快速平移），就越倾向直接采用本帧，避免拖影。
// 镜框外不 discard：写入本帧观测，保证镜框边缘锐化取样时读到的是连续内容。
uniform sampler2D Scene;      // 光影最终画面
uniform sampler2D Mask;       // TaCZ 镜片模板
uniform sampler2D Hist;       // 上一帧累积缓冲（双线性）
uniform vec2 Center;
uniform float Zoom;
uniform vec2 Texel;           // 1 / Scene 像素尺寸
uniform vec2 BufferSize;      // 累积缓冲像素尺寸
uniform vec4 HistA;           // 屏幕空间仿射映射的 2x2 矩阵，列主序 a00,a10,a01,a11
uniform vec2 HistB;           // 仿射平移
uniform float Reset;          // >0.5：历史无效，本帧观测直接作为新历史
in vec2 uv;
layout(location = 0) out vec4 fragColor;

// Catmull-Rom 权重（与 zoom.fsh 相同的重建核）
vec4 crw(float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return vec4(-0.5 * t3 + t2 - 0.5 * t,
                1.5 * t3 - 2.5 * t2 + 1.0,
               -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                0.5 * t3 - 0.5 * t2);
}

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
    // 采样映射：源画面的中心（= 相机光轴）对到分划板 Center 上，再按 Zoom 放大。
    // Zoom=1 时就退化成"整体平移"，画面一个像素对应屏幕一个像素（不插值、不模糊）；
    // Zoom>1 时以分划板为圆心缩小取样范围，也就是光学放大。
    // 取样点钳在画面内（半个像素的边）：缩放/平移之后落到画面外的那一窄条
    // 取边缘内容，绝不会因为取样越界而露出别处画面或一圈死黑。
    vec2 halfTexel = 0.5 / vec2(textureSize(Scene, 0));
    vec2 s = clamp(vec2(0.5) + (uv - Center) / Zoom, halfTexel, vec2(1.0) - halfTexel);
    vec3 obs = bicubic(s);
    vec2 hu = vec2(HistA.x * uv.x + HistA.z * uv.y,
                   HistA.y * uv.x + HistA.w * uv.y) + HistB;
    bool inside = texture(Mask, uv).r > 0.5;
    if (Reset > 0.5 || !inside || hu.x < 0.0 || hu.x > 1.0 || hu.y < 0.0 || hu.y > 1.0
            || texture(Mask, hu).r < 0.5) {
        fragColor = vec4(obs, 1.0);
        return;
    }
    vec3 hist = texture(Hist, hu).rgb;
    // 本帧观测在源像素 3x3 邻域内的色域，作为历史值的合法范围。
    vec3 lo = obs;
    vec3 hi = obs;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec3 c = texture(Scene, s + vec2(float(i), float(j)) * Texel).rgb;
            lo = min(lo, c);
            hi = max(hi, c);
        }
    }
    // 缓冲内漂移（像素）。允许的色域余量随漂移缓慢放宽，吸收残余对齐误差。
    float drift = length((hu - uv) * BufferSize);
    float margin = 0.02 + drift / (Zoom * 24.0);
    vec3 hc = clamp(hist, lo - margin, hi + margin);
    // 新观测权重：静止时多攒几帧；平移越快、钳制修正越大，越立即跟随本帧。
    float conf = clamp(0.28 + drift / (Zoom * 4.0), 0.28, 1.0);
    float jump = max(max(abs(hc.r - hist.r), abs(hc.g - hist.g)), abs(hc.b - hist.b));
    conf = max(conf, min(jump * 5.0, 1.0));
    fragColor = vec4(mix(hc, obs, conf), 1.0);
}
