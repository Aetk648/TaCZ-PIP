package com.i50208.taczpip.mixin;

import com.i50208.taczpip.client.LensZoomState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 记录"镜内那一遍"真正交给渲染器的实体名单（顺带计数），并在光影下把这遍的实体绘制
 * 摘给单独补画通道独家负责。
 *
 * <p>光影下镜内实体不透明的前提，是单独补画通道知道**该画谁**。自己重新剔除不可靠
 * （Sodium/Voxy 会改写实体剔除路径），所以直接抄渲染器自己的提交记录：
 * 它画了谁，镜片里就补谁。计数用来分开两种毛病：
 * 0 → 实体在镜内那一遍就没被提交；&gt;0 而画面没有 → 被光影批次/整屏合成吞掉。
 *
 * <p>更进一步：名单抄下来之后，**直接取消光影自己这一遍的实体绘制**。
 * 光影包在镜内第二遍里经常只画出写了深度的半透明轮廓（本体颜色进不了它的整屏合成），
 * 补画的实心实体还会被这层轮廓的深度挡住 —— 实机就是"中距离镜内实体透明"
 * 和"开枪镜内实体透明"。摘掉这一层，镜片里就只剩补画通道那一层实心不透明实体。
 * 什么时候能摘见 {@link LensZoomState#takeOverCaptureEntities()}。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ScopeLensEntityCounterMixin {

    @Inject(method = "render("
            + "Lnet/minecraft/world/entity/Entity;DDDFF"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void scopeLens$countCaptureEntity(Entity entity, double x, double y, double z,
                                              float rotationYaw, float partialTick,
                                              com.mojang.blaze3d.vertex.PoseStack poseStack,
                                              net.minecraft.client.renderer.MultiBufferSource buffers,
                                              int packedLight, CallbackInfo ci) {
        LensZoomState.noteCaptureEntity(entity);
        if (LensZoomState.capturingWorld && !LensZoomState.supplementDrawing
                && LensZoomState.takeOverCaptureEntities()) {
            ci.cancel();
        }
    }
}
