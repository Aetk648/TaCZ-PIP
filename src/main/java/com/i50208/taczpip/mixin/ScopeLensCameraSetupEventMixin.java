package com.i50208.taczpip.mixin;

import com.i50208.taczpip.client.LensZoomConfig;
import com.i50208.taczpip.client.LensZoomState;
import com.tacz.guns.client.event.CameraSetupEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管 TaCZ 的两处 FOV 修改（ComputeFov 不可取消，所以用「入口缓存、出口改写」）：
 *
 * <ul>
 *   <li><b>applyScopeMagnification（世界视野）</b>：
 *     主世界遍一律改回原版视野 —— 开镜时整个画面绝不跟着倍率拉近，镜框外大小不变；
 *     只有"用缩小视野单独画镜内画面"的那一遍，才把视野换成
 *     {@link LensZoomState#lensCaptureFov()}（真正放大的小视野）。
 *     这一步以前是"放行 TaCZ 的数值"，但 TaCZ 的 FOV 带二阶平滑滤波，
 *     主画面每帧把它拉回原版后，镜内那遍也被一起拉回了原版 ——
 *     于是镜内画面和镜外一模一样、等于没有放大。现在这一遍的视野完全由本模组计算。</li>
 *   <li><b>applyGunModelFovModifying（手部视野）</b>：
 *     只影响枪模型，把镜框钉成屏幕上一圈固定大小（配置 pinnedHandFov，默认 40°），
 *     10 倍 20 倍都一样大，不会撑满整个屏幕。世界/相机视角一点没动。</li>
 * </ul>
 *
 * remap=false：目标是 TaCZ 自身的类与方法；require=0：TaCZ 更新导致挂不上时退回原生行为而不是崩溃。
 */
@Mixin(value = CameraSetupEvent.class, remap = false)
public abstract class ScopeLensCameraSetupEventMixin {

    /** TaCZ 动手之前的世界视野（= 原版视野），只由主世界遍更新。 */
    @Inject(method = "applyScopeMagnification", at = @At("HEAD"), require = 0)
    private static void scopeLens$captureBaseWorldFov(
            net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event, CallbackInfo ci) {
        if (!event.usedConfiguredFov()) {
            return; // 只处理世界视野，手部那个事件不管
        }
        if (LensZoomState.capturingWorld) {
            return; // 镜内那一遍不改写基准值
        }
        LensZoomState.baseWorldFov = (float) event.getFOV();
    }

    @Inject(method = "applyScopeMagnification", at = @At("RETURN"), require = 0)
    private static void scopeLens$applyWorldFov(
            net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event, CallbackInfo ci) {
        if (!event.usedConfiguredFov()) {
            return;
        }
        if (LensZoomState.capturingWorld) {
            // 镜内那一遍：用我们算出来的真实小视野，这才叫放大。
            event.setFOV(LensZoomState.lensCaptureFov());
            return;
        }
        if (LensZoomState.worldFovLocked()) {
            // 主世界遍：钉死在原版视野，同时把 TaCZ 的平滑滤波目标也拨回原版，
            // 这样关镜时不会出现"画面弹回来"的过渡。
            CameraSetupEvent.WORLD_FOV_DYNAMICS.update(LensZoomState.baseWorldFov);
            event.setFOV(LensZoomState.baseWorldFov);
        }
    }

    @Inject(method = "applyGunModelFovModifying", at = @At("RETURN"), require = 0)
    private static void scopeLens$pinHandFov(
            net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event, CallbackInfo ci) {
        if (event.usedConfiguredFov()) {
            return; // 只处理手部视野
        }
        if (LensZoomState.capturingWorld) {
            return;
        }
        float weight = LensZoomState.aimPinWeight();
        if (weight <= 0F) {
            return;
        }
        // 只把枪模型往眼睛方向拉近，镜框固定在屏幕上一圈；世界视角完全不受影响。
        // 按开镜进度加权：开镜/收镜过程里是渐变，模型不会在一帧里突然放大或缩小
        // （硬开关会让瞄具模型边缘被近裁剪面切开，屏幕上出现一大块黑色一闪而过）。
        float pinned = LensZoomConfig.pinnedHandFov();
        float current = (float) event.getFOV();
        float blended = weight >= 1F ? pinned : current + (pinned - current) * weight;
        CameraSetupEvent.ITEM_MODEL_FOV_DYNAMICS.update(blended);
        event.setFOV(blended);
    }
}
