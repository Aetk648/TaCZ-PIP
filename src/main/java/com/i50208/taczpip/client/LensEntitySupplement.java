package com.i50208.taczpip.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 光影下"镜内单独补画一遍实体"。
 *
 * <p>为什么必须单独画：光影（Iris + 光影包）在镜内那一遍里，地形（Sodium 区块着色器）是正常的，
 * 实体却一直不出现 —— 日志里 `镜内提交实体=1..7` 说明实体确实交给了渲染器，
 * 但它们要么落在光影自己的延迟通道里被整屏合成漏掉，要么被别的实体渲染优化模组在半路吞掉。
 * 与其继续猜是哪一层吞的，这里直接绕开：**这一遍由本模组自己把实体画到镜内那张图上**。
 *
 * <p>做法是把 Iris 的着色管线在一个极短的窗口内临时换成它自带的"无光影"管线
 * （{@link IrisLensPipelineSwap#beginForEntities()}），于是所有实体 RenderType 拿到的是
 * **原版实体着色器**，画进当前绑定的主画面、并且按主画面里已有的地形深度做遮挡。
 * 画完立刻把管线换回去，镜外的画面一点不受影响。
 *
 * <p>为什么不是"用光影的实体着色器再画一次"：那样还是回到同一条会丢实体的路上。
 * 原版着色器的观感和光影包略有差别（少了些后处理），但实体、掉落物、盔甲架、手上的枪
 * 一定能在镜内看见 —— 这正是玩家要的那件事。
 */
final class LensEntitySupplement {

    private static final Logger LOGGER = LoggerFactory.getLogger("scope_lens_zoom");

    /** 连续失败太多次就彻底关掉，别让镜内取景每帧都在同一个地方炸。 */
    private static boolean disabled;
    private static boolean failureLogged;
    private static boolean successLogged;
    private static int failures;
    private static int lastCount;
    /** 镜内这一遍视野里"应该有"的实体数（补画前统计），用来判断"该看见却一个都没画出来"。 */
    private static int lastInView;
    private static ByteBufferBuilder sharedBuffer;
    /**
     * 最近一次摘管线画实体是否成功过（粘滞）。
     *
     * <p>镜内那一遍"取消光影自己画实体"的前提是这条通道真的画得出来：
     * 第一次成功之前、或换管线失败后，都不摘 —— 宁可由光影画（可能透明），
     * 也不能把实体摘没了又补不出来（镜子里空着）。
     */
    private static boolean swapConfirmed;

    private LensEntitySupplement() { }

    /** 镜内那一遍能不能放心把实体摘给这条通道独家负责（见 LensZoomState.takeOverCaptureEntities）。 */
    static boolean willSuppressIrisEntities() {
        return !disabled && swapConfirmed;
    }

    /** 上一次补画真正提交了多少个实体（写进诊断日志用）。 */
    static int lastCount() {
        return lastCount;
    }

    /** 镜内视野里本该看见的实体数；大于 0 而这帧一个都没画出来，说明这条通道没生效。 */
    static int lastInView() {
        return lastInView;
    }

    /**
     * 把实体补画进当前绑定的主画面（镜内那一遍刚刚画完、地形深度还在的时候）。
     *
     * @param modelView   镜内那一遍用的视图矩阵（相机旋转，含视角晃动），把它原样用上才能和地形对齐
     * @param projection  镜内那一遍用的投影矩阵（就是"小视野"，所以补画的实体也是放大的）
     * @param partialTick 本帧插值时间，实体位置要靠它和地形同一帧
     * @param target      画进哪张图：光影下是主画面，无光影是镜内私有贴图
     * @return 真正提交了多少个实体；0 表示这一帧没画（关掉了 / 出错 / 画面里没实体）
     */
    static int renderIntoMain(Matrix4f modelView, Matrix4f projection, float partialTick,
                              com.mojang.blaze3d.pipeline.RenderTarget target) {
        lastCount = 0;
        lastInView = 0;
        if (disabled || modelView == null || projection == null) return 0;
        if (LensZoomState.shaderOnThisFrame && !LensZoomConfig.shadersLensEntities()) return 0;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.levelRenderer == null) return 0;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return 0;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        var main = target != null ? target : mc.getMainRenderTarget();
        if (dispatcher == null || main == null || main.width <= 0 || main.height <= 0) return 0;

        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;
        // 名单来源：镜内那一遍**真正提交给渲染器**的实体，由
        // ScopeLensEntityCounterMixin 抄录。早先在这里自己重建视锥、还检查"区块编译状态"，
        // 结果在 Sodium/Voxy 的实体剔除路径面前把所有实体都错剔了 —— 日志里
        // "镜内提交实体=3，单独补画实体=0" 就是这么来的。渲染器画过谁就补谁，不再自作聪明。
        List<Entity> visible = new ArrayList<>();
        it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<Entity> chosen =
                new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>(64);
        Entity camEntity = camera.getEntity();
        for (Entity entity : LensZoomState.captureEntities()) {
            if (entity == null || !chosen.add(entity)) continue;
            if (entity == camEntity && !camera.isDetached()) continue;
            if (entity.isInvisible()) continue;
            // 不再二次做 shouldRender 距离判定：名单里的实体已经通过了镜内那一遍
            // 拉大过的可见距离。这里再判一次会把远处实体错杀，镜片里就只剩轮廓。
            visible.add(entity);
        }
        // 把镜外主画面那一遍提交过的实体并进来。镜内那一遍的视锥剔除、
        // 以及 EntityCulling 这类模组的遮挡剔除，偶发会把中距离实体错杀 ——
        // 玩家在镜外明明看得见它，镜内名单里却没有，补画不画、光影那层又是透明轮廓，
        // 就是"不近不远才透明"。凡是镜外画过、又落在镜内小视野里的，一律补画。
        Frustum frustum = null;
        for (Entity entity : LensZoomState.mainPassEntities()) {
            if (entity == null || visible.size() >= 128 || !chosen.add(entity)) continue;
            if (entity == camEntity && !camera.isDetached()) continue;
            if (entity.isInvisible()) continue;
            if (frustum == null) {
                frustum = new Frustum(modelView, projection);
                frustum.prepare(camX, camY, camZ);
            }
            if (!frustum.isVisible(entity.getBoundingBoxForCulling().inflate(0.5))) continue;
            visible.add(entity);
        }
        if (visible.isEmpty()) {
            // 两份名单都没记到东西（比如某个模组绕开 dispatcher 自己画）时的兜底：
            // 按小视野视锥走一遍，但**不再**检查区块编译、也不用原版 64 格距离。
            Frustum scanFrustum = new Frustum(modelView, projection);
            scanFrustum.prepare(camX, camY, camZ);
            for (Entity entity : level.entitiesForRendering()) {
                if (entity == camEntity && !camera.isDetached()) continue;
                if (entity instanceof LocalPlayer && mc.player != entity) continue;
                if (entity.isInvisible()) continue;
                if (!scanFrustum.isVisible(entity.getBoundingBoxForCulling().inflate(0.5))) continue;
                visible.add(entity);
            }
        }
        lastInView = visible.size();
        if (visible.isEmpty()) {
            // 镜片里本来就没有实体，那就不用动任何状态了。
            return 0;
        }

        GlStateBackup state = new GlStateBackup();
        GlStateManager._backupGlState(state);
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        Matrix4f previousModelView = new Matrix4f();
        previousModelView.set(RenderSystem.getModelViewStack());
        ShaderInstance previousShader = RenderSystem.getShader();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        float previousFogStart = RenderSystem.getShaderFogStart();
        float previousFogEnd = RenderSystem.getShaderFogEnd();
        int previousTexture0 = RenderSystem.getShaderTexture(0);
        boolean swapped = false;
        boolean vertexState = false;
        int count = 0;
        try {
            // 1) 开光影时让 Iris 在这一小段里交出原版着色器（镜外光影不受影响）。
            //    没开光影就不用换：本来就是原版着色器。
            boolean shaders = LensZoomState.shaderOnThisFrame;
            swapped = shaders && IrisLensPipelineSwap.beginForEntities();
            if (shaders && !swapped) {
                // 换不过去就干脆别画：这时 RenderType 拿到的还是光影的实体着色器，
                // 画出来只会写进光影的 gbuffer（镜头里依然看不到），还可能搅乱这一帧的合成。
                noteSwapUnavailable();
                return 0;
            }
            if (swapped) {
                // 摘管线成功过：从这一帧起镜内那一遍可以放心把实体摘给这条通道独家画
                // （见 ScopeLensEntityCounterMixin / LensZoomState.takeOverCaptureEntities）。
                swapConfirmed = true;
            }
            // 2) 顶点格式回到原版（不开光影时的那份），否则原版实体着色器按扩展步长读顶点会画歪。
            vertexState = IrisVertexBridge.begin();
            main.bindWrite(true);
            GL11.glViewport(0, 0, main.width, main.height);
            // 3) 状态摆成"原版画实体"该有的样子：深度测试开着，用这一遍已经画好的地形深度做遮挡。
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glColorMask(true, true, true, true);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_CULL_FACE);            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            Matrix4fStack stack = RenderSystem.getModelViewStack();
            stack.clear();
            stack.mul(modelView);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            // 4) 把"半透明实体类型"换成不透明版本再落笔：盔甲架底座、史莱姆、药水效果
            //    这类走 entity_translucent 的几何体，原版是带 alpha 混合画的 —— 在镜内
            //    那遍它们底下垫的是光影画出来的半透明轮廓，混完就是玩家看到的"能看见轮廓、
            //    本体透明"。这里统一换成 entity_cutout：同一个纹理、不混合、直接盖实在。
            //    雾也关掉：镜内画面拷进贴图后还要过一层着色器，叠两遍雾会发灰。
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            // 多边形偏移往近处拉一点：镜内那一遍的光影实体轮廓可能已经写了几乎同值的深度，
            // 不加偏移的话补画的实体内部会被自己的轮廓深度挡掉 —— 又是"只剩轮廓"。
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            // 偏移再拉近一点：开枪那几帧光影会把实体轮廓深度写得很近，
            // -1 经常不够，补画的实体内部就被挡掉，看起来又是透明。
            GL11.glPolygonOffset(-4.0F, -4.0F);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();

            MultiBufferSource.BufferSource buffers = new OpaqueBufferSource(sharedBuffer(),
                    new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<>());
            PoseStack poseStack = new PoseStack();
            // 这段时间放行实体渲染器：镜内那一遍的"摘实体"钩子不能把补画自己的绘制也取消掉。
            LensZoomState.supplementDrawing = true;
            try {
                for (Entity entity : visible) {
                    double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - camX;
                    double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - camY;
                    double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - camZ;
                    float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
                    int light = dispatcher.getPackedLightCoords(entity, partialTick);
                    poseStack.pushPose();
                    try {
                        dispatcher.render(entity, x, y, z, yaw, partialTick, poseStack, buffers, light);
                        count++;
                    } finally {
                        poseStack.popPose();
                    }
                }
                buffers.endBatch();
            } finally {
                LensZoomState.supplementDrawing = false;
            }
            lastCount = count;
            if (count > 0) {
                successLogged = true;
                failures = 0;
            }
            return count;
        } catch (Throwable t) {
            captureFailure(t);
            return 0;
        } finally {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(0.0F, 0.0F);
            RenderSystem.setShaderFogStart(previousFogStart);
            RenderSystem.setShaderFogEnd(previousFogEnd);
            if (vertexState) IrisVertexBridge.end();
            if (swapped) IrisLensPipelineSwap.end();
            GlStateManager._restoreGlState(state);
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            Matrix4fStack stack = RenderSystem.getModelViewStack();
            stack.clear();
            stack.mul(previousModelView);
            RenderSystem.applyModelViewMatrix();
            if (previousShader != null) RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderTexture(0, previousTexture0);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            main.bindWrite(true);
            GL11.glViewport(0, 0, main.width, main.height);
            // 光影路径画在主画面上：深度已经被我们写过实体了，原版进 HUD 之前本来就会清一次。
            // 这里清回去，免得 HUD 被镜内实体挡住。无光影画在私有贴图上，主画面深度不能动。
            if (swapped && LensZoomState.shaderOnThisFrame) {
                GL11.glDepthMask(true);
                GL11.glClearDepth(1.0D);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            }
        }
    }

    private static ByteBufferBuilder sharedBuffer() {
        if (sharedBuffer == null) sharedBuffer = new ByteBufferBuilder(8192);
        return sharedBuffer;
    }

    private static void noteSwapUnavailable() {
        // 这次摘不动管线：下一帧镜内那一遍就继续让光影自己画实体，
        // 否则摘了实体又补不出来，镜子里就什么都没有了。
        swapConfirmed = false;
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.info("[scope_lens_zoom] 这一次没能临时摘掉 Iris 管线，本帧镜内实体由光影自己那一遍提供");
        }
    }

    private static void captureFailure(Throwable t) {
        swapConfirmed = false;
        if (++failures < 20) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[scope_lens_zoom] 镜内实体补画失败（本帧镜内只有光影自己的画面），"
                        + "后续会继续尝试", t);
            }
        } else {
            disabled = true;
            LOGGER.warn("[scope_lens_zoom] 镜内实体补画连续失败过多，本次运行关闭这条通道");
        }
    }

    /** 诊断/日志用：这条通道现在还在跑吗。 */
    static boolean active() {
        return !disabled;
    }

    static boolean everSucceeded() {
        return successLogged;
    }

    /**
     * 把半透明的实体 RenderType 换成不透明版本的 BufferSource。
     *
     * <p>只认名字里带 translucent / decal / alpha 的实体类型，换成同纹理的 entity_cutout：
     * 顶点格式一致（NEW_ENTITY），纹理一致，唯一区别是不再按 alpha 混合、而是该丢的丢、
     * 该实的实。其余类型（cutout、solid、glint 等）原样透传。
     */
    private static final class OpaqueBufferSource extends MultiBufferSource.BufferSource {
        OpaqueBufferSource(ByteBufferBuilder shared,
                           java.util.SequencedMap<net.minecraft.client.renderer.RenderType, ByteBufferBuilder> fixed) {
            super(shared, fixed);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type) {
            return super.getBuffer(opaqueVariant(type));
        }
    }

    private static RenderType opaqueVariant(RenderType type) {
        try {
            if (!(type instanceof com.i50208.taczpip.mixin.RenderTypeAccessor renderTypeAccessor)) {
                return type;
            }
            // RenderType 继承自 RenderStateShard，name 是它的 public final 字段，直接读。
            String name = type.name;
            if (name == null) return type;
            boolean translucent = name.contains("translucent")
                    || name.equals("entity_decal") || name.equals("entity_alpha");
            if (!translucent) return type;
            RenderStateShard.EmptyTextureStateShard textureState =
                    ((com.i50208.taczpip.mixin.CompositeStateAccessor) (Object)
                            renderTypeAccessor.scopeLens$getState()).scopeLens$getTextureState();
            java.util.Optional<net.minecraft.resources.ResourceLocation> texture =
                    ((com.i50208.taczpip.mixin.TextureStateAccessor) (Object) textureState)
                            .scopeLens$getTexture();
            if (texture == null || texture.isEmpty()) return type;
            return RenderType.entityCutoutNoCull(texture.get());
        } catch (Throwable t) {
            // 认不出来就别换：宁可这个实体还是半透明，也不能让整条补画通道崩掉。
            return type;
        }
    }

    /**
     * Iris 的原版顶点格式开关（{@code net.irisshaders.iris.vertices.ImmediateState}）。
     *
     * <p>开着光影时 Iris 会把实体顶点格式扩展成带 blockId / 切线之类字段的版本，
     * 那是给它自己的实体着色器用的。这里要用原版着色器，所以必须在建 buffer 之前
     * 把"扩展顶点格式"关掉，画完立刻还原。全部反射访问，Iris 不在场时什么都不做。
     */
    private static final class IrisVertexBridge {
        private static boolean probed;
        private static boolean ready;
        private static java.lang.reflect.Field extendedFormatField;
        private static java.lang.reflect.Field skipExtensionField;
        private static Boolean savedSkip;
        private static Boolean savedThreadLocal;

        private IrisVertexBridge() { }

        @SuppressWarnings("unchecked")
        static boolean begin() {
            if (!probed) probe();
            if (!ready) return false;
            try {
                savedSkip = extendedFormatField.getBoolean(null);
                extendedFormatField.setBoolean(null, false);
                java.lang.reflect.Field field = skipExtensionField;
                if (field != null) {
                    Object value = field.get(null);
                    if (value instanceof ThreadLocal<?> local) {
                        ThreadLocal<Boolean> threadLocal = (ThreadLocal<Boolean>) local;
                        savedThreadLocal = threadLocal.get();
                        threadLocal.set(Boolean.TRUE);
                    }
                }
                return true;
            } catch (Throwable t) {
                ready = false;
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        static void end() {
            try {
                if (savedSkip != null) extendedFormatField.setBoolean(null, savedSkip);
                if (savedThreadLocal != null && skipExtensionField != null) {
                    Object value = skipExtensionField.get(null);
                    if (value instanceof ThreadLocal<?> local) {
                        ((ThreadLocal<Boolean>) local).set(savedThreadLocal);
                    }
                }
            } catch (Throwable ignored) {
                // 还原失败最多让下一帧的顶点格式歪一次，不影响游戏。
            }
            savedSkip = null;
            savedThreadLocal = null;
        }

        private static void probe() {
            probed = true;
            for (String namespace : new String[]{"net.irisshaders.iris", "net.coderbot.iris"}) {
                try {
                    Class<?> clazz = Class.forName(namespace + ".vertices.ImmediateState");
                    extendedFormatField = clazz.getField("renderWithExtendedVertexFormat");
                    java.lang.reflect.Field skip;
                    try {
                        skip = clazz.getField("skipExtension");
                    } catch (NoSuchFieldException ignored) {
                        skip = null;
                    }
                    skipExtensionField = skip;
                    ready = true;
                    return;
                } catch (Throwable ignored) {
                    // 换下一个命名空间。
                }
            }
        }
    }
}
