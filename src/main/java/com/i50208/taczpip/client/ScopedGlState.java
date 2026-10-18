package com.i50208.taczpip.client;

import org.lwjgl.opengl.*;

/** 私有合成器只用原生 GL；离开作用域时精确恢复现场，不污染 Minecraft 的状态缓存。 */
final class ScopedGlState implements AutoCloseable {
    private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int buffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] textures = new int[3];
    private final int[] samplers = new int[3];
    private final int[] viewport = new int[4];
    private final int drawBuffers = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
    private final boolean[][] colorMasks = new boolean[drawBuffers][4];
    private final boolean[] blendEnabled = new boolean[drawBuffers];
    private final int[][] indexedBlend = new int[drawBuffers][];
    private final boolean hasIndexedBlend = GL.getCapabilities().OpenGL40
            || GL.getCapabilities().GL_ARB_draw_buffers_blend;
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
    private final int frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
    private final float[] clearColor = new float[4];
    private final int[] scissorBox = new int[4];
    private final int[] capabilities = {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE,
            GL11.GL_STENCIL_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_COLOR_LOGIC_OP,
            GL30.GL_FRAMEBUFFER_SRGB, GL30.GL_RASTERIZER_DISCARD};
    private final boolean[] enabled = new boolean[capabilities.length];
    private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
    private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final int[] front = stencil(false);
    private final int[] back = stencil(true);

    ScopedGlState() {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            for (int target = 0; target < drawBuffers; target++) {
                GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, target, mask);
                for (int i = 0; i < 4; i++) colorMasks[target][i] = mask.get(i) != 0;
                blendEnabled[target] = GL30.glIsEnabledi(GL11.GL_BLEND, target);
                if (hasIndexedBlend) {
                    indexedBlend[target] = new int[]{
                            GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB, target),
                            GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB, target),
                            GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA, target),
                            GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA, target),
                            GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_RGB, target),
                            GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_ALPHA, target)};
                }
            }
        }
        for (int i = 0; i < capabilities.length; i++) enabled[i] = GL11.glIsEnabled(capabilities[i]);
        for (int i = 0; i < textures.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i);
        }
        GL13.glActiveTexture(activeTexture);
    }

    private static int[] stencil(boolean back) {
        int[] keys = back ? new int[]{GL20.GL_STENCIL_BACK_FUNC, GL20.GL_STENCIL_BACK_REF,
                GL20.GL_STENCIL_BACK_VALUE_MASK, GL20.GL_STENCIL_BACK_WRITEMASK,
                GL20.GL_STENCIL_BACK_FAIL, GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL,
                GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS}
                : new int[]{GL11.GL_STENCIL_FUNC, GL11.GL_STENCIL_REF, GL11.GL_STENCIL_VALUE_MASK,
                GL11.GL_STENCIL_WRITEMASK, GL11.GL_STENCIL_FAIL, GL11.GL_STENCIL_PASS_DEPTH_FAIL,
                GL11.GL_STENCIL_PASS_DEPTH_PASS};
        for (int i = 0; i < keys.length; i++) keys[i] = GL11.glGetInteger(keys[i]);
        return keys;
    }

    static void prepareDraw() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        GL11.glColorMask(true, true, true, true);
        GL33.glBindSampler(0, 0);
        GL33.glBindSampler(1, 0);
        GL33.glBindSampler(2, 0);
    }

    @Override
    public void close() {
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
        for (int i = 0; i < textures.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
            GL33.glBindSampler(i, samplers[i]);
        }
        GL13.glActiveTexture(activeTexture);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        restoreStencil(GL11.GL_FRONT, front);
        restoreStencil(GL11.GL_BACK, back);
        GL11.glDepthMask(depthMask);
        GL11.glDepthFunc(depthFunc);
        GL11.glCullFace(cullFace);
        GL11.glFrontFace(frontFace);
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        for (int i = 0; i < capabilities.length; i++) {
            if (enabled[i]) GL11.glEnable(capabilities[i]);
            else GL11.glDisable(capabilities[i]);
        }
        // Iris 的多个颜色附件可以有各自的混合和写入掩码，不能只恢复第零个。
        for (int target = 0; target < drawBuffers; target++) {
            boolean[] mask = colorMasks[target];
            GL30.glColorMaski(target, mask[0], mask[1], mask[2], mask[3]);
            if (blendEnabled[target]) GL30.glEnablei(GL11.GL_BLEND, target);
            else GL30.glDisablei(GL11.GL_BLEND, target);
            if (hasIndexedBlend) {
                int[] blend = indexedBlend[target];
                if (GL.getCapabilities().OpenGL40) {
                    GL40.glBlendFuncSeparatei(target, blend[0], blend[1], blend[2], blend[3]);
                    GL40.glBlendEquationSeparatei(target, blend[4], blend[5]);
                } else {
                    ARBDrawBuffersBlend.glBlendFuncSeparateiARB(target, blend[0], blend[1], blend[2], blend[3]);
                    ARBDrawBuffersBlend.glBlendEquationSeparateiARB(target, blend[4], blend[5]);
                }
            }
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    private static void restoreStencil(int face, int[] s) {
        GL20.glStencilFuncSeparate(face, s[0], s[1], s[2]);
        GL20.glStencilMaskSeparate(face, s[3]);
        GL20.glStencilOpSeparate(face, s[4], s[5], s[6]);
    }
}
