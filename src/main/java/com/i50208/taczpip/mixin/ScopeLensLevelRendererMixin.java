package com.i50208.taczpip.mixin;

import com.i50208.taczpip.client.LensZoomState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 镜内二次渲染那一遍的三处"别再画一遍 / 别冻坏别的模组"：
 *
 * <ul>
 *   <li><b>跳过区块可见性图与区块重建</b>（{@code setupRender} / {@code compileSections}）：
 *     原版这两个方法每帧用当前视野更新 {@code SectionOcclusionGraph} 并推进区块重建。
 *     一帧里跑两遍（先小视野、再正常视野）会把那张增量更新的可见性图搅乱，
 *     远处区块就会逐帧闪进闪出 —— 这正是"高倍镜瞄远处整个屏幕闪烁"的元凶之一。
 *     镜内那一遍直接复用主画面那一遍算好的区块列表。</li>
 *   <li><b>跳过别的模组的渲染阶段事件</b>（{@code RenderLevelStageEvent}）：
 *     它们大多自带帧缓冲与后处理链，跑两遍就会互相踩。</li>
 *   <li><b>跳过虚空黑盘</b>：见 {@link LevelRendererAccessor}，解决镜内远处死黑。</li>
 * </ul>
 *
 * require = 0：万一别的版本把方法结构改了，就退回原版行为，不会崩游戏。
 */
@Mixin(LevelRenderer.class)
public abstract class ScopeLensLevelRendererMixin {

    /** 世界遍开头记下投影矩阵：时域超分要按它换算"相机转一点 = 画面挪几像素"。 */
    @Inject(method = "renderLevel("
            + "Lnet/minecraft/client/DeltaTracker;Z"
            + "Lnet/minecraft/client/Camera;"
            + "Lnet/minecraft/client/renderer/GameRenderer;"
            + "Lnet/minecraft/client/renderer/LightTexture;"
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD"), require = 0)
    private void scopeLens$stashWorldProjection(DeltaTracker tracker, boolean renderScopes,
                                                Camera camera, GameRenderer gameRenderer,
                                                LightTexture lightTexture, Matrix4f first,
                                                Matrix4f second, CallbackInfo ci) {
        LensZoomState.stashWorldProjection(first, second);
    }

    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender("
                             + "Lnet/minecraft/client/Camera;"
                             + "Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V"),
            require = 0)
    private static void scopeLens$keepSectionGraph(LevelRenderer instance, Camera camera,
                                                   Frustum frustum, boolean hasCapturedFrustum,
                                                   boolean isSpectator) {
        if (LensZoomState.capturingWorld && !LensZoomState.captureAllowsTerrainSetup()) {
            return; // 镜内那一遍：别动可见性图，也别让远处区块重建
        }
        ((LevelRendererAccessor) instance).scopeLens$setupRender(camera, frustum,
                hasCapturedFrustum, isSpectator);
    }

    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;compileSections("
                             + "Lnet/minecraft/client/Camera;)V"),
            require = 0)
    private static void scopeLens$keepChunkCompiling(LevelRenderer instance, Camera camera) {
        if (LensZoomState.capturingWorld && !LensZoomState.captureAllowsTerrainSetup()) {
            return; // 镜内那一遍：区块重建一帧只跑一次（在主画面那一遍里跑）
        }
        ((LevelRendererAccessor) instance).scopeLens$compileSections(camera);
    }

    /**
     * 镜内那一遍里别的模组的渲染阶段事件要不要派发。
     *
     * <p>镜内那一遍必须放行除 AFTER_LEVEL 之外的阶段：Voxy 超视距、远距玩家、
     * 以及那些把实体攒起来一次性画的模组，都挂在这些阶段上。跳过它们，
     * 镜内那张图里超视距就会"有时有、有时没有"。
     * AFTER_LEVEL 是整屏后处理，一帧跑两遍没有任何好处，还可能把别的模组的画面搞花。
     * 放行部分整体包在 try/catch 里 —— 镜内那一遍属于"画不出来就退回原样"的路径，
     * 绝不能因为某个模组在这里抛异常就把游戏带崩。
     */
    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                     target = "Lnet/neoforged/neoforge/client/ClientHooks;dispatchRenderStage("
                             + "Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent$Stage;"
                             + "Lnet/minecraft/client/renderer/LevelRenderer;"
                             + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                             + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;I"
                             + "Lnet/minecraft/client/Camera;"
                             + "Lnet/minecraft/client/renderer/culling/Frustum;)V"),
            require = 0)
    private static void scopeLens$dispatchStages(
            RenderLevelStageEvent.Stage stage, LevelRenderer renderer, PoseStack poseStack,
            Matrix4f frustumMatrix, Matrix4f projectionMatrix, int ticks,
            Camera camera, Frustum frustum) {
        if (LensZoomState.capturingWorld
                && (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL
                    || !LensZoomState.captureDispatchesModStages())) {
            return; // 镜内那一遍：整屏后处理永远跳过，其余按路线决定
        }
        try {
            ClientHooks.dispatchRenderStage(stage, renderer, poseStack, frustumMatrix,
                    projectionMatrix, ticks, camera, frustum);
        } catch (RuntimeException | LinkageError e) {
            if (!LensZoomState.capturingWorld) throw e;
            scopeLens$noteStageFailure(e);
        }
    }

    @Unique
    private static boolean scopeLens$stageFailureLogged;

    @Unique
    private static void scopeLens$noteStageFailure(Throwable e) {
        if (scopeLens$stageFailureLogged) return;
        scopeLens$stageFailureLogged = true;
        org.slf4j.LoggerFactory.getLogger("scope_lens_zoom").warn(
                "[scope_lens_zoom] 镜内那一遍派发别的模组渲染阶段时出错（已忽略，不影响镜外画面）", e);
    }

    @Redirect(method = "renderSky",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader("
                             + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;"
                             + "Lnet/minecraft/client/renderer/ShaderInstance;)V"),
            require = 0)
    private static void scopeLens$skipVoidDisc(VertexBuffer buffer, Matrix4f modelView,
                                               Matrix4f projection, ShaderInstance shader) {
        if (LensZoomState.capturingWorld) {
            LevelRenderer levelRenderer = Minecraft.getInstance().levelRenderer;
            if (levelRenderer != null
                    && buffer == ((LevelRendererAccessor) levelRenderer).scopeLens$getDarkBuffer()) {
                return; // 镜内那一遍不画虚空黑盘，远处地面露出雾色而不是死黑
            }
        }
        buffer.drawWithShader(modelView, projection, shader);
    }

    /**
     * 开光影时，镜内那一遍不画天空。
     *
     * <p>光影包把天空拆成了 gbuffer 里的几个颜色输出，真正的天空色要等它的整屏合成才出来；
     * 而镜内那一遍只做世界渲染、跑的是 Iris 的空管线，根本不会执行那步合成 ——
     * 天空就会变成一片纯黑盖在镜头上半部分。这里直接跳过，让镜内背景保持本模组清好的雾色，
     * 视觉上就是原版"远处糊成雾"的样子。不开光影时这条完全不生效。
     */
    @Inject(method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void scopeLens$skipSkyInCapture(Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                            float partialTick, Camera camera, boolean isFoggy,
                                            Runnable setupFog, CallbackInfo ci) {
        if (LensZoomState.captureSkipsSky()) {
            ci.cancel();
        }
    }
}
