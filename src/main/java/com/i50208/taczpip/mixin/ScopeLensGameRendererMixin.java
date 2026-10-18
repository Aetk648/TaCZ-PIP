package com.i50208.taczpip.mixin;

import com.i50208.taczpip.client.LensZoomState;
import com.i50208.taczpip.client.ShaderLensCompositor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 渲染钩子，都在 {@link GameRenderer} 上：
 * <ul>
 *   <li>render 开头：判定本帧的开镜进度、倍率与镜片模式，清理上一帧临时数据。</li>
 *   <li>HUD 投影之前：光影最终画面只在已保存的真实镜片区域内放大。</li>
 *   <li>renderItemInHand 开头：无光影镜内取景（这一帧的区块列表和 Voxy 超视距都齐了），
 *       同时备份"枪还没画上去"的干净世界画面作为退路。二次渲染期间
 *       {@code capturingWorld} 为 true，立刻返回，避免递归。</li>
 *   <li>renderLevel 里的 AFTER_LEVEL 阶段：镜内那一遍不派发，避免别的模组一帧画两遍。</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class ScopeLensGameRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void scopeLens$beginFrame(DeltaTracker tracker, boolean clearColor, CallbackInfo ci) {
        LensZoomState.updateFrameState(Minecraft.getInstance());
    }

    /** HUD 投影设置在世界和后处理之后，即使 F1 隐藏界面也会经过这里。 */
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Window;getGuiScale()D", ordinal = 0))
    private void scopeLens$compositeBeforeGui(DeltaTracker tracker, boolean clearColor, CallbackInfo ci) {
        ShaderLensCompositor.composite((GameRenderer) (Object) this, tracker);
    }

    /**
     * 无光影的镜内取景放在画手之前：这一帧的区块列表和 Voxy 超视距都已经准备好了。
     * 放在 renderLevel 开头会用到上一帧的列表，超视距就会时有时无。
     * {@code capturingWorld} 为 true 时立刻返回，避免递归。
     */
    @Inject(method = "renderItemInHand(Lnet/minecraft/client/Camera;FLorg/joml/Matrix4f;)V",
            at = @At("HEAD"), require = 0)
    private void scopeLens$copyWorldBeforeHand(Camera camera, float partialTick, Matrix4f projection,
                                               CallbackInfo ci) {
        LensZoomState.copyWorldBeforeHand();
        if (!LensZoomState.capturingWorld) {
            LensZoomState.captureZoomedWorld((GameRenderer) (Object) this,
                    Minecraft.getInstance().getTimer());
        }
    }

    /**
     * 镜内那一遍（二次渲染）里不要触发别的模组的 AFTER_LEVEL 渲染阶段：
     * 一帧跑两遍会让它们的后处理/帧缓冲互相踩，镜外画面就会闪。
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
    private static void scopeLens$skipAfterLevelInCapture(
            RenderLevelStageEvent.Stage stage, LevelRenderer renderer, PoseStack poseStack,
            Matrix4f frustumMatrix, Matrix4f projectionMatrix, int ticks,
            Camera camera, Frustum frustum) {
        if (LensZoomState.capturingWorld) {
            return;
        }
        ClientHooks.dispatchRenderStage(stage, renderer, poseStack, frustumMatrix, projectionMatrix,
                ticks, camera, frustum);
    }
}
