package com.i50208.taczpip.client;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class LensZoomConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue LENS_ZOOM_ENABLED;
    public static final ModConfigSpec.BooleanValue PIN_SCOPE_FRAME;
    public static final ModConfigSpec.DoubleValue PINNED_HAND_FOV;
    public static final ModConfigSpec.DoubleValue LENS_SHARPNESS;
    public static final ModConfigSpec.BooleanValue TEMPORAL_SR;
    public static final ModConfigSpec.BooleanValue SHADERS_LENS_CAPTURE;
    public static final ModConfigSpec.BooleanValue SHADERS_LENS_ENTITIES;
    public static final ModConfigSpec.EnumValue<CaptureMode> LENS_CAPTURE_MODE;

    /** 开光影时镜内画面走哪条路；AUTO 表示开局自动挑一条画得出地形的。 */
    public enum CaptureMode {
        AUTO,
        ISOLATED_REPAIRED,
        ISOLATED_REFRESH,
        SHADER_LIT,
        MAGNIFY
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("lenszoom");
        LENS_ZOOM_ENABLED = builder
                .comment(
                        "Render the zoomed picture ONLY inside the scope lens; the world outside the scope",
                        "keeps the normal (vanilla) field of view, and the whole screen is never zoomed.",
                        "The in-lens magnification follows the scope attachment data of each gun pack,",
                        "and changes together with the zoom level you switch to.",
                        "仅在瞄准镜镜片内放大画面（镜外保持原版视野，绝不整屏拉近），镜内倍率跟随瞄具数据与倍率调节。",
                        "Set false to fall back to TaCZ's original whole-screen scope zoom."
                )
                .define("enableLensZoom", true);
        PIN_SCOPE_FRAME = builder
                .comment(
                        "Keep the scope frame the same on-screen size no matter which zoom level is selected,",
                        "so a 20x scope does not fill the whole screen.",
                        "开镜瞄准框在屏幕上始终只占中心一圈，不随 10x/20x 倍率放大到铺满整个屏幕。"
                )
                .define("pinScopeFrameSize", true);
        PINNED_HAND_FOV = builder
                .comment(
                        "Hand-item FOV used while aiming. This only moves the scope model closer to the eye",
                        "(bigger frame on screen); it never changes the world / camera FOV.",
                        "Smaller = scope closer to the eye (bigger circle). Vanilla first-person FOV is ~70.",
                        "开镜时手持瞄具使用的视野，只把枪模型往眼睛方向拉近（镜框更大），不改世界视角。",
                        "数值越小，镜框越贴近眼睛、在屏幕上越大。默认 40。"
                )
                .defineInRange("pinnedHandFov", 40.0, 15.0, 90.0);
        LENS_SHARPNESS = builder
                .comment(
                        "Super-resolution reconstruction strength inside the lens while shaders are active.",
                        "The lens picture is rebuilt from the shader's final image with a bicubic filter plus",
                        "edge-adaptive sharpening: smooth areas stay untouched, block edges become crisp.",
                        "0 = bicubic only (soft); 1.2 = default; above ~1.6 can look harsh on some shader packs.",
                        "光影下镜内画面的超分辨率重建强度：双三次插值 + 边缘自适应锐化。",
                        "平滑区域不受影响，只把被拉糊的边缘重新锐化；0 为最柔和，默认 1.2，觉得刺眼可调低。"
                )
                .defineInRange("lensSharpness", 1.2, 0.0, 2.0);
        TEMPORAL_SR = builder
                .comment(
                        "Temporal super-resolution inside the TaCZ scope while shaders are on.",
                        "Each frame's lens picture is accumulated into a dedicated buffer, then aligned",
                        "with the previous frames using the real camera rotation. Tiny mouse/camera",
                        "jitter therefore reconstructs extra detail that a single frame does not contain.",
                        "Turn off if you see smearing on fast panning; the spatial bicubic path still runs.",
                        "光影下瞄准镜专用时域超分辨率：把多帧镜内画面按相机真实旋转对齐后累积，",
                        "高倍镜看远处会随帧数越来越清晰。觉得拖影就把 temporalSuperResolution 关掉。"
                )
                .define("temporalSuperResolution", true);
        SHADERS_LENS_CAPTURE = builder
                .comment(
                        "With shaders on, render the inside of the scope by re-rendering the world at the",
                        "narrowed lens FOV into a private buffer with Iris's shading pipeline temporarily",
                        "isolated for that pass only (terrain via Sodium's own unshaded shader, entities,",
                        "sky, weather and Voxy LOD all included). The picture outside the scope keeps the",
                        "shader pack fully active. This is what makes 6x/8x lenses sharp instead of a",
                        "magnified blurry screenshot. If isolation ever fails, the mod falls back to the",
                        "old 'magnify the shader final image' path automatically.",
                        "开光影时镜内画面的正确做法：镜内那一遍临时把 Iris 的着色管线隔离成无光影状态，",
                        "用镜片小视野完整重渲染世界（含 Sodium 区块、Voxy 超视距 LOD、实体、天空、天气），",
                        "6x/8x 高倍镜不糊；镜外主画面光影完全不受影响。隔离失败会自动退回旧的最终画面放大。"
                )
                .define("shadersLensCapture", true);
        SHADERS_LENS_ENTITIES = builder
                .comment(
                        "While shaders are on, draw the entities inside the scope a second time by the mod",
                        "itself, with the vanilla entity shaders and the scene depth that the shader pass",
                        "already produced. This is what guarantees that mobs, item drops, armour stands and",
                        "the target dummies are visible through the lens even when the shader pack's own",
                        "pipeline drops them. Turn off if you prefer the shader pack's own entity look and",
                        "the lens already shows entities for you.",
                        "开光影时，镜内额外单独补画一遍实体（原版实体着色器 + 光影那一遍已经画好的地形深度）。",
                        "这是“镜内一定能看见实体”的保险：光影自己的管线丢实体也不影响。",
                        "如果你觉得光影自己那套实体观感更好、镜内也看得见实体，可以关掉它。"
                )
                .define("shadersLensEntities", true);
        LENS_CAPTURE_MODE = builder
                .comment(
                        "How the inside of the lens is produced while shaders are on.",
                        "AUTO (default): on the first aim of a session every candidate path is drawn once and",
                        "measured on the GPU; the first one that really contains terrain is kept. This is what",
                        "makes 6x/8x lenses sharp instead of a magnified blurry screenshot.",
                        "ISOLATED_REPAIRED / ISOLATED_REFRESH: force the 'lens pass renders the vanilla world'",
                        "paths (REFRESH additionally rebuilds the chunk visibility lists for that pass).",
                        "SHADER_LIT: lens shows the shader pack's own small-FOV render (sharp, shader colours).",
                        "MAGNIFY: old fallback, magnify the shader final image (never black, but blurry at high zoom).",
                        "开光影时镜内画面怎么来。AUTO（默认）= 开局把几条路线各画一遍并当场读回像素体检，",
                        "挑第一条真的画得出地形的路线固定使用；也可以手动写死：",
                        "ISOLATED_REPAIRED / ISOLATED_REFRESH = 镜内按“无光影”重渲染世界（后者还会重建区块列表）；",
                        "SHADER_LIT = 镜内直接放光影自己渲染的小视野画面（清晰、带光影色调）；",
                        "MAGNIFY = 旧的放大最终画面（一定不黑，但高倍会糊）。"
                )
                .defineEnum("lensCaptureMode", CaptureMode.AUTO);
        builder.pop();
        SPEC = builder.build();
    }

    private LensZoomConfig() {
    }

    public static boolean enabled() {
        return LENS_ZOOM_ENABLED.get();
    }

    public static boolean pinScopeFrame() {
        return PIN_SCOPE_FRAME.get();
    }

    public static float pinnedHandFov() {
        return PINNED_HAND_FOV.get().floatValue();
    }

    public static float lensSharpness() {
        return LENS_SHARPNESS.get().floatValue();
    }

    public static boolean temporalSR() {
        return TEMPORAL_SR.get();
    }

    public static boolean shadersLensCapture() {
        return SHADERS_LENS_CAPTURE.get();
    }

    public static boolean shadersLensEntities() {
        return SHADERS_LENS_ENTITIES.get();
    }

    public static CaptureMode captureMode() {
        return LENS_CAPTURE_MODE.get();
    }
}
