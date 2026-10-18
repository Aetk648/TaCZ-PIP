package com.i50208.taczpip.client;

import com.i50208.taczpip.mixin.GameRendererAccessor;
import com.i50208.taczpip.mixin.MinecraftAccessor;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 主画面始终保留正常视野。镜内画面一律走"独立小视野完整重渲染"（含 Sodium 区块、
 * Voxy 超视距 LOD、实体、天空、天气）。
 *
 * <p>开光影时，镜内那一遍开始前把 Iris 的当前管线临时隔离成"无光影"管线
 * （见 {@link IrisLensPipelineSwap}），画完立即恢复 —— 镜外主画面的光影效果不受任何影响。
 *
 * <p>贴回屏幕一律延后到 HUD 之前：画枪时只捕获镜片模板和分划板，
 * 等整帧出图后再由 {@link ShaderLensCompositor} 把小视野世界图 1:1 贴进镜片。
 * 这样后坐力那几帧模板还没写完也不会漏贴（不开光影的透明圈就是这么来的）。
 * 只有这一帧重渲染失败时，才退回"按倍率放大最终画面"的老路。
 */
public final class LensZoomState {
    public static boolean lensModeThisFrame;
    public static boolean secondPassThisFrame;
    public static boolean capturingWorld;
    public static boolean captureOkThisFrame;
    public static float currentZoom = 1F;
    public static float baseWorldFov = 70F;
    public static float aimProgress;
    public static boolean aimProgressFalling;
    public static boolean aimProgressRising;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("scope_lens_zoom");
    private static long frameId = -1;
    private static long capturedFrame = -1;
    private static long colorCopyFrame = -1;
    private static RenderTarget worldCapture;
    private static RenderTarget frameCopy;
    /** 光影下"整帧已经画好"的备份：镜内取景那一遍画完要把它原样放回去。 */
    private static RenderTarget frameRestore;
    private static boolean gunZoomedCache;
    private static float lastProgress;
    private static boolean secondPassDisabled;
    private static int secondPassFailures;
    private static boolean passWarned;
    private static boolean blitWarned;
    private static boolean okLogged;
    private static boolean stencilWarned;
    /** 本帧 Iris 光影在生效（无论镜内走的是隔离重渲染还是最终画面退路）。 */
    public static boolean shaderOnThisFrame;
    /** 镜内那一遍里实测的投影焦距（p11），用来确认"小视野"真的生效了。 */
    private static float captureProjection11;
    private static long captureProjectionFrame = -1;
    private static int diagFrames;
    /** 本帧镜内那一遍用的是哪条路线，日志里写清楚。 */
    private static LensCaptureStrategy.Strategy capturedStrategy = LensCaptureStrategy.Strategy.ISOLATED_REPAIRED;
    /** 镜内那一遍里允许重建区块可见列表（见 ScopeLensLevelRendererMixin）。 */
    private static boolean captureRefreshTerrain;
    /** 镜内那一遍里是否跳过天空（隔离路线跳过，光影直出路线不跳）。 */
    private static boolean captureSkipsSky;
    /** 镜内那一遍里是否放行别的模组的渲染阶段事件（只有"光影直出"那条路线放行）。 */
    private static boolean captureDispatchStages;
    /**
     * 本次开镜是否已经"真的盯进镜片里"过。
     *
     * <p>开枪后坐力会让 TaCZ 的开镜进度短暂回退（0.98 → 0.7 这种），随后马上回到 1。
     * 这是瞄具动画，不是收镜。用"进度一旦超过 0.8 就记住、掉到 0.35 以下才忘掉"的方式区分：
     * 后坐力的抖动不会中断镜内画面（实机里的"透明 + 瞄准框"就是中断那几帧造成的），
     * 只有玩家真的把镜收回去才会停。
     */
    private static boolean aimLatched;
    /**
     * 镜内画面用的"稳定开镜进度"。
     *
     * <p>后坐力会让 TaCZ 的开镜进度短暂回退（模型往回缩、镜片一圈随之变小），如果镜内倍率
     * 直接跟着它走，放大画面就会"往回缩一点"再弹回来 —— 实机里就是"开枪后镜内画面往后移，
     * 外面套着一圈空白"。所以回落要分"抖一下"和"真的收镜"：
     * 进度在涨/持平就跟着走；短暂回落先钉住不跟，
     * 钉住超过 {@link #LENS_HOLD_FRAMES} 帧（或进度已经接近 0）才认定为收镜。
     * 开镜动画照常从 0 涨到 1，不受影响。
     */
    private static float lensProgress;
    /** 进度在回落时，"先保持住"已经持续了多少帧（见 updateFrameState）。 */
    private static int lensHoldFrames;
    /** 后坐力那种回退先钉住多少帧（约 0.3 秒）；再久就认定玩家在收镜。 */
    private static final int LENS_HOLD_FRAMES = 20;
    /** 当前瞄具数据里写的倍率（不随开镜进度变化）；没开镜时是 1。 */
    private static float gunZoomTarget = 1F;

    /** 本帧世界遍的投影矩阵（renderLevel 开头抓取），时域重建算重投影用。 */
    private static final Matrix4f worldProjection = new Matrix4f();
    private static long worldProjectionFrame = -1;
    /** 镜内那一遍真正用的视图/投影矩阵：单独补画实体必须和它完全一致，实体才会和地形对齐。 */
    private static final Matrix4f captureModelView = new Matrix4f();
    private static final Matrix4f captureProjection = new Matrix4f();

    private LensZoomState() { }

    public static void updateFrameState(Minecraft mc) {
        frameId++;
        capturedFrame = -1;
        colorCopyFrame = -1;
        captureOkThisFrame = false;
        capturingWorld = false;
        mainEntitySubmitted = 0;
        mainEntities.clear();
        ShaderLensCompositor.beginFrame();
        boolean enabled = LensZoomConfig.enabled();
        gunZoomedCache = false;
        aimProgress = 0F;
        currentZoom = 1F;
        gunZoomTarget = 1F;
        if (enabled && mc.level != null && mc.player != null && !mc.player.isSpectator()
                && mc.options.getCameraType().isFirstPerson()) {
            ItemStack mainHand = mc.player.getMainHandItem();
            IGun gun = IGun.getIGunOrNull(mainHand);
            if (gun != null) {
                float zoom = gun.getAimingZoom(mainHand);
                if (Float.isFinite(zoom) && zoom > 1.0001F) {
                    gunZoomedCache = true;
                    gunZoomTarget = zoom;
                    aimProgress = IClientPlayerGunOperator.fromLocalPlayer(mc.player)
                            .getClientAimingProgress(mc.getTimer().getGameTimeDeltaPartialTick(false));
                }
            }
        }
        // 倍率、贴图、锁镜判定全部用"稳定进度"算，原始进度只用来驱动稳定进度本身。
        // 规则：
        //   进度在涨 / 持平 → 直接跟（开镜动画照常从 0 涨到 1）
        //   进度在掉        → 先钉住不跟。后坐力是"掉一下再弹回"，收镜是"持续往下走"。
        //                     钉住超过 LENS_HOLD_FRAMES 帧、或已经掉到接近 0，才认定为收镜。
        // 早先在进度掉到 0.35 以下时 10 帧后直接跟 —— 不少枪的后坐力会把进度打穿 0.35，
        // 那几帧镜片不贴图，实机就是"不开光影、开枪后一圈透明"。
        if (aimProgress >= lensProgress - 0.002F) {
            lensProgress = aimProgress;
            lensHoldFrames = 0;
        } else {
            lensHoldFrames++;
            boolean exiting = (aimProgress < 0.08F && lensHoldFrames > 6)
                    || lensHoldFrames > LENS_HOLD_FRAMES;
            if (exiting) {
                lensProgress = Math.max(aimProgress, lensProgress - 0.08F);
            }
        }
        if (gunZoomedCache) {
            currentZoom = 1F + (gunZoomTarget - 1F) * lensProgress;
        }
        aimProgressFalling = aimProgress < lastProgress - 0.002F && lastProgress > 0.02F;
        aimProgressRising = aimProgress > lastProgress + 0.002F;
        lastProgress = aimProgress;
        if (aimProgress > 0.75F) aimLatched = true;
        if (!gunZoomedCache || lensProgress < 0.08F) aimLatched = false;
        lensModeThisFrame = enabled && gunZoomedCache && lensProgress > 0.03F && currentZoom > 1.02F;
        shaderOnThisFrame = IrisCompat.shadersInUse();
        // 光影下镜内自渲染的几条路线都在 captureZoomedWorld 里按需尝试/自检；
        // 关掉 shadersLensCapture 就只能放大最终画面了。
        boolean captureAllowed = !shaderOnThisFrame || LensZoomConfig.shadersLensCapture();
        // 本帧要不要产出"镜内那张小视野画面"。
        // 不用光影时在 renderLevel 开头画（画进私有贴图，不影响主画面）；
        // 开了光影改到整帧画完之后再画（见 captureShaderLensLate）。
        // 所有倍率都走这一遍：远处实体要的是"用小视野把世界重新画清楚"，
        // 不是把已经画小的屏幕截图像素再拉大（那就是糊、就是透明轮廓）。
        secondPassThisFrame = lensModeThisFrame && captureAllowed && !secondPassDisabled;
        if (!lensModeThisFrame || !shaderOnThisFrame) ShaderLensCompositor.invalidateHistory();
    }

    /**
     * renderLevel 开头抓取本帧世界遍的投影矩阵；两个参数里挑透视的那个（m33==0）。
     * 光影阴影遍也走这里，但阴影的视野和玩家差很远，用视野对得上才收下，避免把太阳投影当成瞄准投影。
     */
    public static void stashWorldProjection(Matrix4f first, Matrix4f second) {
        Matrix4f projection = null;
        Matrix4f modelView = null;
        if (first != null && second != null) {
            if (first.m33() == 0F && second.m33() == 1F) {
                projection = first;
                modelView = second;
            } else if (second.m33() == 0F && first.m33() == 1F) {
                projection = second;
                modelView = first;
            }
        }
        if (projection == null) return;
        float p11 = projection.m11();
        if (capturingWorld) {
            // 镜内那一遍：这一遍的视野本来就被我们换成小视野了，p11 会比原版大得多，
            // 所以只做最基本的合理性判断，然后记下来 —— 日志里能直接看出小视野有没有生效。
            if (Float.isFinite(p11) && p11 > 0.05F && p11 < 200F) {
                captureProjection11 = p11;
                captureProjectionFrame = frameId;
                // 原样留一份：镜内那一遍画完之后要单独补画实体，用的必须是同一套矩阵。
                captureProjection.set(projection);
                captureModelView.set(modelView);
            }
            return;
        }
        if (!(p11 > 0.2F) || p11 > 8F) return;
        float expected = (float) (1.0 / Math.tan(Math.toRadians(Math.max(1.0, baseWorldFov) / 2.0)));
        if (expected > 0.2F && Math.abs(p11 - expected) > expected * 0.35F) return;
        worldProjection.set(projection);
        worldProjectionFrame = frameId;
    }

    /** 本帧世界投影矩阵；没有抓到（钩子没挂上）就返回 null，时域重建自动退回单帧。 */
    public static Matrix4f worldProjection() {
        return worldProjectionFrame == frameId ? worldProjection : null;
    }

    /** 本帧"枪和分划还没画上去"的干净世界画面（renderItemInHand 开头留的底）；没有就返回 null。 */
    static RenderTarget cleanWorldCopy() {
        return colorCopyFrame == frameId ? frameCopy : null;
    }

    public static boolean worldFovLocked() {
        return LensZoomConfig.enabled() && gunZoomedCache && !capturingWorld;
    }

    public static float lensCaptureFov() {
        double halfTan = Math.tan(Math.toRadians(baseWorldFov / 2.0)) / Math.max(1.02F, currentZoom);
        float fov = (float) (Math.toDegrees(Math.atan(halfTan)) * 2.0);
        return net.minecraft.util.Mth.clamp(fov, 0.5F, baseWorldFov);
    }

    public static boolean lensActive() { return lensModeThisFrame; }

    /**
     * 光影下镜内画面是否已经由本模组自己用小视野重渲染出来了。
     * 成功时镜内直接贴这张原分辨率贴图（1:1，不插值、不放大 → 最清晰）。
     */
    public static boolean shaderWorldCaptureActive() {
        return lensModeThisFrame && shaderOnThisFrame && captureOkThisFrame
                && capturedFrame == frameId;
    }

    /** 光影下镜内合成一律延后到 Iris 最终出图之后（否则会被整屏合成覆盖掉）。 */
    public static boolean shaderCompositeActive() { return lensModeThisFrame && shaderOnThisFrame; }

    /** 本帧自渲染世界贴图（没有就返回 0）。光影路径用，要求本帧光影合成在跑。 */
    public static int worldCaptureTexture() {
        return shaderWorldCaptureActive() && worldCapture != null ? worldCapture.getColorTextureId() : 0;
    }

    /** 无光影路径用的自渲染世界贴图；本帧取到景就返回。 */
    public static int worldCaptureTextureVanilla() {
        return captureOkThisFrame && capturedFrame == frameId && worldCapture != null
                ? worldCapture.getColorTextureId() : 0;
    }

    /**
     * 镜内那一遍里向实体渲染器提交了多少个实体（诊断用）。
     *
     * <p>用来分开两种完全不同的毛病：数字是 0 说明实体在镜内那一遍就被剔掉了
     * （视野/可见区块列表的问题）；数字大于 0 而镜内还是看不见实体，
     * 说明实体已经画了，是光影那边批次刷新或整屏合成没把它带出来。
     */
    public static int captureEntitySubmitted;

    /**
     * 镜内那一遍真正交给渲染器的实体名单。
     *
     * <p>单独补画通道直接照着这份名单画，不再自己重新剔除：早先的补画通道自己建了视锥、
     * 还检查"区块编译状态"，结果在 Sodium/Voxy 面前把所有实体都错剔了（日志里
     * "单独补画实体=0" 而 "镜内提交实体=3"），镜片里的实体就一直是光影自己那层半透明轮廓。
     * 渲染器亲手画过谁，镜片里就该有谁 —— 名单是唯一可信的依据。
     */
    private static final java.util.ArrayList<net.minecraft.world.entity.Entity> captureEntities =
            new java.util.ArrayList<>(96);

    public static void noteCaptureEntity(net.minecraft.world.entity.Entity entity) {
        if (capturingWorld) {
            captureEntitySubmitted++;
            if (entity != null && captureEntities.size() < 96 && !captureEntities.contains(entity)) {
                captureEntities.add(entity);
            }
        } else {
            mainEntitySubmitted++;
            if (entity != null && mainEntities.size() < 256) {
                mainEntities.add(entity);
            }
        }
    }

    /** 镜内那一遍提交过的实体名单（只读；每遍取景开始时清空）。 */
    public static java.util.List<net.minecraft.world.entity.Entity> captureEntities() {
        return captureEntities;
    }

    /**
     * 镜外主画面那一遍提交过的实体名单（本帧）。
     *
     * <p>镜内单独补画时和镜内名单取并集：玩家在主画面看得见、镜内那一遍却被
     * Sodium 视锥 / EntityCulling 这类模组误剔的实体（"中距离才透明"的一种成因），
     * 只要落在小视野里就照样画进镜片。
     */
    private static final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<net.minecraft.world.entity.Entity>
            mainEntities = new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>(64);

    public static java.util.Collection<net.minecraft.world.entity.Entity> mainPassEntities() {
        return mainEntities;
    }

    /**
     * 补画通道正在亲手画实体的那一小段时间。
     *
     * <p>镜内那一遍的"取消光影自己画实体"钩子必须在这段时间放行，
     * 否则补画自己提交的绘制会被自己取消掉。
     */
    public static boolean supplementDrawing;

    /**
     * 镜内那一遍是否要把实体从光影自己的绘制里摘出来（改由 {@link LensEntitySupplement} 独家负责）。
     *
     * <p>光影包在镜内这第二遍里画实体时，常常只留下写了深度的半透明轮廓（本体颜色进不了
     * 它的整屏合成）—— 这就是"中距离镜内实体透明""开枪镜内实体透明"的来源：
     * 补画的实心实体还会被这层轮廓的深度挡住。摘掉光影那层，镜片里就只剩一层实心实体。
     *
     * <p>只在补画通道真的画得出来的时候才摘：摘管线隔离那条路线（active）实体本来就由
     * 无光影管线实心绘制；补画通道出错被关闭、或换管线失败时也不摘，
     * 宁可由光影画（可能透明）也不能让镜子里没有实体。
     */
    public static boolean takeOverCaptureEntities() {
        return shaderOnThisFrame && LensZoomConfig.shadersLensEntities()
                && !IrisLensPipelineSwap.active()
                && LensEntitySupplement.willSuppressIrisEntities();
    }

    /** 镜外那一遍（玩家真正看到的那一遍）提交了多少实体，和镜内那一遍对比用。 */
    private static int mainEntitySubmitted;

    /**
     * 镜内那一遍明显比镜外少了实体？
     *
     * <p>正常情况下镜内那一遍是"整帧之后的同一套管线"，提交的实体数应该和镜外相当
     * （小视野会剔掉画面外侧的一部分，所以只会 <= 镜外）。如果镜外有、镜内一个都没有，
     * 那就是实体又丢了 —— 这时宁可放弃"清晰的小视野画面"，改用"放大光影最终画面"：
     * 那张图就是镜外同一张图，**实体一定在**。宁可糊一点，也不能让镜子里空着。
     */
    public static boolean lateCaptureLostEntities() {
        // 清晰度优先：只要小视野自渲染图画出来了，就绝不再退回"整屏截图放大"。
        // 远处实体变糊，正是因为这条退路把原生小视野换成了把屏幕像素拉大。
        // 实体由单独补画通道负责，补画失败也不牺牲清晰度。
        return false;
    }

    /** 镜内那一遍里是否允许重建区块可见列表（见 ScopeLensLevelRendererMixin）。 */
    public static boolean captureAllowsTerrainSetup() {
        return capturingWorld && captureRefreshTerrain;
    }

    /** 镜内那一遍里是否跳过天空：只有“把 Iris 管线摘掉”的路线才跳（那一步画不出光影的天空）。 */
    public static boolean captureSkipsSky() {
        return capturingWorld && captureSkipsSky;
    }

    /**
     * 镜内那一遍是否放行别的模组的渲染阶段事件。
     *
     * <p>Voxy 超视距、远距玩家、以及那些把实体攒起来一次性画的模组，都挂在这些阶段上。
     * 跳过它们，镜内那张图里超视距就会"有时有、有时没有"。AFTER_LEVEL（整屏后处理）
     * 仍然跳过，一帧跑两遍没有任何好处。
     */
    public static boolean captureDispatchesModStages() {
        return capturingWorld && captureDispatchStages;
    }

    public static int worldCaptureWidth() {
        return worldCapture == null ? 0 : worldCapture.width;
    }

    public static int worldCaptureHeight() {
        return worldCapture == null ? 0 : worldCapture.height;
    }

    public static float captureProjection() {
        return captureProjectionFrame == frameId ? captureProjection11 : 0F;
    }

    /** 镜内那一遍用的视图矩阵；这一帧没取过景就返回 null（补画实体时跳过）。 */
    public static Matrix4f captureModelView() {
        return captureProjectionFrame == frameId ? captureModelView : null;
    }

    /** 镜内那一遍用的投影矩阵（小视野）；这一帧没取过景就返回 null。 */
    public static Matrix4f captureProjectionMatrix() {
        return captureProjectionFrame == frameId ? captureProjection : null;
    }
    public static boolean gunZoomed(Minecraft mc) { return gunZoomedCache; }
    public static float currentAimingProgress() { return aimProgress; }

    public static boolean handPinActive(Minecraft mc) {
        return aimPinWeight() > 0F;
    }

    /**
     * "把镜框固定在屏幕上一圈"这条设置的生效强度，0~1。
     *
     * <p>这里早先是硬开关（开镜进度 &lt; 0.02 或正在收镜就整块关掉）。手部视野一旦从固定值
     * 瞬间跳回 TaCZ 自己的值，瞄具模型会在一帧里突然放大/缩小，模型边缘被近裁剪面切开，
     * 屏幕上就是"左侧一大块黑色一闪而过"（实机就是收镜那一帧拍到的）。
     * 改成按开镜进度平滑过渡：开镜和收镜过程中都渐变，任何一帧都不会跳变。
     */
    public static float aimPinWeight() {
        if (!LensZoomConfig.enabled() || !LensZoomConfig.pinScopeFrame() || !gunZoomedCache) return 0F;
        return Math.clamp((aimProgress - 0.02F) / 0.25F, 0F, 1F);
    }

    /**
     * 本帧要不要往镜片里贴放大画面。
     *
     * <p>判定用的是"这一次开镜有没有真的盯进去过"（{@link #aimLatched}），而不是"这一刻进度有没有在掉"。
     * 而且两边都用**稳定进度**：后坐力把原始进度打到很低的那几帧，锁镜和贴图都不受影响 ——
     * 只要镜片模板还画得出非零区域，镜内就必须有画面，一帧都不能空（空帧=透明圈）。
     */
    public static boolean lensFillAllowed() {
        // 锁过一次之后，只要镜片还在屏幕上（稳定进度 > 0.03）就必须贴图。
        // 门槛再高，后坐力把进度打下去的那几帧就会漏贴 —— 那就是透明圈。
        return lensModeThisFrame && aimLatched && lensProgress > 0.03F;
    }

    /** 稳定后的开镜进度：镜内画面相关的一切判定都用它，不用被后坐力抖动的原始值。 */
    public static float stableAimProgress() {
        return lensProgress;
    }

    private static void ensureTargets(int width, int height) {
        if (width <= 0 || height <= 0) return;
        worldCapture = ensureTarget(worldCapture, width, height);
        frameCopy = ensureTarget(frameCopy, width, height);
        frameRestore = ensureTarget(frameRestore, width, height);
    }

    private static RenderTarget ensureTarget(RenderTarget target, int width, int height) {
        if (target == null) {
            target = new MainTarget(width, height);
            target.setClearColor(0F, 0F, 0F, 1F);
            target.clear(Minecraft.ON_OSX);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
        target.setFilterMode(GL11.GL_LINEAR);
        return target;
    }

    /**
     * 独立小视野世界重渲染（镜内画面的来源）。不开光影时在 {@code renderLevel} 开头画；
     * 开光影时的入口是 {@link #captureShaderLensLate}。
     *
     * <p>开光影时这一遍要穿过 Iris / Sodium 的一堆内部状态，不同环境下能画出来的路线不一样，
     * 所以这里按 {@link LensCaptureStrategy#candidates()} 给的顺序挨个试：每画完一张就当场
     * 读回来量一量（{@link LensCaptureProbe}），画出了地形就锁定这条路线；都画不出来就退回
     * “放大光影最终画面”。自检只在开局那一两帧做，定下来之后每帧只画一次。
     */
    public static void captureZoomedWorld(GameRenderer renderer, DeltaTracker tracker) {
        if (!secondPassThisFrame || shaderOnThisFrame || capturingWorld || capturedFrame == frameId) return;
        // 无光影也走"先备份主画面、再画小视野、再还原"：这一遍改到画手之前执行，
        // 那时主画面已经画好了（含 Voxy 超视距）。直接往主画面上画会把它盖掉。
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return;
        ensureTargets(main.width, main.height);
        if (frameRestore == null || frameRestore.width != main.width
                || frameRestore.height != main.height) {
            RenderTarget capture = runCaptureRoutes(renderer, tracker);
            if (capture == null) {
                capturedFrame = -1;
                captureOkThisFrame = false;
            }
            return;
        }
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean saved = false;
        try {
            blit(main, frameRestore);
            saved = true;
            RenderTarget capture = runCaptureRoutes(renderer, tracker);
            if (capture == null) {
                capturedFrame = -1;
                captureOkThisFrame = false;
            }
        } catch (Throwable t) {
            captureOkThisFrame = false;
            capturedFrame = -1;
            if (!passWarned) {
                passWarned = true;
                LOGGER.warn("[scope_lens_zoom] 无光影镜内取景失败，本帧改用已渲染画面", t);
            }
        } finally {
            if (saved) {
                try {
                    blit(frameRestore, main);
                } catch (RuntimeException e) {
                    warnBlit(e);
                }
            }
            main.bindWrite(true);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /**
     * 光影下的镜内取景：等整帧（世界 + 光影后期 + 枪和瞄具）都画完之后，再单独画一遍小视野世界。
     *
     * <p>为什么放到最后：光影包的一遍渲染里有大量"每帧一次"的状态（gbuffer 的清空与 ping-pong、
     * 延迟着色的深度拷贝、实体/方块的批处理刷写）。在 {@code renderLevel} 开头插一遍，
     * 等于让 Iris 在同一个帧里连跑两遍管线，而**第一遍**拿到的那张图里地形在、实体却常常不在
     * （实体提交数不是 0，是画了没进整屏合成）。实机里的"实体透明"就是这个。
     * 放到整帧之后，镜内这一遍就变成"本帧第二轮完整管线"，和玩家真正看到的那一遍条件一致。
     *
     * <p>具体顺序：
     * <ol>
     *   <li>把已经画好的整帧拷进 {@link #frameRestore}；</li>
     *   <li>清空主画面，用镜内小视野重画一遍世界，再拷进镜内贴图；</li>
     *   <li>把整帧原样拷回主画面 —— 除镜片以外一个像素都不变。</li>
     * </ol>
     * 任何一步出问题都直接把备份拷回去并把镜内画面退到"放大最终画面"，玩家最多糊一点，不会黑屏。
     */
    public static void captureShaderLensLate(GameRenderer renderer, DeltaTracker tracker) {
        if (!secondPassThisFrame || !shaderOnThisFrame || capturingWorld || capturedFrame == frameId) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return;
        ensureTargets(main.width, main.height);
        if (frameRestore == null || frameRestore.width != main.width
                || frameRestore.height != main.height) return;
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        float[] previousClearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, previousClearColor);
        boolean saved = false;
        try {
            // 1) 整帧备份（世界 + 光影后期 + 枪和瞄具，就是玩家此刻看到的那张图）
            blit(main, frameRestore);
            saved = true;
            // 2) 清干净主画面，用镜内小视野重画一遍世界
            main.bindWrite(true);
            float[] fog = ShaderLensCompositor.lensFallbackColor();
            GL11.glClearColor(fog[0], fog[1], fog[2], 1F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                    | GL11.GL_STENCIL_BUFFER_BIT, Minecraft.ON_OSX);
            RenderTarget capture = runCaptureRoutes(renderer, tracker);
            if (capture == null || capturedFrame != frameId) {
                capturedFrame = -1;
                captureOkThisFrame = false;
            }
        } catch (Throwable t) {
            captureOkThisFrame = false;
            capturedFrame = -1;
            if (!passWarned) {
                passWarned = true;
                LOGGER.warn("[scope_lens_zoom] 光影下镜内取景失败，本帧改用最终画面放大", t);
            }
            if (++secondPassFailures > 120) secondPassDisabled = true;
        } finally {
            // 3) 无论成功失败，都把玩家该看到的那一帧原样放回去
            boolean restoreOk = false;
            if (saved) {
                try {
                    blit(frameRestore, main);
                    restoreOk = true;
                } catch (RuntimeException e) {
                    warnBlit(e);
                }
            }
            if (!restoreOk) {
                // 拷不回去就别再往主画面上贴任何东西，直接让合成器走"最终画面放大"
                captureOkThisFrame = false;
                capturedFrame = -1;
            }
            main.bindWrite(true);
            GL11.glClearColor(previousClearColor[0], previousClearColor[1],
                    previousClearColor[2], previousClearColor[3]);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /**
     * 用镜内小视野重画一遍世界，按路线顺序试到一张能用的为止。
     *
     * @return 画成功的那张贴图；全失败返回 null（调用方把本帧当成"没有镜内贴图"处理）
     */
    private static RenderTarget runCaptureRoutes(GameRenderer renderer, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return null;
        RenderSystem.assertOnRenderThread();
        MinecraftAccessor accessor = (MinecraftAccessor) mc;
        boolean previousHand = ((GameRendererAccessor) renderer).scopeLens$getRenderHand();
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        float[] previousFogColor = RenderSystem.getShaderFogColor().clone();
        float previousFogStart = RenderSystem.getShaderFogStart();
        float previousFogEnd = RenderSystem.getShaderFogEnd();
        float[] previousShaderColor = RenderSystem.getShaderColor().clone();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        ShaderInstance previousShader = RenderSystem.getShader();
        GlStateBackup state = new GlStateBackup();
        GlStateManager._backupGlState(state);
        capturingWorld = true;
        double previousViewScale = net.minecraft.world.entity.Entity.getViewScale();
        try {
            // 镜内那一遍：把实体可见距离按倍率拉大。原版默认 64 格（盔甲架这类小体积
            // 更短），高倍镜看远处时实体会被这条距离先裁掉 —— 镜内只剩轮廓/透明。
            // 倍率越大、看得越远，所以按倍率放大（上限 8 倍，避免把整张图的实体全画一遍）。
            float viewBoost = Math.clamp(currentZoom, 1F, 8F);
            net.minecraft.world.entity.Entity.setViewScale(Math.max(previousViewScale, viewBoost));
            // 雾也推到更远：小视野里雾会把远处实体和地形糊成一片，看起来就是"糊、透明"。
            if (Float.isFinite(previousFogEnd) && previousFogEnd > 0F && previousFogEnd < 1.0E6F) {
                RenderSystem.setShaderFogStart(previousFogStart * viewBoost);
                RenderSystem.setShaderFogEnd(previousFogEnd * viewBoost);
            }
            ensureTargets(main.width, main.height);
            RenderTarget capture = worldCapture;
            if (capture == null || capture.width != main.width || capture.height != main.height) return null;
            boolean probe = shaderOnThisFrame && LensCaptureStrategy.probing()
                    && aimProgress > 0.5F;
            for (LensCaptureStrategy.Strategy strategy : LensCaptureStrategy.candidates()) {
                if (!strategy.rendersCapture()) {
                    // 走到“放大最终画面”：不画自渲染贴图，交给合成器取最终画面。
                    if (LensCaptureStrategy.probing()) {
                        LensCaptureStrategy.settleAtEnd(strategy, "自渲染路线都试过了");
                    }
                    capturedStrategy = strategy;
                    return null;
                }
                if (!drawCapture(renderer, tracker, capture, strategy, accessor)) {
                    capturedFrame = -1;
                    captureOkThisFrame = false;
                    LensCaptureStrategy.reject(strategy, "这一遍没有画出来");
                    if (!LensCaptureStrategy.probing()) break;
                    continue;
                }
                capturedFrame = frameId;
                captureOkThisFrame = true;
                capturedStrategy = strategy;
                if (!probe) {
                    logCaptureSuccess(capture, strategy);
                    return capture;
                }
                LensCaptureProbe.Stats stats = LensCaptureProbe.measure(capture);
                if (stats == null) {
                    // 读不回来就不下结论，先按这条路线用着，别把玩家的镜内搞空。
                    LensCaptureStrategy.confirm(strategy, "无法读回画面，按实机表现判断");
                    logCaptureSuccess(capture, strategy);
                    return capture;
                }
                if (LensCaptureProbe.looksBroken(stats)) {
                    capturedFrame = -1;
                    captureOkThisFrame = false;
                    LensCaptureStrategy.reject(strategy, stats.describe());
                    continue;
                }
                LensCaptureStrategy.confirm(strategy, stats.describe());
                logCaptureSuccess(capture, strategy);
                return capture;
            }
            return null;
        } catch (Throwable t) {
            captureOkThisFrame = false;
            if (!passWarned) {
                passWarned = true;
                LOGGER.warn("[scope_lens_zoom] 镜内二次渲染失败，本帧改用已渲染画面", t);
            }
            if (++secondPassFailures > 120) secondPassDisabled = true;
            return null;
        } finally {
            net.minecraft.world.entity.Entity.setViewScale(previousViewScale);
            renderer.setRenderHand(previousHand);
            accessor.scopeLens$setMainTarget(main);
            captureRefreshTerrain = false;
            captureSkipsSky = false;
            captureDispatchStages = false;
            capturingWorld = false;
            GlStateManager._restoreGlState(state);
            RenderSystem.setShaderColor(previousShaderColor[0], previousShaderColor[1],
                    previousShaderColor[2], previousShaderColor[3]);
            RenderSystem.setShaderFogColor(previousFogColor[0], previousFogColor[1],
                    previousFogColor[2], previousFogColor[3]);
            RenderSystem.setShaderFogStart(previousFogStart);
            RenderSystem.setShaderFogEnd(previousFogEnd);
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            if (previousShader != null) RenderSystem.setShader(() -> previousShader);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /**
     * 画一遍镜内世界；返回 false 表示这条路线这一帧没能开画（比如切维度首帧 Iris 管线还没就绪）。
     *
     * <p>几条路线的差别只在三处：要不要把 Iris 的着色管线临时换成空管线、要不要把 Iris 自己的
     * 帧缓冲绑定改道到镜内贴图、以及镜内那一遍跳过哪些东西。
     */
    private static boolean drawCapture(GameRenderer renderer, DeltaTracker tracker, RenderTarget capture,
                                       LensCaptureStrategy.Strategy strategy, MinecraftAccessor accessor) {
        boolean swap = shaderOnThisFrame && strategy.isolated();
        if (swap) {
            if (!IrisLensPipelineSwap.enabled()) return false;
            IrisLensPipelineSwap.prepareIsolatedPass();
            if (!IrisLensPipelineSwap.begin()) return false;
        }
        // 开光影时绝不换掉 Minecraft 的主画面目标：Iris 每一遍开始都要按主画面目标的
        // 深度贴图尺寸核对它的整套渲染目标（gbuffer / 各 composite pass），一帧里换两次
        // 就等于每帧把 Iris 的全部渲染目标重建两遍 —— 镜内那一遍的整屏合成会因此带不出
        // 实体、星星和光影最终色调（实机截图里镜内只有地形、还比镜外暗一截）。
        // 所以光影下直接画在主画面上（Iris 的最终合成本来就只输出到主画面），画完再拷进镜内贴图；
        // 主画面随后会被外层那一遍完整重画，不受影响。
        boolean renderIntoMain = shaderOnThisFrame;
        RenderTarget main = renderIntoMain ? Minecraft.getInstance().getMainRenderTarget() : null;
        if (renderIntoMain && main == null) return false;
        try {
            if (!renderIntoMain) accessor.scopeLens$setMainTarget(capture);
            renderer.setRenderHand(false);
            captureRefreshTerrain = strategy.refreshTerrain();
            captureSkipsSky = swap;
            // 镜内那一遍必须放行别的模组的渲染阶段（AFTER_LEVEL 除外）。
            // Voxy 的超视距 LOD 是挂在 Sodium 画 solid/cutout 区块时画的，但还有一批
            // （远距玩家、某些光影兼容路径）走 RenderLevelStageEvent；一跳过，
            // 镜内那张图里超视距就会"有时有、有时没有"。
            // 摘管线那几条路线里 Iris 状态是假的，Voxy 自己会判断 irisShaderPackEnabled
            // 走无光影那条 viewport 设置，所以放行是安全的。
            captureDispatchStages = true;
            if (renderIntoMain) {
                // 主画面目标可能被上一段代码解绑过，这里显式绑一次（含视口）。
                main.bindWrite(true);
            } else {
                capture.bindWrite(true);
                paintBackdrop(capture);
            }
            captureEntitySubmitted = 0;
            captureEntities.clear();
            // 开光影时 Voxy 会在主画面那一遍开头把"主画面矩阵"存进
            // IrisUtil.CAPTURED_VIEWPORT_PARAMETERS；画区块时如果这份还在，
            // 它就直接拿主画面视口画超视距 —— 镜内那一遍就会把没放大的远景
            // 套进小视野里（超视距有时有、有时没有，还有画面冲突）。
            // 这里先清掉，让 Voxy 按这一遍真正的小视野矩阵重新建视口。
            VoxyLensCompat.prepareCapturePass();
            renderer.renderLevel(tracker);
            // 有些实体/方块实体的绘制是通过共享 BufferSource 攒批的（TaCZ 自己的模型、
            // ImmediatelyFast 之类的加速层也一样）。如果某批刚好在这一遍结束时还没落笔，
            // 它就会被下一遍（镜外那一遍）画出去 —— 镜内贴图里就"实体提交数正常、画面上没有"。
            // 这里在小视野矩阵还在的时候强制刷一次，把攒着的几何体落进这一遍的画面里。
            flushPendingBatches();
            if (renderIntoMain) {
                if (main.width != capture.width || main.height != capture.height) return false;
                // 光影下实体常常"提交了却没进画面"（日志里 镜内提交实体=1..7，画面上一个都没有）。
                // 与其继续猜是光影的哪一步吞的，这里在整帧出图之后、拷进镜内贴图之前，
                // 用原版实体着色器单独补画一遍实体：深度用主画面里刚画好的地形，实体和地形对得齐。
                supplementEntities(tracker, main);
                blit(main, capture);
            } else {
                // 无光影：实体走原版通道本来就会进私有贴图，但 Sodium/Voxy 偶发会把远处
                // 实体剔掉。这里按同一套小视野矩阵再补一次，保证镜内一定有实心实体。
                supplementEntities(tracker, capture);
            }
            return true;
        } finally {
            captureRefreshTerrain = false;
            captureSkipsSky = false;
            captureDispatchStages = false;
            if (swap) IrisLensPipelineSwap.end();
        }
    }

    /**
     * 把共享 BufferSource 里攒着的批次立刻画掉（用的是当前这一遍的投影矩阵）。
     * 见 {@link #drawCapture} 里的调用点。任何异常都只记一行日志，绝不让它影响画面。
     */
    private static void flushPendingBatches() {
        try {
            var source = Minecraft.getInstance().renderBuffers().bufferSource();
            if (source != null) source.endBatch();
        } catch (Throwable t) {
            if (!flushWarned) {
                flushWarned = true;
                LOGGER.warn("[scope_lens_zoom] 镜内那一遍刷写待提交批次失败（已忽略）", t);
            }
        }
    }

    private static boolean flushWarned;
    private static boolean entitySupplementWarned;

    /**
     * 光影下"把实体单独画进镜内那张图"的入口。
     *
     * <p>为什么要有这一步：光影那一遍里地形在、实体不在（实机日志一直显示
     * {@code 镜内提交实体=1..7}，画面上却一个都没有）。这说明实体确实交给了渲染器，
     * 只是被光影的批次/整屏合成（或者别的实体渲染优化模组）吞掉了。
     *
     * <p>所以这里不再依赖那条路：整帧出图之后，用 {@link LensEntitySupplement} 把实体
     * 按同一套小视野矩阵、用原版实体着色器再画一遍，深度直接用主画面里刚画好的地形深度。
     * 实体一定会出现在镜片里，而且和地形严格对得上（同一帧、同一相机、同一投影）。
     *
     * <p>这是"保险层"：任何异常都只记一行日志，镜内取景照旧，绝不影响镜外画面。
     */
    private static void supplementEntities(DeltaTracker tracker, RenderTarget main) {
        if (!shaderOnThisFrame || main == null) return;
        float partialTick;
        try {
            partialTick = tracker.getGameTimeDeltaPartialTick(false);
        } catch (Throwable t) {
            partialTick = 0F;
        }
        try {
            LensEntitySupplement.renderIntoMain(captureModelView(), captureProjectionMatrix(),
                    partialTick, main);
        } catch (Throwable t) {
            if (!entitySupplementWarned) {
                entitySupplementWarned = true;
                LOGGER.warn("[scope_lens_zoom] 镜内实体补画出错（已忽略，镜内照旧出图）", t);
            }
        }
    }

    private static void logCaptureSuccess(RenderTarget capture, LensCaptureStrategy.Strategy strategy) {
        if (!okLogged) {
            okLogged = true;
            LOGGER.info("[scope_lens_zoom] 镜内二次渲染成功 {}x{}，倍率 {}，镜内视野 {}°，光影={}，路线={}（{}）",
                    capture.width, capture.height, currentZoom, lensCaptureFov(), shaderOnThisFrame,
                    strategy.name(), strategy.label());
        }
        if (shaderOnThisFrame && ++diagFrames % 240 == 0) {
            LOGGER.info("[scope_lens_zoom] 镜内诊断：光影=开，路线={}，镜内视野={}°，实测投影p11={}，"
                            + "贴图 {}x{}，合成来源={}，镜内提交实体={}，单独补画实体={}",
                    capturedStrategy.name(), lensCaptureFov(), captureProjection(),
                    capture.width, capture.height,
                    // 光影下镜内那一遍是在整帧画完之后、直接画在主画面上，再由 Iris 自己整屏合成，
                    // 最后整张拷进镜内贴图。它和镜外走的是同一套管线、同样的批次时机，
                    // 所以镜内的实体、星星、雾、光影色调都和镜外一致。
                    "主画面拷贝（整帧之后重画，Iris 整屏合成）", captureEntitySubmitted,
                    LensEntitySupplement.lastCount());
        }
    }

    /** 镜内超出渲染距离的区域使用雾色，不用纯黑。 */
    private static void paintBackdrop(RenderTarget capture) {        float[] fog = ShaderLensCompositor.lensFallbackColor();
        capture.setClearColor(fog[0], fog[1], fog[2], 1F);
        capture.clear(Minecraft.ON_OSX);
    }

    public static void copyWorldBeforeHand() {
        // 每帧开镜都要留这份底：低倍镜的"按真实倍率放大"路径、以及取景失败的那几帧
        // 全靠它，不能因为"这一帧小视野取到景了"就省掉（低倍路径就依赖这份拷贝）。
        // 开光影时同样要留：这份是"枪和分划还没画上去"的干净世界画面，
        // 拿它放大不会把 TaCZ 自己的分划一起放大进镜片（双分划）。
        if (!lensModeThisFrame || capturingWorld || colorCopyFrame == frameId) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (main == null) return;
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            ensureTargets(main.width, main.height);
            if (frameCopy == null) return;
            blit(main, frameCopy);
            colorCopyFrame = frameId;
        } catch (RuntimeException e) {
            warnBlit(e);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        }
    }

    private static void blit(RenderTarget from, RenderTarget to) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
    }

    /**
     * 不开光影时的镜内贴图：模板已由 TaCZ 限定，只在模板通过处贴图。
     * 开光影时不能这么做 —— Iris 是在 LevelRenderer 结尾才整屏合成，这里画的会被整屏覆盖，
     * 所以光影下改走 {@link ShaderLensCompositor} 的延后合成（见 {@link #shaderCompositeActive()}）。
     *
     * <p>只按 TaCZ 自己的模板贴，不再自己算任何圆（见 {@link #lensFillAllowed()} 的说明）。
     *
     * @param centerU 分划板（也就是镜片光学中心）在屏幕上的 U 坐标 0~1
     * @param centerV 同上，V 坐标
     */
    public static void blitIntoLens(float centerU, float centerV) {
        if (!lensFillAllowed() || shaderOnThisFrame) return;
        float u = Float.isFinite(centerU) ? Math.clamp(centerU, 0F, 1F) : 0.5F;
        float v = Float.isFinite(centerV) ? Math.clamp(centerV, 0F, 1F) : 0.5F;
        int stencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        int stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        // TaCZ 用"模板值 = 第几层目镜 + 1"标记镜内区域：瞄准镜走取反技巧后是 254，
        // 红点/全息这类没有取反圆的是 1、2、3……；只有 0 表示"镜外"。
        // 注意：真正要贴的是"模板值 != 0"的整个目镜 —— 254 那个内圆之外，
        // TaCZ 还画了一圈黑色目镜遮罩（模板值 1），只贴内圆就会留下那一圈。
        // 这里的判定只是"当前确实处在镜片渲染中"的可用信号，不决定填充范围。
        boolean stencilReady = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
                && stencilFunc == GL11.GL_EQUAL && stencilRef != 0
                && GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK) == 255;
        if (!stencilReady) {
            if (!stencilWarned) {
                stencilWarned = true;
                LOGGER.info("[scope_lens_zoom] 镜片模板未限定为镜内区域，本帧不贴图");
            }
            return;
        }
        RenderTarget target;
        float u0;
        float v0;
        float u1;
        float v1;
        boolean identity;
        if (captureOkThisFrame && capturedFrame == frameId) {
            // 所有倍率：小视野自渲染图 1:1 直贴，原生分辨率、零插值。
            target = worldCapture;
            u0 = 0F;
            v0 = 0F;
            u1 = 1F;
            v1 = 1F;
            identity = true;
        } else if (colorCopyFrame == frameId) {
            // 低倍（红点/全息）或没取到景：把本帧画面按真实倍率放大。
            // 低倍不走 1:1 直贴的原因和光影侧一致：镜片窗口只框住十字线指向的近处地面，
            // 原始纹理像素被整块搬进镜片就是格子线（image-75）。
            target = frameCopy;
            // 以分划板为中心取一块，而不是以屏幕中心：后坐力动画里镜片圆心会离开屏幕中心。
            float half = 0.5F / Math.max(1.02F, currentZoom);
            u0 = u - half;
            u1 = u + half;
            v0 = v - half;
            v1 = v + half;
            // 退路是把整张画面按倍率取一块放大，不是 1:1，只能走老的着色器路径。
            identity = false;
        } else return;
        if (target == null) return;
        if (identity && ShaderLensCompositor.fillLensScreen(target.getColorTextureId(),
                ShaderLensCompositor.lensFallbackColor(), u, v)) {
            // 新路径：原生 GL 直接画进模板区，1:1 取样最清晰。
            return;
        }
        GlStateBackup state = new GlStateBackup();
        GlStateManager._backupGlState(state);
        ShaderInstance previousShader = RenderSystem.getShader();
        int previousTexture = RenderSystem.getShaderTexture(0);
        float[] previousColor = RenderSystem.getShaderColor().clone();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f previousModelView = new Matrix4f(RenderSystem.getModelViewStack());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, target.getColorTextureId());
            // 整个镜片总成都贴（模板值 != 0），把 TaCZ 那圈黑色目镜遮罩也盖掉，
            // 镜内就不会出现"放大画面缩在中间、外面套一圈空白"的样子。
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilMask(0);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            GL11.glStencilFunc(GL11.GL_NOTEQUAL, 0, 255);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0F, 1F, 0F, 1F, -1F, 1F),
                    VertexSorting.ORTHOGRAPHIC_Z);
            // 不 push/pop：保存并恢复栈顶，异常也不会泄漏层数。
            RenderSystem.getModelViewStack().identity();
            RenderSystem.applyModelViewMatrix();
            BufferBuilder builder = RenderSystem.renderThreadTesselator()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.addVertex(0F, 0F, 0F).setUv(u0, v0);
            builder.addVertex(1F, 0F, 0F).setUv(u1, v0);
            builder.addVertex(1F, 1F, 0F).setUv(u1, v1);
            builder.addVertex(0F, 1F, 0F).setUv(u0, v1);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        } catch (RuntimeException e) {
            warnBlit(e);
        } finally {
            RenderSystem.getModelViewStack().set(previousModelView);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            if (previousShader != null) RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            GlStateManager._restoreGlState(state);
        }
    }

    private static void warnBlit(RuntimeException e) {
        if (!blitWarned) {
            blitWarned = true;
            LOGGER.warn("[scope_lens_zoom] 镜内画面复制或贴图失败", e);
        }
    }
}
