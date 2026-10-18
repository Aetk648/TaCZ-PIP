package com.i50208.taczpip;

import com.i50208.taczpip.client.LensZoomConfig;
import com.i50208.taczpip.client.ScopeLensGuiOverlay;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * TaCZ 附属客户端模组：把"瞄具全屏幕放大"改成"只有瞄准镜镜片内放大"。
 * 镜片内倍率直接读取 TaCZ 自身的瞄具数据（枪包 display 中的 zoom 数组与切换的 zoom_number），
 * 因此自动适配所有枪包；调整倍率（TaCZ 默认 V 键 / 滚轮逻辑）时镜内倍率同步变化。
 */
@Mod(ScopeLensMod.MOD_ID)
public final class ScopeLensMod {
    public static final String MOD_ID = "scope_lens_zoom";

    public ScopeLensMod(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, LensZoomConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 光影下的镜内合成时机：世界已经出完最终画面、HUD 还没开始画的那一刻。
            NeoForge.EVENT_BUS.addListener(ScopeLensGuiOverlay::onGuiPre);
        }
    }
}
