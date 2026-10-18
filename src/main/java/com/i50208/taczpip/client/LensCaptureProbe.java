package com.i50208.taczpip.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * 镜内那张自渲染贴图的“体检”：画完之后把它缩到 64×40 读回来，看看里面到底有没有东西。
 *
 * <p>为什么需要这个：开光影时镜内那一遍要穿过 Iris 和 Sodium 的一堆内部状态，
 * 有时候“日志说成功了”但画面里地形其实是一整块死黑（实体和天空照常，所以肉眼一打眼
 * 看不出是哪一步坏）。用一次 GPU 缩放 + 读回（只有 10KB）就能客观判断：
 * 纯黑像素占比过高、或者整张图平得像一张纸且很暗，就说明这一遍没画出地形。
 *
 * <p>只在开局自检的那几帧执行，定下路线后完全不再读回，不影响帧率。
 */
final class LensCaptureProbe {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 40;

    private static int framebuffer;
    private static int texture;
    private static ByteBuffer pixels;
    private static boolean unavailable;

    private LensCaptureProbe() { }

    /** 一张镜内贴图的体检结果，全部是 0~1 的比例。 */
    record Stats(float mean, float deviation, float pureBlack, float darkLower, float darkUpper) {
        String describe() {
            return String.format(java.util.Locale.ROOT,
                    "均值=%.3f 起伏=%.3f 纯黑=%.0f%% 下半黑=%.0f%% 上半黑=%.0f%%",
                    mean, deviation, pureBlack * 100F, darkLower * 100F, darkUpper * 100F);
        }
    }

    /** 量一张贴图；量不了（没有 GL / 尺寸不对）返回 null。 */
    static Stats measure(RenderTarget target) {
        if (unavailable || target == null || target.width <= 0 || target.height <= 0) return null;
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            ensure();
            // 私有小 FBO 的读写缓冲都指向它唯一的附件。
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30.glBlitFramebuffer(0, 0, target.width, target.height, 0, 0, WIDTH, HEIGHT,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            pixels.clear();
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            return analyze();
        } catch (Throwable t) {
            unavailable = true;
            return null;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
    }

    /**
     * 这张图是不是“没画出东西”。
     *
     * <p>两条判据，都取自实机截图：坏掉时地形是纯 (0,0,0) —— 光照贴图或方块图集被绑成空纹理时
     * 采样结果就是精确的 0，而正常的天空/雾/夜晚再怎么暗也不会大片像素精确等于 0。
     * 另一条是整张图又平又暗（说明除了一片底色什么都没画）。
     */
    static boolean looksBroken(Stats stats) {
        if (stats == null) return false; // 量不了就不做判断，交给实机表现
        // 判据一：大片“精确的 0,0,0”。正常的天空/雾/夜晚，采样结果都不会精确等于 0，
        // 只有贴图被绑成空纹理时才这样，这是实机截图里最硬的一条证据。
        if (stats.pureBlack > 0.30F) return true;
        // 判据二：整张图又平又暗，说明除了一片底色什么都没画出来。
        return stats.deviation < 0.0035F && stats.mean < 0.10F;
    }

    private static void ensure() {
        if (framebuffer != 0) return;
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("镜内体检帧缓冲不完整: 0x" + Integer.toHexString(status));
        }
        pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
    }

    private static Stats analyze() {
        float sum = 0F;
        float sumSquares = 0F;
        int pureBlack = 0;
        int darkLower = 0;
        int darkUpper = 0;
        int half = HEIGHT / 2;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int i = (y * WIDTH + x) * 4;
                int r = pixels.get(i) & 0xFF;
                int g = pixels.get(i + 1) & 0xFF;
                int b = pixels.get(i + 2) & 0xFF;
                float luma = (0.2126F * r + 0.7152F * g + 0.0722F * b) / 255F;
                sum += luma;
                sumSquares += luma * luma;
                boolean black = r == 0 && g == 0 && b == 0;
                if (black) pureBlack++;
                if (luma < 0.03F) {
                    if (y < half) darkLower++; else darkUpper++;
                }
            }
        }
        int total = WIDTH * HEIGHT;
        float mean = sum / total;
        float variance = Math.max(0F, sumSquares / total - mean * mean);
        return new Stats(mean, (float) Math.sqrt(variance),
                pureBlack / (float) total,
                darkLower / (float) (total / 2),
                darkUpper / (float) (total / 2));
    }
}
