package com.i50208.taczpip.mixin;

import com.i50208.taczpip.client.LensZoomState;
import com.i50208.taczpip.client.ReticleRenderer;
import com.i50208.taczpip.client.ShaderLensCompositor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * 在 TaCZ 的真实模板区域（模板缓冲里的镜片圆）准备好之后：
 * 光影下把分划板顶点和模板一起截下来，等 Iris 整屏出图后由合成器贴；
 * 无光影下直接把小视野世界图 1:1 贴进模板区。
 *
 * <p>这里不再自己算「镜片圆」：早先版本的补圆是靠目镜节点的屏幕投影算出来的，
 * 收镜/后坐力那几帧会算歪，屏幕上会糊出一个巨大的黑圆盘。
 * 镜内边界一律以 TaCZ 自己画的模板为准。
 *
 * <p>同时负责把"分划板在屏幕上的位置"（也就是镜片光学中心）交给合成器：
 * 小视野那一遍是以屏幕中心（相机光轴）为准渲染的，后坐力动画里镜片圆心会离开屏幕中心，
 * 不做这一步画面就和交叉线错开。
 *
 * <p>两个入口都要接管，否则不同瞄具只能顾到一种：
 * <ul>
 *   <li>{@code renderOcularAndDivision}：带倍率的瞄准镜（模板值 254，TaCZ 用取反圆挖出镜片圆）；</li>
 *   <li>{@code renderDivisionOnly}：红点/全息这类"只有一块玻璃 + 分划"的（模板值 1、2、3……）。
 *       以前没接这一条，所以小倍率瞄具在镜内完全没有放大 —— 它们的数据里其实写了倍率。</li>
 * </ul>
 */
@Mixin(value = BedrockAttachmentModel.class, remap = false)
public abstract class ScopeLensBedrockAttachmentModelMixin {
    @Shadow
    protected List<List<BedrockPart>> ocularNodePaths;

    @Shadow
    protected List<List<BedrockPart>> divisionNodePaths;

    @Shadow
    private float scopeViewRadiusModifier;

    @Shadow
    private void renderTempPart(PoseStack poseStack, ItemDisplayContext transformType,
                                RenderType renderType, int light, int overlay, List<BedrockPart> path) { }

    @Unique
    private static boolean scopeLens$warned;

    /** 瞄准镜：TaCZ 在"镜片圆 ∩ 目镜范围"里画分划板，这里就是镜内该贴图的地方。 */
    @Redirect(method = "renderOcularAndDivision("
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lnet/minecraft/client/renderer/RenderType;IIZ)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/BedrockAttachmentModel;renderTempPart("
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/world/item/ItemDisplayContext;"
                            + "Lnet/minecraft/client/renderer/RenderType;IILjava/util/List;)V",
                    ordinal = 2), require = 1)
    private void scopeLens$onLensMaskReady(BedrockAttachmentModel model, PoseStack stack,
                                           ItemDisplayContext context, RenderType type,
                                           int light, int overlay, List<BedrockPart> path) {
        if (LensZoomState.lensFillAllowed() && context.firstPerson()
                && scopeLens$applyLens(stack, context, type, light, overlay, path)) {
            // 光影下：分划板已经被截走，等整屏出图后由合成器连同放大画面一起画；
            // 无光影下：画面已经贴进模板区，分划板照常由 TaCZ 画在上面。
            return;
        }
        renderTempPart(stack, context, type, light, overlay, path);
    }

    /** 红点/全息这类"只有玻璃 + 分划"的瞄具（以前完全没接管，所以镜内没有放大）。 */
    @Redirect(method = "renderDivisionOnly("
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/BedrockAttachmentModel;renderTempPart("
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/world/item/ItemDisplayContext;"
                            + "Lnet/minecraft/client/renderer/RenderType;IILjava/util/List;)V",
                    ordinal = 0), require = 0)
    private void scopeLens$onSightDivisionReady(BedrockAttachmentModel model, PoseStack stack,
                                               ItemDisplayContext context, RenderType type,
                                               int light, int overlay, List<BedrockPart> path) {
        if (LensZoomState.lensFillAllowed() && context.firstPerson()
                && scopeLens$applyLens(stack, context, type, light, overlay, path)) {
            return;
        }
        renderTempPart(stack, context, type, light, overlay, path);
    }

    /**
     * 光影下：截走分划板顶点 + 光学中心，交给延后合成；
     * 无光影下：现在就把小视野世界图贴进模板区。
     *
     * <p>贴图的锚点改用**分划板几何自己投到屏幕上的包围盒中心**。
     * 以前锚的是"目镜节点"的投影：红点/全息这类瞄具根本没有配对的目镜节点
     * （或节点装在镜体别的部位），投影点和玩家真正用来瞄准的红点差出一截。
     * 镜内画面中心（= 相机光轴 = 子弹飞行方向）被搬到那个错误的锚点上，
     * 玩家对着分划板瞄头、子弹却打到脚 —— 倍率越低、镜内视野越宽，同样的像素
     * 偏差折算成角度越大，所以这个毛病只在低倍瞄具上明显。
     *
     * @return true 表示这一次"分划板不用 TaCZ 自己画了"，调用方直接返回
     */
    @Unique
    private boolean scopeLens$applyLens(PoseStack stack, ItemDisplayContext context, RenderType type,
                                        int light, int overlay, List<BedrockPart> path) {
        float[] optical = scopeLens$opticalCenter(stack, path);
        float[] triangles = scopeLens$reticleTriangles(stack, context, type, light, overlay, path);
        float[] anchor = scopeLens$reticleAnchor(triangles, optical);
        // 有光影 / 无光影都把分划板截走、等 HUD 之前再贴：
        // 无光影以前是在这一瞬间立刻贴，后坐力把模板打乱的那几帧就会漏贴（透明圈）。
        if (triangles != null) {
            int texture = scopeLens$reticleTexture(type);
            if (texture > 0 && ShaderLensCompositor.capture(triangles, texture, anchor[0], anchor[1])) {
                return true;
            }
        }
        if (!LensZoomState.shaderCompositeActive()) {
            LensZoomState.blitIntoLens(anchor[0], anchor[1]);
        }
        return false;
    }

    /** 把分划板几何抓成一份独立的三角形顶点（失败返回 null，不碰 TaCZ 的共享批次）。 */
    @Unique
    private float[] scopeLens$reticleTriangles(PoseStack stack, ItemDisplayContext context, RenderType type,
                                               int light, int overlay, List<BedrockPart> path) {
        if (path.isEmpty()) return null;
        try {
            Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            ReticleRenderer vertices = new ReticleRenderer(projection, modelView);
            PoseStack snapshot = new PoseStack();
            snapshot.last().pose().set(stack.last().pose());
            snapshot.last().normal().set(stack.last().normal());
            for (int i = 0; i < path.size() - 1; i++) path.get(i).translateAndRotateAndScale(snapshot);
            BedrockPart part = path.get(path.size() - 1);
            boolean visible = part.visible;
            try {
                part.visible = true;
                part.render(snapshot, context, vertices, light, overlay);
            } finally {
                part.visible = visible;
            }
            float[] triangles = vertices.triangles();
            return triangles.length == 0 ? null : triangles;
        } catch (RuntimeException e) {
            if (!scopeLens$warned) {
                scopeLens$warned = true;
                org.slf4j.LoggerFactory.getLogger("scope_lens_zoom")
                        .warn("[scope_lens_zoom] 捕获准星顶点失败，保留原准星", e);
            }
            return null;
        }
    }

    /**
     * 从当前 RenderType 读取瞄具真正使用的贴图，不依赖 Iris 批次结束后残留的 Sampler0。
     * 只读取已有纹理，不启动 RenderType，也不冲刷共享 BufferSource。
     */
    @Unique
    private int scopeLens$reticleTexture(RenderType type) {
        try {
            if (type instanceof RenderTypeAccessor renderTypeAccessor) {
                var state = (CompositeStateAccessor) (Object) renderTypeAccessor.scopeLens$getState();
                var location = ((TextureStateAccessor) state.scopeLens$getTextureState()).scopeLens$getTexture();
                if (location.isPresent()) {
                    return net.minecraft.client.Minecraft.getInstance().getTextureManager()
                            .getTexture(location.get()).getId();
                }
            }
        } catch (RuntimeException ignored) {
            // 读不到贴图就照常交给 TaCZ 画。
        }
        return 0;
    }

    /**
     * 分划板几何在屏幕上的包围盒中心（0~1，v 向上）—— 玩家真正用来瞄准的那个点。
     *
     * <p>顶点是裁剪空间坐标（x, y, 0, w），透视除 w 后取包围盒。抓不到几何、
     * 数值不正常、或和光学中心差出大半屏（说明抓到的不是分划板本身）时退回光学中心。
     */
    @Unique
    private static float[] scopeLens$reticleAnchor(float[] triangles, float[] optical) {
        if (triangles == null || triangles.length < 30) return optical;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i + 3 < triangles.length; i += 10) {
            float w = triangles[i + 3];
            if (w <= 1.0E-5F) continue;
            float x = triangles[i] / w;
            float y = triangles[i + 1] / w;
            if (!Float.isFinite(x) || !Float.isFinite(y)) continue;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        if (maxX <= minX || maxY <= minY) return optical;
        float u = (minX + maxX) * 0.25F + 0.5F;
        float v = (minY + maxY) * 0.25F + 0.5F;
        if (!Float.isFinite(u) || !Float.isFinite(v)) return optical;
        if (Math.abs(u - optical[0]) > 0.4F || Math.abs(v - optical[1]) > 0.4F) return optical;
        return new float[]{Math.clamp(u, 0F, 1F), Math.clamp(v, 0F, 1F)};
    }

    /**
     * 镜片光学中心（= 分划板中心）在屏幕上的位置，0~1。
     *
     * <p>取目镜节点在屏幕上的投影：同一个 index 的目镜节点和分划板节点是配对的（TaCZ 自己就这么配对）。
     * 拿不到就退回屏幕中心 —— 宁可不偏移，也不能乱偏。
     */
    @Unique
    private float[] scopeLens$opticalCenter(PoseStack stack, List<BedrockPart> divisionPath) {
        try {
            int index = divisionNodePaths.indexOf(divisionPath);
            if (index >= 0 && index < ocularNodePaths.size()) {
                Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
                Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
                PoseStack ocular = new PoseStack();
                ocular.last().pose().set(stack.last().pose());
                for (BedrockPart node : ocularNodePaths.get(index)) node.translateAndRotateAndScale(ocular);
                Vector4f center = new Vector4f(0, 0, 0, 1);
                center.mul(ocular.last().pose()).mul(modelView).mul(projection);
                if (center.w > 1.0E-5F) {
                    return new float[]{center.x / center.w * 0.5F + 0.5F,
                            center.y / center.w * 0.5F + 0.5F};
                }
            }
        } catch (RuntimeException ignored) {
            // 计算光学中心失败不影响画面本身：退回屏幕中心。
        }
        return new float[]{0.5F, 0.5F};
    }
}
