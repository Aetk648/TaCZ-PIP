#version 330 core
// 镜内画面填充（不开光影时用）。
//
// 整屏四边形，靠模板测试（TaCZ 的镜片模板）限制范围：一像素对一像素搬进镜片，
// 就是最清晰的光学放大。
//
// 不再支持"自己算一个圆去补模板"的那种画法：那个圆是靠目镜节点的屏幕投影算出来的，
// 收镜/后坐力那几帧会算歪，屏幕上会糊出一个巨大的黑圆盘（实机截图 2716）。
// 镜内边界一律以 TaCZ 自己的模板为准。
uniform sampler2D Scene;
uniform vec3 FogColor;
uniform vec2 Center;   // 分划板（镜片光学中心）在屏幕上的位置 0~1
uniform vec2 Radius;
in vec2 uv;
layout(location = 0) out vec4 fragColor;

void main() {
    if (Radius.x > 0.0 && Radius.y > 0.0) {
        vec2 d = (uv - Center) / Radius;
        if (dot(d, d) > 1.0) discard;
    }
    // 画面中心对到分划板上：小视野那一遍是以屏幕中心（相机光轴）为准渲染的，
    // 而后坐力动画里镜片圆心/分划板会离开屏幕中心。Center = 分划板屏幕位置 0~1，
    // 于是 (0.5 - Center) 就是把光轴那一点搬到分划板上的平移量。
    //
    // 平移量再小，屏幕边上也一定会有一窄条取样点落到画面外；那边界一旦不处理，
    // 露出来的就是别处的内容或者一圈死黑 —— 实机里看到的"镜内后面套着一圈空白"。
    // 所以这里把取样点钳在画面内（半个像素的边），落到边上的像素取边缘内容，
    // 任何时候都不会出现没有内容的一圈。
    vec2 halfTexel = 0.5 / vec2(textureSize(Scene, 0));
    vec2 raw = uv + (vec2(0.5) - Center);
    vec2 s = clamp(raw, halfTexel, vec2(1.0) - halfTexel);
    vec3 color = texture(Scene, s).rgb;
    // 来源里精确为 0 的像素意味着"那一遍没画到这里"：用雾色顶上，镜内不会出现死黑块。
    if (max(max(color.r, color.g), color.b) < (1.0 / 1024.0)) color = FogColor;
    // 取样点落到画面外（后坐力把镜片圆心拉出屏幕中心）时，边缘那一圈也用雾色填实，
    // 绝不会透出没放大的原视野 —— 那就是"开枪后一圈透明"。
    if (raw.x < 0.0 || raw.x > 1.0 || raw.y < 0.0 || raw.y > 1.0) color = FogColor;
    fragColor = vec4(color, 1.0);
}
