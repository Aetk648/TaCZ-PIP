package com.i50208.taczpip.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 光影最终画面的局部放大。不重入 Iris，不修改主相机，不使用共享顶点批次或矩阵栈。
 * 镜片边界直接来自 TaCZ 的模板测试（目镜模型和开镜圆的交集），不是估算的屏幕圆。
 * 开启时域重建后：镜内观测逐帧写进 RGBA16F 累积缓冲，历史按相机真实旋转做重投影对齐，
 * 多帧的亚像素信息拼进同一张画面 —— 相机只要有微小移动，镜内分辨率就随帧数增长。
 */
public final class ShaderLensCompositor {
    private static final Logger LOGGER = LoggerFactory.getLogger("scope_lens_zoom");
    private static final List<Lens> LENSES = new ArrayList<>();
    private static final Quaternionf PREVIOUS_ROT = new Quaternionf();
    private static int count;
    private static boolean composed;
    private static int maskProgram;
    private static int zoomProgram;
    private static int tsrProgram;
    private static int presentProgram;
    private static int reticleProgram;
    private static int fillProgram;
    private static int fullscreenVao;
    private static int reticleVao;
    private static int reticleVbo;
    private static int frameTexture;
    private static int frameFbo;
    private static int frameWidth;
    private static int frameHeight;
    private static boolean warned;
    private static boolean logged;
    private static boolean initializationFailed;
    private static int missingFrames;
    private static boolean missingWarned;
    /** 上面那条"镜内丢实体"的日志只打一次。 */
    private static boolean entityLossWarned;
    private static boolean skipSeen;
    private static boolean prevValid;
    private static float prevP00;
    private static float prevP11;
    private static long frameCounter;
    private static java.nio.ByteBuffer stencilScratch;

    private ShaderLensCompositor() { }

    public static void beginFrame() {
        count = 0;
        composed = false;
        frameCounter++;
    }

    /** 离开光影镜内路径（关镜、换枪、关光影）时调用：下一帧从观测重新开始累积。 */
    public static void invalidateHistory() {
        skipSeen = true;
        prevValid = false;
        for (Lens lens : LENSES) {
            lens.resetPending = true;
            lens.prevValid = false;
            lens.lastZoom = 0F;
        }
    }

    /**
     * 在准星绘制前复制真实模板区域；返回成功才延后准星。
     * 原始深度/模板附件只读借用，用完立即从私有 FBO 脱离；不刷新 TaCZ 批次。
     */
    public static boolean capture(float[] reticle, int texture, float centerU, float centerV) {
        // 有光影 / 无光影都截：无光影改到 HUD 之前才贴图，必须先把模板和分划板留下来。
        if (!LensZoomState.lensFillAllowed() || count >= 8 || texture <= 0
                || reticle.length == 0 || initializationFailed) return false;
        RenderSystem.assertOnRenderThread();
        int source = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        // TaCZ 用"模板值 = 第几层目镜 + 1"标记镜内区域：瞄准镜走取反技巧后是 254，
        // 红点/全息这类没有取反圆的是 1、2、3……；只有 0 表示"镜外"。
        if (source == 0 || !GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
                || GL11.glGetInteger(GL11.GL_STENCIL_FUNC) != GL11.GL_EQUAL
                || stencilRef == 0 || GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK) != 255) {
            warn("没有找到 TaCZ 的镜片模板区域，保留原准星", null);
            return false;
        }
        try (ScopedGlState ignored = new ScopedGlState()) {
            initialize();
            ScopedGlState.prepareDraw();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, source);
            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                    GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int attachment = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                    GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int width;
            int height;
            int level = 0;
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (type == GL11.GL_TEXTURE && attachment > 0) {
                level = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                        GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, attachment);
                width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
            } else if (type == GL30.GL_RENDERBUFFER && attachment > 0) {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, attachment);
                width = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
                height = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
            } else {
                warn("当前光影手部目标没有模板附件，保留原准星", null);
                return false;
            }
            if (width <= 0 || height <= 0 || GL11.glGetInteger(GL13.GL_SAMPLES) > 0) {
                warn("镜片模板尺寸或多重采样格式不支持，保留原准星", null);
                return false;
            }
            // 光影路径才预先分配最终画面备份（退路放大要用）。无光影不走那条退路，
            // 这里失败不该连模板都截不成。
            if (LensZoomState.shaderCompositeActive()) {
                var main = Minecraft.getInstance().getMainRenderTarget();
                ensureFrame(main.width, main.height);
            }
            while (LENSES.size() <= count) LENSES.add(new Lens());
            Lens lens = LENSES.get(count);
            lens.resize(width, height);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lens.fbo);
            // TaCZ / NeoForge 的模板附件为打包深度模板；只读，不清理、不更改任何原始像素。
            if (type == GL11.GL_TEXTURE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                        GL11.GL_TEXTURE_2D, attachment, level);
            } else {
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                        GL30.GL_RENDERBUFFER, attachment);
            }
            try {
                checkFramebuffer();
                GL11.glViewport(0, 0, width, height);
                updateLensBounds(lens, width, height);
                GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{0, 0, 0, 0});
                GL11.glEnable(GL11.GL_STENCIL_TEST);
                GL11.glStencilMask(0);
                // 镜片总成 = 模板值不为 0 的所有像素：TaCZ 用"模板值 = 第几层目镜 + 1"
                // 标出目镜范围，再用取反圆在中间挖出一个"镜片圆"（瞄准镜是 254）。
                // 只贴那个内圆的话，圆外面那一圈目镜遮罩（TaCZ 画的黑色遮罩）就露在外面 ——
                // 也就是实机画面里"镜内套着一圈黑边/透明圈"。
                // 这里按"整个目镜"取，镜内就是一块完整画面，不再有那一圈。
                GL11.glStencilFunc(GL11.GL_NOTEQUAL, 0, 255);
                GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                GL20.glUseProgram(maskProgram);
                drawFullscreen();
            } finally {
                // 即使绘制失败，也不让下一帧清理私有纹理时误清理世界深度。
                if (type == GL11.GL_TEXTURE) {
                    GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                            GL11.GL_TEXTURE_2D, 0, 0);
                } else {
                    GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                            GL30.GL_RENDERBUFFER, 0);
                }
            }
            lens.vertices = reticle;
            lens.reticleTexture = texture;
            lens.centerU = Float.isFinite(centerU) ? Math.clamp(centerU, 0F, 1F) : 0.5F;
            lens.centerV = Float.isFinite(centerV) ? Math.clamp(centerV, 0F, 1F) : 0.5F;
            count++;
            return true;
        } catch (RuntimeException e) {
            warn("读取镜片模板失败，保留原准星", e);
            return false;
        }
    }

    /**
     * 不开光影时的镜内贴图：把自渲染的小视野画面 1:1 贴进镜片。
     *
     * <p>1:1 是这个方案的根：那张贴图已经是"镜片视野"的原始像素，屏幕像素与世界角度
     * 一一对应，原样搬运就是最清晰的光学放大，没有任何插值。
     *
     * <p>填充范围是 TaCZ 模板里"整个镜片总成"（模板值 != 0），不是一个像素都不多补的内圆：
     * 内圆之外还有 TaCZ 自己画的一圈黑色目镜遮罩，只贴内圆就会留下那一圈。
     * 但仍然不自己算任何形状（早先自算的遮罩圆靠目镜投影算，收镜/后坐力那几帧会算歪，
     * 糊出一个巨大黑盘 ）。边界一律以 TaCZ 的模板为准。
     *
     * <p>贴图上精确为 0 的像素（那一遍没画到的地方）用雾色补上，避免镜内出现死黑块。
     *
     * @param sceneTexture 自渲染世界贴图
     * @param fogColor     雾色 RGB（0~1），用来填补来源里的死黑像素
     * @return 是否画进去了
     */
    public static boolean fillLensScreen(int sceneTexture, float[] fogColor,
                                         float centerU, float centerV) {
        if (sceneTexture <= 0 || initializationFailed) return false;
        RenderSystem.assertOnRenderThread();
        int target = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (target == 0) return false;
        // 先读模板状态：prepareDraw() 会把模板测试关掉。
        boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
                && GL11.glGetInteger(GL11.GL_STENCIL_FUNC) == GL11.GL_EQUAL
                && GL11.glGetInteger(GL11.GL_STENCIL_REF) != 0
                && GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK) == 255;
        if (!stencil) return false;
        try (ScopedGlState ignored = new ScopedGlState()) {
            initialize();
            ScopedGlState.prepareDraw();
            GL20.glUseProgram(fillProgram);
            // 只用原生 GL 绑定贴图：RenderSystem 的缓存不能被动到，后面原版还要接着画。
            bindTexture(0, sceneTexture);
            GL20.glUniform1i(GL20.glGetUniformLocation(fillProgram, "Scene"), 0);
            setFogUniform(fillProgram, fogColor);
            GL30.glBindVertexArray(fullscreenVao);
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilMask(0);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            // 整个镜片总成都贴（模板值 != 0），不只是中间那个"取反圆"：
            // TaCZ 会在圆外再画一圈黑色目镜遮罩，只贴内圆就会留下那一圈黑边/透明圈
            // （实机画面里那一圈）。贴满整个目镜 = 镜内永远是一整块画面。
            GL11.glStencilFunc(GL11.GL_NOTEQUAL, 0, 255);
            // 镜内画面按"分划板中心"对齐：小视野那一遍是以屏幕中心（相机光轴）为准渲染的，
            // 而镜片圆心/分划板在后坐力动画里会离开屏幕中心。这里把画面整体平移，让画面中心
            // 正好落在分划板上，交叉线指哪儿画面中心就是哪儿（实机画面的错位就是没这一步）。
            GL20.glUniform2f(GL20.glGetUniformLocation(fillProgram, "Center"),
                    Float.isFinite(centerU) ? Math.clamp(centerU, 0F, 1F) : 0.5F,
                    Float.isFinite(centerV) ? Math.clamp(centerV, 0F, 1F) : 0.5F);
            GL20.glUniform2f(GL20.glGetUniformLocation(fillProgram, "Radius"), 0F, 0F);
            drawFullscreen();
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            return true;
        } catch (RuntimeException e) {
            warn("镜内画面填充失败，保留原视野", e);
            return false;
        }
    }

    /**
     * 镜内内容的"底色"：比雾色稍微亮一点点。
     *
     * <p>镜内那一遍没画到的像素是精确的 0，直接显示就是死黑一块。这里给一个看得过去的最低亮度，
     * 让缺内容的地方看上去像远处糊成雾，而不是镜头被挡住。
     */
    public static float[] lensFallbackColor() {
        float[] fog = RenderSystem.getShaderFogColor().clone();
        float luminance = 0.2126F * fog[0] + 0.7152F * fog[1] + 0.0722F * fog[2];
        float floor = 0.16F;
        if (luminance >= floor) return fog;
        float lift = (floor - luminance) / Math.max(1.0E-4F, 1F - luminance);
        // 往中性偏蓝灰抬一点：夜里镜内也不会是一块纯黑
        fog[0] += (0.32F - fog[0]) * lift;
        fog[1] += (0.35F - fog[1]) * lift;
        fog[2] += (0.42F - fog[2]) * lift;
        return fog;
    }

    private static void setFogUniform(int program, float[] fog) {
        float[] color = fog != null && fog.length >= 3 ? fog : lensFallbackColor();
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "FogColor"), color[0], color[1], color[2]);
    }

    /**
     * 不开光影时的延后贴图：枪已经画完、模板也写好了，现在把小视野画面贴进镜片。
     *
     * <p>以前是在 TaCZ 画分划板的那一瞬间贴（{@code blitIntoLens}）。后坐力把开镜进度
     * 打下去的那几帧，模板圆还没画完或已经被清掉，贴图钩子直接跳过 —— 镜片里就是
     * 一圈透明。改到 HUD 之前再贴：这一刻模板已经是最终状态，贴进去就不会漏。
     */
    private static void compositeVanilla() {
        var main = Minecraft.getInstance().getMainRenderTarget();
        if (main == null) return;
        int worldTexture = 0;
        if (LensZoomState.captureOkThisFrame) {
            worldTexture = LensZoomState.worldCaptureTextureVanilla();
        }
        try (ScopedGlState ignored = new ScopedGlState()) {
            ScopedGlState.prepareDraw();
            int sceneTexture;
            int sceneWidth;
            int sceneHeight;
            float zoom;
            float sharpness;
            boolean worldSource = worldTexture > 0;
            if (worldSource) {
                sceneTexture = worldTexture;
                sceneWidth = Math.max(1, LensZoomState.worldCaptureWidth());
                sceneHeight = Math.max(1, LensZoomState.worldCaptureHeight());
                zoom = 1.0F;
                sharpness = 0F;
            } else {
                var clean = LensZoomState.cleanWorldCopy();
                if (clean == null) return;
                sceneTexture = clean.getColorTextureId();
                sceneWidth = clean.width;
                sceneHeight = clean.height;
                zoom = Math.max(1.02F, LensZoomState.currentZoom);
                sharpness = 0F;
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
            GL11.glViewport(0, 0, main.width, main.height);
            for (int i = 0; i < count; i++) {
                Lens lens = LENSES.get(i);
                GL11.glDisable(GL11.GL_BLEND);
                drawSpatial(lens, sceneTexture, sceneWidth, sceneHeight, zoom, sharpness);
                drawReticle(lens, main.width, main.height);
            }
        } catch (RuntimeException e) {
            warn("无光影镜内合成失败", e);
        }
    }

    /**
     * 世界及光影后处理全部结束后、HUD 开画之前调用；F1 隐藏 HUD 时也执行。
     *
     * <p>光影下镜内画面有两个来源：
     * <ol>
     *   <li><b>自渲染世界图</b>（首选）：本模组已用镜片小视野把世界重画进独立贴图，
     *       这里是原分辨率像素，1:1 贴进镜片即可 —— 不插值、不放大，等于真正的光学放大。</li>
     *   <li><b>光影最终画面</b>（退路）：把整屏画面按倍率放大 + 时域重建。</li>
     * </ol>
     * 两种情况都在这里、也就是 Iris 整屏出图之后才贴，否则会被整屏合成覆盖。
     *
     * @param renderer 光影下"自渲染世界图"那一步就在这里面做（见
     *                 {@link LensZoomState#captureShaderLensLate}）：整帧画完之后再画一遍，
     *                 镜内拿到的世界和镜外是同一套管线条件，实体不会再丢。
     *                 兜底入口没有这两个参数时传 null，跳过那一步。
     * @param tracker  同一帧的时间轴，重画世界要原样传下去
     */
    public static void composite(net.minecraft.client.renderer.GameRenderer renderer,
                                 net.minecraft.client.DeltaTracker tracker) {
        if (composed) return;
        if (!LensZoomState.shaderCompositeActive()) {
            // 不开光影：枪已经画完、模板也写好了，现在把小视野画面贴进镜片。
            // 贴图时机从"画枪中间"改到这里，后坐力那几帧模板还没写完也不会漏贴。
            if (LensZoomState.lensFillAllowed() && count > 0) {
                composed = true;
                compositeVanilla();
            }
            return;
        }
        composed = true;
        if (count == 0) {
            skipSeen = true;
            if (LensZoomState.aimProgress > 0.95F && ++missingFrames == 120 && !missingWarned) {
                missingWarned = true;
                LOGGER.warn("[scope_lens_zoom] 连续 120 帧未取得镜片模板，"
                        + "请检查 TaCZ 渲染钩子或前面的模板错误（本帧镜内保留原视野）");
            }
            return;
        }
        missingFrames = 0;
        // 光影下：再来一遍世界渲染，只不过放在整帧之后（原因见 LensZoomState 里的方法注释）。
        // 它自己负责把整帧备份、重画、再还原，失败时镜内退回"放大最终画面"。
        // 兜底入口（RenderGuiEvent.Pre）拿不到 renderer/tracker，就不做这一遍。
        if (renderer != null && tracker != null) {
            LensZoomState.captureShaderLensLate(renderer, tracker);
        }
        var main = Minecraft.getInstance().getMainRenderTarget();
        int worldTexture = LensZoomState.worldCaptureTexture();
        // 镜内那一遍提交的实体明显比镜外少（镜外有、镜内一个都没有）：说明实体又丢了。
        // 这时放弃"清晰的小视野自渲染图"，改用"放大光影最终画面" —— 那是镜外同一张图，
        // 实体一定在。宁可糊一点，也不能让镜子里空着。
        boolean entityLoss = LensZoomState.lateCaptureLostEntities();
        if (entityLoss && !entityLossWarned) {
            entityLossWarned = true;
            LOGGER.warn("[scope_lens_zoom] 镜片视野里本该有实体，但镜内那一遍和单独补画都没画出来，"
                    + "本帧改用放大光影最终画面，保证镜内一定能看到实体");
        }
        // 所有倍率都优先用小视野自渲染图 1:1 直贴：那张图已经按瞄具真实倍率画过了，
        // 再乘一次"整屏放大"就是二次放大（低倍镜准星对不上、镜内外画面重叠），
        // 远处实体也会被糊成轮廓。低倍镜同样走这条路，不再用整屏截图放大。
        boolean worldSource = worldTexture > 0 && !entityLoss;
        try (ScopedGlState ignored = new ScopedGlState()) {
            ScopedGlState.prepareDraw();
            int sceneTexture;
            int sceneWidth;
            int sceneHeight;
            float zoom;
            float sharpness;
            boolean temporal;
            if (worldSource) {
                // 自渲染世界图本身就是"小视野"的原分辨率画面 —— 它的每一像素就是镜内该有的像素。
                // 所以这里必须 1:1 取样（Zoom=1 时 s = Center + (uv-Center)/1 = uv，也就是原样搬运），
                // 绝不能按"整张缩进镜片圆"的方式再放大一次：
                //   镜内视野已经用 2*atan(tan(基础视野/2)/倍率) 算过，投影 p11 已经是主画面的 倍率 倍，
                //   同一块屏幕像素覆盖的世界角度就比主画面小 倍率 倍 —— 放大倍数已经在渲染那一步付过了。
                // 再乘一个"镜片直径/屏幕高"就是二次放大：6.5 倍镜会变成 13 倍，和无光影的倍率对不上，
                // 画面也被插值糊掉。这里取 1，光影和无光影两条路的倍率、清晰度才对得起来。
                sceneTexture = worldTexture;
                sceneWidth = Math.max(1, LensZoomState.worldCaptureWidth());
                sceneHeight = Math.max(1, LensZoomState.worldCaptureHeight());
                zoom = 1.0F;
                // 1:1 搬运就是逐像素复制：bicubic 在整 texel 位置上是恒等采样，
                // 再叠任何强度的 CAS 都会把方块纹理相邻像素的色差推成可见的格子线。
                // 早先在这里留了 0.48 的锐化，正是"低倍镜里出现线"的放大器 —— 现在归零。
                sharpness = 0F;
                temporal = false;
            } else {
                // 退路：小视野没画出来时才把世界画面按倍率放大。优先用"枪和分划还没画上去"
                // 的干净拷贝，避免 TaCZ 自己的分划被连着放大一遍叠成双分划。
                ensureFrame(main.width, main.height);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frameFbo);
                GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, frameWidth, frameHeight,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                var clean = LensZoomState.cleanWorldCopy();
                zoom = Math.max(1.02F, LensZoomState.currentZoom);
                sharpness = Math.clamp(LensZoomConfig.lensSharpness() * 0.5F, 0F, 1F);
                temporal = LensZoomConfig.temporalSR() && zoom >= 4F;
                if (clean != null && clean.width == main.width && clean.height == main.height) {
                    sceneTexture = clean.getColorTextureId();
                    sceneWidth = clean.width;
                    sceneHeight = clean.height;
                } else {
                    sceneTexture = frameTexture;
                    sceneWidth = frameWidth;
                    sceneHeight = frameHeight;
                }
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
            GL11.glViewport(0, 0, main.width, main.height);
            float[] a = new float[4];
            float[] b = new float[2];
            boolean mappingOk = false;
            if (temporal) {
                Lens first = LENSES.get(0);
                mappingOk = computeReprojection(a, b, first.centerU, first.centerV);
                if (!mappingOk) skipSeen = true;
            }
            for (int i = 0; i < count; i++) {
                Lens lens = LENSES.get(i);
                float lensZoom = zoom;
                GL11.glDisable(GL11.GL_BLEND);
                if (temporal) {
                    drawTemporalLens(lens, sceneTexture, sceneWidth, sceneHeight,
                            main.width, main.height, lensZoom, mappingOk, a, b);
                } else {
                    drawSpatial(lens, sceneTexture, sceneWidth, sceneHeight, lensZoom, sharpness);
                }
                drawReticle(lens, main.width, main.height);
            }
            skipSeen = false;
            if (!logged && LensZoomState.aimProgress > 0.95F) {
                logged = true;
                LOGGER.info("[scope_lens_zoom] 光影镜内合成已执行：来源={}（路线 {}），倍率 {}，镜片数 {}，主画面 {}x{}，"
                                + "模板 {}x{}，贴图源 {}x{}，镜片直径 {}px，镜内缩放 {}，时域重建 {}，重投影 {}，锐化 {}",
                        worldSource ? (LensCaptureStrategy.current() == LensCaptureStrategy.Strategy.SHADER_LIT
                                ? "光影自渲染小视野图(原生分辨率1:1)" : "无光影自渲染世界图(小视野1:1)")
                                : "世界画面按倍率放大(不含枪身和分划)",
                        LensCaptureStrategy.current().name(),
                        LensZoomState.currentZoom, count, main.width, main.height,
                        LENSES.get(0).width, LENSES.get(0).height, sceneWidth, sceneHeight,
                        Math.round(lensPixelDiameter(LENSES.get(0))),
                        zoom,
                        temporal ? "开" : "关", mappingOk ? "对齐" : "本帧重置", sharpness);
            }
        } catch (RuntimeException e) {
            warn("光影镜内合成失败", e);
        }
    }

    /**
     * 读一次模板缓冲，量出"镜内区域"在屏幕上的包围盒。
     *
     * <p>为什么要量：镜内那张贴图是用小视野渲染的**整屏**画面，而镜片圆只占屏幕中间一块。
     * 要让它铺满镜片圆、看到的正好是整个小视野（也就是说真正的 N 倍放大），
     * 就必须知道镜片圆在屏幕上有多大。这个尺寸只跟当前瞄具和开镜进度有关，
     * 所以开镜动画期间跟随更新，停稳后就靠缓存，不会每帧都读 GPU。
     */
    private static void updateLensBounds(Lens lens, int width, int height) {
        float aim = LensZoomState.currentAimingProgress();
        boolean sameAim = Float.isFinite(lens.boundsAim) && Math.abs(aim - lens.boundsAim) < 0.02F;
        if (lens.boundsValid && sameAim && frameCounter - lens.boundsFrame < 60) return;
        lens.boundsFrame = frameCounter;
        lens.boundsAim = aim;
        if (stencilScratch == null || stencilScratch.capacity() < width * height) {
            stencilScratch = org.lwjgl.BufferUtils.createByteBuffer(width * height);
        }
        stencilScratch.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_STENCIL_INDEX,
                GL11.GL_UNSIGNED_BYTE, stencilScratch);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // 读模板失败时保持上一次量到的尺寸，不打断这一帧的合成。
        }
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        int step = 2;
        for (int y = 0; y < height; y += step) {
            int row = y * width;
            for (int x = 0; x < width; x += step) {
                if ((stencilScratch.get(row + x) & 0xFF) < 128) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX - minX > 4 && maxY - minY > 4) {
            lens.boundsU0 = minX / (float) width;
            lens.boundsV0 = minY / (float) height;
            lens.boundsU1 = (maxX + step) / (float) width;
            lens.boundsV1 = (maxY + step) / (float) height;
            lens.boundsValid = true;
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static float lensPixelDiameter(Lens lens) {
        if (lens.boundsValid && lens.width > 0 && lens.height > 0) {
            float w = (lens.boundsU1 - lens.boundsU0) * lens.width;
            float h = (lens.boundsV1 - lens.boundsV0) * lens.height;
            float d = Math.min(w, h);
            if (d > 8F) return d;
        }
        // 还没量到（开镜第一帧）：先按"镜片约占屏幕一半"起步，下一帧就有准确值。
        return Math.min(lens.width, lens.height) * 0.5F;
    }

    /** 单帧空间输出：把来源贴图按倍率（自渲染世界图时整屏 1:1）贴进镜片模板内。 */
    private static void drawSpatial(Lens lens, int sceneTexture, int sceneWidth, int sceneHeight,
                                    float zoom, float sharpness) {
        GL20.glUseProgram(zoomProgram);
        bindTexture(0, sceneTexture);
        bindTexture(1, lens.texture);
        GL20.glUniform1i(GL20.glGetUniformLocation(zoomProgram, "Scene"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(zoomProgram, "Mask"), 1);
        GL20.glUniform2f(GL20.glGetUniformLocation(zoomProgram, "Center"),
                lens.centerU, lens.centerV);
        GL20.glUniform1f(GL20.glGetUniformLocation(zoomProgram, "Zoom"), zoom);
        GL20.glUniform2f(GL20.glGetUniformLocation(zoomProgram, "Texel"),
                1F / Math.max(1, sceneWidth), 1F / Math.max(1, sceneHeight));
        GL20.glUniform1f(GL20.glGetUniformLocation(zoomProgram, "Sharpness"), sharpness);
        setFogUniform(zoomProgram, lensFallbackColor());
        drawFullscreen();
    }

    /** 累积一帧观测进 RGBA16F 历史缓冲，然后把累积结果 1:1 锐化输出到屏幕。 */
    private static void drawTemporalLens(Lens lens, int sceneTexture, int sceneWidth, int sceneHeight,
                                         int screenWidth, int screenHeight,
                                         float zoom, boolean mappingOk, float[] a, float[] b) {
        boolean reset = !mappingOk || skipSeen || lens.resetPending
                || Math.abs(zoom - lens.lastZoom) > Math.max(1.0E-4F, lens.lastZoom * 0.02F);
        int write = lens.accIndex;
        int read = lens.accIndex ^ 1;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, lens.accFbo[write]);
        GL11.glViewport(0, 0, lens.width, lens.height);
        GL20.glUseProgram(tsrProgram);
        bindTexture(0, sceneTexture);
        bindTexture(1, lens.texture);
        bindTexture(2, lens.accTex[read]);
        GL20.glUniform1i(GL20.glGetUniformLocation(tsrProgram, "Scene"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(tsrProgram, "Mask"), 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(tsrProgram, "Hist"), 2);
        GL20.glUniform2f(GL20.glGetUniformLocation(tsrProgram, "Center"), lens.centerU, lens.centerV);
        GL20.glUniform1f(GL20.glGetUniformLocation(tsrProgram, "Zoom"), zoom);
        GL20.glUniform2f(GL20.glGetUniformLocation(tsrProgram, "Texel"),
                1F / Math.max(1, sceneWidth), 1F / Math.max(1, sceneHeight));
        GL20.glUniform2f(GL20.glGetUniformLocation(tsrProgram, "BufferSize"), lens.width, lens.height);
        // 屏幕空间映射 s→s_prev 升到镜片缓冲空间：hist(uv) = A·uv + [PC − A·C + Z·(A·C + b − PC)]
        float cu = lens.centerU;
        float cv = lens.centerV;
        float pcu = lens.prevValid ? lens.prevCenterU : cu;
        float pcv = lens.prevValid ? lens.prevCenterV : cv;
        float acx = a[0] * cu + a[2] * cv;
        float acy = a[1] * cu + a[3] * cv;
        GL20.glUniform4f(GL20.glGetUniformLocation(tsrProgram, "HistA"), a[0], a[1], a[2], a[3]);
        GL20.glUniform2f(GL20.glGetUniformLocation(tsrProgram, "HistB"),
                pcu - acx + zoom * (acx + b[0] - pcu),
                pcv - acy + zoom * (acy + b[1] - pcv));
        GL20.glUniform1f(GL20.glGetUniformLocation(tsrProgram, "Reset"), reset ? 1F : 0F);
        drawFullscreen();
        lens.accIndex = read;
        lens.lastZoom = zoom;
        lens.resetPending = false;
        lens.prevValid = true;
        lens.prevCenterU = cu;
        lens.prevCenterV = cv;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                Minecraft.getInstance().getMainRenderTarget().frameBufferId);
        GL11.glViewport(0, 0, screenWidth, screenHeight);
        GL20.glUseProgram(presentProgram);
        bindTexture(0, lens.accTex[write]);
        bindTexture(1, lens.texture);
        GL20.glUniform1i(GL20.glGetUniformLocation(presentProgram, "Acc"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(presentProgram, "Mask"), 1);
        GL20.glUniform2f(GL20.glGetUniformLocation(presentProgram, "Texel"),
                1F / lens.width, 1F / lens.height);
        GL20.glUniform1f(GL20.glGetUniformLocation(presentProgram, "Zoom"), zoom);
        GL20.glUniform1f(GL20.glGetUniformLocation(presentProgram, "Sharpness"),
                Math.clamp(LensZoomConfig.lensSharpness(), 0F, 2F));
        drawFullscreen();
    }

    /**
     * 用上一帧与本帧的相机四元数 + 世界投影矩阵，拟合"本帧 Scene 像素 → 上一帧 Scene 像素"
     * 的仿射映射（对无穷远内容精确，镜内看远处正是主场景）。失败返回 false，本帧重置历史。
     */
    private static boolean computeReprojection(float[] a, float[] b, float cu, float cv) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f projection = LensZoomState.worldProjection();
        if (camera == null || !camera.isInitialized() || projection == null) {
            prevValid = false;
            return false;
        }
        float p00 = projection.m00();
        float p11 = projection.m11();
        boolean ok = false;
        if (prevValid && p00 > 1.0E-4F && p11 > 1.0E-4F) {
            Quaternionf rot = new Quaternionf(PREVIOUS_ROT).conjugate()
                    .mul(new Quaternionf(camera.rotation()));
            float e = 0.01F;
            float[] p0 = new float[2];
            float[] pu = new float[2];
            float[] pv = new float[2];
            if (mapToPrevious(rot, p00, p11, cu, cv, p0)
                    && mapToPrevious(rot, p00, p11, cu + e, cv, pu)
                    && mapToPrevious(rot, p00, p11, cu, cv + e, pv)) {
                float a00 = (pu[0] - p0[0]) / e;
                float a10 = (pu[1] - p0[1]) / e;
                float a01 = (pv[0] - p0[0]) / e;
                float a11 = (pv[1] - p0[1]) / e;
                float det = a00 * a11 - a01 * a10;
                if (det > 0.25F && det < 4.0F
                        && Math.abs(p0[0] - cu) < 0.35F && Math.abs(p0[1] - cv) < 0.35F) {
                    a[0] = a00;
                    a[1] = a10;
                    a[2] = a01;
                    a[3] = a11;
                    b[0] = p0[0] - (a00 * cu + a01 * cv);
                    b[1] = p0[1] - (a10 * cu + a11 * cv);
                    ok = true;
                }
            }
        }
        PREVIOUS_ROT.set(camera.rotation());
        prevP00 = p00;
        prevP11 = p11;
        prevValid = true;
        return ok;
    }

    /** 当前帧 Scene UV → 同一世界方向在上一帧的 Scene UV（只对齐旋转，无穷远无视差）。 */
    private static boolean mapToPrevious(Quaternionf rot, float p00, float p11,
                                         float u, float v, float[] out) {
        Vector3f dir = new Vector3f((2F * u - 1F) / p00, (2F * v - 1F) / p11, -1F).normalize();
        rot.transform(dir);
        if (dir.z > -1.0E-3F) return false;
        out[0] = 0.5F * prevP00 * dir.x / (-dir.z) + 0.5F;
        out[1] = 0.5F * prevP11 * dir.y / (-dir.z) + 0.5F;
        return Float.isFinite(out[0]) && Float.isFinite(out[1]);
    }

    private static void drawReticle(Lens lens, int width, int height) {
        GL20.glUseProgram(reticleProgram);
        bindTexture(0, lens.reticleTexture);
        bindTexture(1, lens.texture);
        GL20.glUniform1i(GL20.glGetUniformLocation(reticleProgram, "Reticle"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(reticleProgram, "Mask"), 1);
        GL20.glUniform2f(GL20.glGetUniformLocation(reticleProgram, "OutputSize"), width, height);
        GL11.glEnable(GL11.GL_BLEND);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL30.glBindVertexArray(reticleVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, reticleVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, lens.vertices, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, lens.vertices.length / 10);
    }

    private static void drawFullscreen() {
        GL30.glBindVertexArray(fullscreenVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private static void bindTexture(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static void ensureFrame(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalStateException("主画面尺寸无效");
        if (frameTexture != 0 && width == frameWidth && height == frameHeight) return;
        if (frameTexture == 0) frameTexture = GL11.glGenTextures();
        if (frameFbo == 0) frameFbo = GL30.glGenFramebuffers();
        allocateTexture(frameTexture, width, height, GL11.GL_LINEAR);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, frameFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, frameTexture, 0);
        checkFramebuffer();
        frameWidth = width;
        frameHeight = height;
    }

    private static void allocateTexture(int texture, int width, int height, int filter) {
        bindTexture(0, texture);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /** 时域累积缓冲：半浮点，避免逐帧混合的量化损失把画面越攒越灰。 */
    private static void allocateFloatTexture(int texture, int width, int height) {
        bindTexture(0, texture);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private static void checkFramebuffer() {
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("镜片帧缓冲不完整: 0x" + Integer.toHexString(status));
        }
    }

    private static void initialize() {
        if (maskProgram != 0) return;
        try {
            maskProgram = program("fullscreen.vsh", "mask.fsh");
            zoomProgram = program("fullscreen.vsh", "zoom.fsh");
            tsrProgram = program("fullscreen.vsh", "tsr.fsh");
            presentProgram = program("fullscreen.vsh", "present.fsh");
            reticleProgram = program("reticle.vsh", "reticle.fsh");
            fillProgram = program("fullscreen.vsh", "fill.fsh");
            fullscreenVao = GL30.glGenVertexArrays();
            reticleVao = GL30.glGenVertexArrays();
            reticleVbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(reticleVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, reticleVbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 40, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 40, 16L);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, 40, 24L);
        } catch (RuntimeException e) {
            initializationFailed = true;
            throw e;
        }
    }

    private static int program(String vertexFile, String fragmentFile) {
        int vertex = shader(GL20.GL_VERTEX_SHADER, vertexFile);
        int fragment = 0;
        int program = 0;
        try {
            fragment = shader(GL20.GL_FRAGMENT_SHADER, fragmentFile);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            return program;
        } catch (RuntimeException e) {
            if (program != 0) GL20.glDeleteProgram(program);
            throw e;
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int shader(int type, String file) {
        String source;
        try (var stream = ShaderLensCompositor.class.getResourceAsStream(
                "/assets/scope_lens_zoom/shaders/lens/" + file)) {
            if (stream == null) throw new IOException("缺少着色器 " + file);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(file + ": " + error);
        }
        return shader;
    }

    private static void warn(String message, RuntimeException error) {
        if (warned) return;
        warned = true;
        LOGGER.warn("[scope_lens_zoom] " + message, error);
    }

    private static final class Lens {
        int texture;
        int fbo;
        int width;
        int height;
        final int[] accTex = new int[2];
        final int[] accFbo = new int[2];
        int accIndex;
        boolean resetPending = true;
        boolean prevValid;
        float prevCenterU;
        float prevCenterV;
        float lastZoom;
        int reticleTexture;
        float centerU;
        float centerV;
        float[] vertices;
        /** 镜片（模板区域）在屏幕上的包围盒，0~1 归一化；shaderSource 时用它算镜内缩放。 */
        float boundsU0 = 0.5F;
        float boundsV0 = 0.5F;
        float boundsU1 = 0.5F;
        float boundsV1 = 0.5F;
        boolean boundsValid;
        float boundsAim = Float.NaN;
        long boundsFrame = -1;

        void resize(int width, int height) {
            if (texture != 0 && width == this.width && height == this.height) return;
            if (texture == 0) texture = GL11.glGenTextures();
            if (fbo == 0) fbo = GL30.glGenFramebuffers();
            allocateTexture(texture, width, height, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            checkFramebuffer();
            for (int i = 0; i < 2; i++) {
                if (accTex[i] == 0) accTex[i] = GL11.glGenTextures();
                if (accFbo[i] == 0) accFbo[i] = GL30.glGenFramebuffers();
                allocateFloatTexture(accTex[i], width, height);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, accFbo[i]);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, accTex[i], 0);
                checkFramebuffer();
            }
            accIndex = 0;
            resetPending = true;
            prevValid = false;
            this.width = width;
            this.height = height;
        }
    }
}
