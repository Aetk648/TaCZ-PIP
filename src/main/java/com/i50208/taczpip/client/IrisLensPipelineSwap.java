package com.i50208.taczpip.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * “只在镜内那一遍关闭光影”的软实现。
 *
 * <p>Iris 内部按维度持有一个渲染管线对象：光影包生效时它是 IrisRenderingPipeline（着色管线），
 * 而 Iris 自带的 VanillaRenderingPipeline 就是“没选光影包”时用的那份空管线。Sodium、Voxy、
 * 原版着色器绑定的每一处分支都在问同一句“当前管线是不是 Iris 的着色管线”。
 * 于是镜内那一遍开始前，把 Iris 的 PipelineManager 里当前维度的管线临时换成这份空管线，
 * 画完立刻换回来 —— 镜内就等价于“没开光影”的完整世界渲染（Sodium 内置地形着色器、
 * 原版天空、实体、天气、Voxy 超视距 LOD 全部照常进镜内），而镜外主画面完全不受影响。
 *
 * <p>不修改光影包、不调任何会触发重载的方法、不销毁任何管线对象；换入换出只发生在
 * 一次 renderLevel（以及镜片贴图那几行 GL 调用）的窗口内，异常时本帧直接放弃镜内贴图，
 * 连续失败则整个机制关闭并退回“放大最终画面”的老退路。全部通过反射访问，
 * Iris（含 net.coderbot 命名空间的移植版）不在场时零开销。
 */
public final class IrisLensPipelineSwap {
    private static final Logger LOGGER = LoggerFactory.getLogger("scope_lens_zoom");
    private static final String[] NAMESPACES = {"net.irisshaders.iris", "net.coderbot.iris"};

    private static Method getPipelineManager;
    private static Method getCurrentDimension;
    private static Field managerPipelineField;
    private static Field managerMapField;
    private static Object vanillaPipeline;
    /** 无光影时的区块顶点格式实例（未开光影时 Sodium 用的那份）。 */
    private static Object compactVertexFormatSnapshot;

    /** 本帧 Iris 的着色管线是否已经被换成了无光影管线。 */
    public static boolean active() {
        return active;
    }

    /** 换入窗口内的暂存，end() 后失效。 */
    private static Object activeManager;
    private static Object savedKey;
    private static Object savedEntry;
    private static Object savedFieldPipeline;
    private static boolean active;

    private static boolean probed;
    private static boolean ready;
    private static int failures;
    private static boolean loggedOk;

    private IrisLensPipelineSwap() { }

    /**
     * 隔离窗口开始前的状态修正（每次都做，开销是几次 GL 调用）。
     *
     * <p>镜内那一遍是靠 Sodium 自己的地形着色器画区块的，而那个着色器用的不是
     * RenderSystem 那套贴图槽，而是 Sodium 内部记录的“方块图集编号 / 光照贴图编号”。
     * 这两个编号是 Sodium 在贴图上载时记下来的；开着光影时区块一直走光影着色器，
     * 没人验证过它们还指不指着当前那张贴图 —— 一旦指到空纹理，采样结果就是精确的
     * (0,0,0)：地形全黑、实体和天空却完全正常（这正是实机画面里的样子）。
     * 所以这里每次都按“当前真的在用的那张贴图”把 Sodium 的记录校正一遍。
     *
     * <p>顺带把当前着色程序强制归零（绕开 GlStateManager 的缓存并把它同步成 0），
     * 免得某一帧 Sodium 绑定自己的地形着色器时被“看起来已经绑好了”挡掉。
     */
    public static void prepareIsolatedPass() {
        repairSodiumChunkTextures();
        resyncTextureUnits();
        resetCurrentProgram();
    }

    private static boolean repaired;
    private static boolean repairLogged;
    private static int liveBlockTextureId;
    private static int liveLightTextureId;

    private static void repairSodiumChunkTextures() {
        if (repaired) return;
        repaired = true;
        try {
            Class<?> util = Class.forName("net.caffeinemc.mods.sodium.client.util.TextureUtil");
            var mc = net.minecraft.client.Minecraft.getInstance();
            int block = mc.getTextureManager()
                    .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getId();
            int light = ((com.i50208.taczpip.mixin.LightTextureAccessor)
                    mc.gameRenderer.lightTexture()).scopeLens$getTexture().getId();
            liveBlockTextureId = block;
            liveLightTextureId = light;
            boolean blockOk = setTrackedId(util, block,
                    new String[]{"setBlockTextureId", "setBlockTexture"},
                    new String[]{"blockTextureId", "blockTexture"});
            boolean lightOk = setTrackedId(util, light,
                    new String[]{"setLightTextureId", "setLightTexture"},
                    new String[]{"lightTextureId", "lightTexture"});
            if (!repairLogged) {
                repairLogged = true;
                LOGGER.info("[scope_lens_zoom] 镜内地形贴图校正：方块图集={}（{}），光照贴图={}（{}）",
                        block, blockOk ? "已写入" : "未找到入口",
                        light, lightOk ? "已写入" : "未找到入口");
            }
        } catch (Throwable t) {
            if (!repairLogged) {
                repairLogged = true;
                LOGGER.info("[scope_lens_zoom] 镜内地形贴图校正跳过（{}: {}）",
                        t.getClass().getSimpleName(), t.getMessage());
            }
        }
    }

    /** 先试 setter，再试静态字段；都找不到返回 false（不报错，不影响别的功能）。 */
    private static boolean setTrackedId(Class<?> owner, int id, String[] methods, String[] fields) {
        for (String name : methods) {
            try {
                java.lang.reflect.Method m = owner.getDeclaredMethod(name, int.class);
                m.setAccessible(true);
                m.invoke(null, id);
                return true;
            } catch (Throwable ignored) { }
        }
        for (String name : fields) {
            try {
                Field f = owner.getDeclaredField(name);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && !java.lang.reflect.Modifier.isFinal(f.getModifiers())
                        && f.getType() == int.class) {
                    f.setAccessible(true);
                    f.setInt(null, id);
                    return true;
                }
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /**
     * 把 0 号（方块图集）和 1 号（光照贴图）纹理单元“先归零、再绑成当前真在用的那张”。
     *
     * <p>两个目的：一，万一 GlStateManager 的贴图缓存和真实绑定长期对不上（光影/别的模组直接
     * 调过 GL），Sodium 那次“看起来已经绑好了”的绑定会被跳过，镜内就会采样到别的贴图上；
     * 先绑 0 再绑目标就能强制落地一次真实绑定。二，即使 Sodium 自己记录的编号是旧的，
     * 这一步也会把正确的那张放上去。
     */
    private static void resyncTextureUnits() {
        if (liveBlockTextureId == 0 || liveLightTextureId == 0) return;
        try {
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(liveBlockTextureId);
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(liveLightTextureId);
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        } catch (Throwable ignored) { }
    }

    private static void resetCurrentProgram() {
        try {
            org.lwjgl.opengl.GL20.glUseProgram(0);
            com.mojang.blaze3d.platform.GlStateManager._glUseProgram(0);
        } catch (Throwable ignored) { }
    }

    /** 探测 + 构造空管线（一次性）。成功才允许镜内走“关光影重渲染”。 */
    public static boolean enabled() {
        if (!LensZoomConfig.shadersLensCapture()) return false;
        if (!probed) probe();
        return ready;
    }

    private static void probe() {
        probed = true;
        try {
            Class<?> irisClass = null;
            String pkg = null;
            for (String candidate : NAMESPACES) {
                try {
                    irisClass = Class.forName(candidate + ".Iris");
                    pkg = candidate;
                    break;
                } catch (ClassNotFoundException ignored) { }
            }
            if (irisClass == null) return;
            getPipelineManager = irisClass.getMethod("getPipelineManager");
            getCurrentDimension = irisClass.getMethod("getCurrentDimension");

            Class<?> managerClass = getPipelineManager.getReturnType();
            managerPipelineField = managerClass.getDeclaredField("pipeline");
            managerMapField = managerClass.getDeclaredField("pipelinesPerDimension");
            managerPipelineField.setAccessible(true);
            managerMapField.setAccessible(true);

            // VanillaRenderingPipeline 的构造器会顺手改写 WorldRenderingSettings 的几个全局字段
            // （区块顶点格式、AO、实体分离绘制等）。构造一次，立刻原样快照还原，
            // 保证光影包自己的网格设置全程不动。
            Class<?> vanillaClass = Class.forName(pkg + ".pipeline.VanillaRenderingPipeline");
            Class<?> settingsClass = Class.forName(pkg + ".shaderpack.materialmap.WorldRenderingSettings");
            Object settings = settingsClass.getField("INSTANCE").get(null);
            Field[] fields = settingsClass.getDeclaredFields();
            Object[] snapshot = new Object[fields.length];
            boolean[] captured = new boolean[fields.length];
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                snapshot[i] = f.get(settings);
                captured[i] = true;
            }
            vanillaPipeline = vanillaClass.getDeclaredConstructor().newInstance();
            try {
                Field vertexFormatField = settingsClass.getDeclaredField("chunkVertexFormat");
                vertexFormatField.setAccessible(true);
                compactVertexFormatSnapshot = vertexFormatField.get(settings);
            } catch (NoSuchFieldException ignored) {
                compactVertexFormatSnapshot = null;
            }
            for (int i = 0; i < fields.length; i++) {
                if (!captured[i]) continue;
                Field f = fields[i];
                f.setAccessible(true);
                f.set(settings, snapshot[i]);
            }
            // 管线能建起来不等于这条路画得出来：光影包若用了扩展区块顶点格式，
            // Sodium 的无光影地形着色器按紧凑步长读顶点，区块必然读成垃圾/全黑。
            // 这里提前判定，省掉开局那几帧"画一张黑图再读回来测一遍"的无用功。
            Object format = currentChunkVertexFormat();
            if (!extendedFormatCompatible(format)) {
                ready = false;
                LOGGER.info("[scope_lens_zoom] 光影包使用扩展区块顶点格式（{}）：镜内画面由光影那一遍"
                        + "整屏合成提供，不再尝试摘管线重渲染", describeFormat(format));
                return;
            }
            ready = true;
            LOGGER.info("[scope_lens_zoom] Iris 管线隔离已就绪：镜内将按“无光影”完整重渲染（含 Sodium/Voxy）");
        } catch (Throwable t) {
            ready = false;
            LOGGER.warn("[scope_lens_zoom] Iris 管线隔离探测失败，光影下沿用最终画面放大退路", t);
        }
    }

    /** 开始镜内/贴图窗口。返回 false 表示本帧没换（或不能换），调用方本帧就不要画镜内画面。 */
    public static boolean begin() {
        if (!ready || active) return false;
        Object format = currentChunkVertexFormat();
        if (!extendedFormatCompatible(format)) {
            // 光影包要的是扩展顶点格式，Sodium 的无光影地形着色器按紧凑步长读顶点，读不了这种网格。
            // 这不是"将就"，光影下镜内就该由光影自己那一遍整屏合成出图（同一套管线，
            // 实体、天空、雾、色调全都在），所以直接不再尝试摘管线重渲染。
            ready = false;
            LOGGER.info("[scope_lens_zoom] 当前光影包使用扩展区块顶点格式（{}），"
                    + "镜内画面改由光影那遍整屏合成提供（不摘管线）", describeFormat(format));
            return false;
        }
        if (!doSwap()) return false;
        if (!loggedOk) {
            loggedOk = true;
            LOGGER.info("[scope_lens_zoom] [镜内] Iris 着色管线已临时摘掉，本帧镜内按“无光影”渲染"
                    + "（区块走 Sodium 内置地形着色器，顶点格式={}）", describeFormat(format));
        }
        return true;
    }

    /**
     * 只给"单独补画实体"开一个极短的隔离窗口。
     *
     * <p>和 {@link #begin()} 的区别：这里<b>不检查区块顶点格式</b>。那个检查是为了让 Sodium 的
     * 无光影地形着色器能读光影包的网格，而这里根本不画地形 —— 只画实体。实体走的是原版
     * RenderType / 原版实体着色器，和区块顶点格式无关。所以光影包就算要求扩展顶点格式，
     * 实体这一遍照样可以按"无光影"画出来（见 {@link LensEntitySupplement}）。
     */
    public static boolean beginForEntities() {
        if (active) return false;
        if (!probed) probe();
        if (vanillaPipeline == null || getPipelineManager == null) return false;
        return doSwap();
    }

    /** 把当前维度的管线临时换成 Iris 自带的"无光影"管线。成功返回 true，必须配对调用 {@link #end()}。 */
    private static boolean doSwap() {
        if (active) return false;
        try {
            Object manager = getPipelineManager.invoke(null);
            Object key = getCurrentDimension.invoke(null);
            @SuppressWarnings("unchecked")
            Map<Object, Object> perDimension = (Map<Object, Object>) managerMapField.get(manager);
            Object entry = perDimension.get(key);
            if (entry == null || entry == vanillaPipeline) {
                // 本维度的管线还没建好（切维度首帧之类）：本帧不动，等 Iris 自己准备好。
                return false;
            }
            activeManager = manager;
            savedKey = key;
            savedEntry = entry;
            savedFieldPipeline = managerPipelineField.get(manager);
            perDimension.put(key, vanillaPipeline);
            managerPipelineField.set(manager, vanillaPipeline);
            active = true;
            return true;
        } catch (Throwable t) {
            active = false;
            noteFailure(t);
            return false;
        }
    }

    /** 结束窗口，原样放回真实管线。必须在 finally 里调用。 */
    public static void end() {
        if (!active) return;
        active = false;
        try {
            @SuppressWarnings("unchecked")
            Map<Object, Object> perDimension = (Map<Object, Object>) managerMapField.get(activeManager);
            if (savedKey != null && savedEntry != null) perDimension.put(savedKey, savedEntry);
            managerPipelineField.set(activeManager, savedFieldPipeline);
        } catch (Throwable t) {
            noteFailure(t);
        }
        activeManager = null;
        savedKey = null;
        savedEntry = null;
        savedFieldPipeline = null;
    }

    /**
     * 光影包要求的区块顶点格式能不能用 Sodium 自己的（无光影）地形着色器画出来。
     *
     * <p>只有像素布局和步长<b>完全一样</b>才行。之前的判断是"步长 ≥ 20 就行"，理由是
     * "Sodium 内置着色器只读前 20 字节" —— 这个理由是错的：顶点属性指针是按步长走的，
     * 步长 24/28 时 Sodium 会把上一个顶点的扩展字段当成下一个顶点的位置/颜色/UV 读，
     * 区块于是画成一团垃圾或全黑。这就是"把 Iris 管线摘掉重渲染"那条路线开了光影必然全黑
     * （实机日志里纯黑=100%）的原因，不是环境差异，也不是偶发。
     *
     * <p>所以这里只在步长正好等于紧凑格式（20 字节）时才允许走隔离路线；
     * 光影包要了 blockId / normal 之类的扩展字段时就干脆别试，直接用光影直出那一遍。
     */
    private static boolean extendedFormatCompatible(Object format) {
        if (format == null) return true;
        if (compactVertexFormatSnapshot != null && format == compactVertexFormatSnapshot) return true;
        try {
            Object vertexFormat = format.getClass().getMethod("getVertexFormat").invoke(format);
            int stride = (int) vertexFormat.getClass().getMethod("getStride").invoke(vertexFormat);
            return stride == 20;
        } catch (Throwable ignored) {
            return false; // 探不到步长就别冒险：隔离路线画出来的可能是全黑
        }
    }

    private static Object currentChunkVertexFormat() {
        try {
            Class<?> settingsClass = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
            Object settings = settingsClass.getField("INSTANCE").get(null);
            return settingsClass.getMethod("getVertexFormat").invoke(settings);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describeFormat(Object format) {
        if (format == null) return "未知";
        try {
            Object stride = format.getClass().getMethod("getStride").invoke(
                    format.getClass().getMethod("getVertexFormat").invoke(format));
            return format.getClass().getSimpleName() + "/步长" + stride;
        } catch (Throwable ignored) {
            return format.getClass().getSimpleName();
        }
    }

    private static void noteFailure(Throwable t) {
        if (++failures <= 3) {
            LOGGER.warn("[scope_lens_zoom] Iris 管线隔离调用失败（第 {} 次），本帧镜内画面跳过", failures, t);
        }
        if (failures > 20) {
            ready = false;
            LOGGER.warn("[scope_lens_zoom] Iris 管线隔离连续失败过多，本次运行退回“最终画面放大”方案");
        }
    }
}
