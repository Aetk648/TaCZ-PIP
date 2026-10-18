package com.i50208.taczpip.client;

import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 光影下的镜内合成时机。
 *
 * <p>光影的最终画面是在世界渲染那一遍的最后才由 Iris 合成出来的，谁在这之前往主缓冲画，
 * 都会被随后那一次全屏合成盖掉。而 HUD 是在世界出完图之后才画的。</p>
 *
 * <p>所以这里卡在「世界已经是最终画面、HUD 还没开始画」的缝隙里，
 * 把镜内画面贴上去：既不会被光影盖掉，也不会盖住 HUD。</p>
 */
public final class ScopeLensGuiOverlay {

    private ScopeLensGuiOverlay() {
    }

    public static void onGuiPre(RenderGuiEvent.Pre event) {
        // 正常路径是 GameRenderer.render 里那个注入点（它比这里早，并且带着 renderer/tracker，
        // 光影下才能在整帧画完之后再补一遍镜内小视野）。这里只是兜底：
        // 没有 renderer/tracker 就不做那遍取景，免得在错误的时机重画世界。
        ShaderLensCompositor.composite(null, null);
    }
}
