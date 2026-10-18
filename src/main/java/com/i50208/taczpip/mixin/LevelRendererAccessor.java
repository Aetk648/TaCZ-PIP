package com.i50208.taczpip.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 取 LevelRenderer 里那张"虚空黑盘"（darkBuffer），以及两个私有的每帧准备方法。
 * <p>
 * 原版只有玩家眼睛低于地平线高度（海平面 63 格）时才画黑盘，作用是把世界底下的虚空涂黑。
 * 但镜内那一遍是用很小的视野再画一次：超出渲染距离的远处地面本来就没画，于是这张
 * 纯黑盘就露了出来 —— 这就是"镜内看远处地面下方一片死黑"的真正原因。
 * 镜内放大时我们跳过它，让那一片露出本帧雾色（和原版"远处糊成雾"一致）。
 * <p>
 * {@code setupRender} / {@code compileSections} 是每帧更新区块可见性图和重建区块的地方。
 * 一帧里跑两遍会让远处的区块逐帧闪进闪出（整屏闪烁），所以镜内那一遍跳过它们，
 * 直接复用主画面那一遍算好的可见区块列表。
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("darkBuffer")
    VertexBuffer scopeLens$getDarkBuffer();

    @Invoker("setupRender")
    void scopeLens$setupRender(Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean isSpectator);

    @Invoker("compileSections")
    void scopeLens$compileSections(Camera camera);
}
