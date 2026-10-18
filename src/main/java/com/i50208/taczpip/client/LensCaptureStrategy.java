package com.i50208.taczpip.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 光影下“镜内画面怎么来”的几条候选路线，以及本次运行该用哪一条。
 *
 * <p>背景：开光影时镜内必须自己用小视野把世界重画一遍才有原生分辨率的放大画面。
 * 这一遍会遇到 Iris / Sodium 各自留下的状态，哪一条路在你的环境里真的画得出地形，
 * 光靠猜是不行的（上一版就是猜错的那一次）。所以这里改成：开局把几条路线挨个画一遍、
 * 每画完一张就当场读回来量一量（见 {@link LensCaptureProbe}），哪条画出了内容就锁哪条，
 * 然后整局都用它。全都不行时退回“放大光影最终画面”—— 那条一定不黑，只是不如自渲染清晰。
 *
 * <p>玩家也可以在配置里直接指定，指定之后就不再做这个自检了。
 */
public final class LensCaptureStrategy {
    private static final Logger LOGGER = LoggerFactory.getLogger("scope_lens_zoom");

    /** 镜内取景路线，按“最爱”到“最保守”排列。 */
    public enum Strategy {
        /** 临时摘掉 Iris 着色管线 + 修正 Sodium 记录的地形贴图编号（默认首选）。 */
        ISOLATED_REPAIRED("隔离+修正贴图", true),
        /** 同上，并且允许镜内那一遍重建区块可见列表（对付“列表是空的”这种情况）。 */
        ISOLATED_REFRESH("隔离+重建列表", true),
        /** 不摘管线，让光影自己把小视野世界画完并整屏合成到镜内贴图（一定是清晰光影画面）。 */
        SHADER_LIT("光影直出", false),
        /** 什么都不重画，放大光影最终画面（一定不黑，但高倍会糊）。 */
        MAGNIFY("最终画面放大", false);

        private final String label;
        private final boolean isolated;

        Strategy(String label, boolean isolated) {
            this.label = label;
            this.isolated = isolated;
        }

        public String label() {
            return label;
        }

        /** 是否需要把 Iris 的着色管线临时换成空管线。 */
        public boolean isolated() {
            return isolated;
        }

        /** 这一遍是否允许重建区块可见列表。 */
        public boolean refreshTerrain() {
            return this == ISOLATED_REFRESH;
        }

        public boolean rendersCapture() {
            return this != MAGNIFY;
        }
    }

    private static final Strategy[] LADDER = {
            Strategy.ISOLATED_REPAIRED,
            Strategy.ISOLATED_REFRESH,
            Strategy.SHADER_LIT,
            Strategy.MAGNIFY
    };

    private static Strategy current = Strategy.ISOLATED_REPAIRED;
    private static boolean settled;
    private static boolean loggedPick;
    private static boolean reported;

    private LensCaptureStrategy() { }

    /** 本帧要依次尝试的路线；已经定下来时就只有一条。 */
    public static List<Strategy> candidates() {
        List<Strategy> list = new ArrayList<>(LADDER.length);
        Strategy pinned = pinnedByConfig();
        if (pinned != null) {
            list.add(pinned);
            return list;
        }
        if (settled) {
            list.add(current);
            return list;
        }
        boolean from = false;
        for (Strategy s : LADDER) {
            if (!from && s == current) from = true;
            if (!from) continue;
            // “重建区块列表”会动主画面的区块可见性增量状态，可能让远处区块闪进出，
            // 所以不放进自动自检：想试它就在配置里把 lensCaptureMode 写成 ISOLATED_REFRESH。
            if (s == Strategy.ISOLATED_REFRESH) continue;
            if (s.isolated() && !IrisLensPipelineSwap.enabled()) continue;
            list.add(s);
        }
        if (list.isEmpty()) list.add(Strategy.MAGNIFY);
        return list;
    }

    /** 是否还在自检（决定要不要读回像素量一量）。 */
    public static boolean probing() {
        return !settled && pinnedByConfig() == null;
    }

    /** 这条路线画出来的画面通过了自检：本局就用它。 */
    public static void confirm(Strategy strategy, String stats) {
        if (pinnedByConfig() != null) return;
        current = strategy;
        settled = true;
        if (!loggedPick) {
            loggedPick = true;
            LOGGER.info("[scope_lens_zoom] 镜内取景自检通过：路线={}（{}），本局固定使用这条（{}）",
                    strategy.name(), strategy.label(), stats);
        }
    }

    /** 这条路线画出来的画面是坏的（整块死黑等）：换下一条。 */
    public static void reject(Strategy strategy, String stats) {
        if (pinnedByConfig() != null) return;
        current = strategy;
        if (!reported) {
            reported = true;
            LOGGER.warn("[scope_lens_zoom] 镜内取景自检不通过：路线={}（{}）画面异常（{}），换下一条路线试试",
                    strategy.name(), strategy.label(), stats);
        }
    }

    /** 全部路线都不行（走到最后一条 MAGNIFY）。 */
    public static void settleAtEnd(Strategy strategy, String stats) {
        if (pinnedByConfig() != null) return;
        current = strategy;
        settled = true;
        LOGGER.warn("[scope_lens_zoom] 镜内取景自检结束：所有自渲染路线都画不出地形，"
                + "本次运行改为放大光影最终画面（不黑，但高倍不如自渲染清晰）。最后一条={}，{}",
                strategy.name(), stats);
    }

    public static Strategy current() {
        Strategy pinned = pinnedByConfig();
        return pinned != null ? pinned : current;
    }

    public static String label() {
        return current().name();
    }

    /** 配置里直接写死的路线；没写（auto）返回 null。 */
    private static Strategy pinnedByConfig() {
        LensZoomConfig.CaptureMode mode = LensZoomConfig.captureMode();
        return switch (mode) {
            case AUTO -> null;
            case ISOLATED_REPAIRED -> Strategy.ISOLATED_REPAIRED;
            case ISOLATED_REFRESH -> Strategy.ISOLATED_REFRESH;
            case SHADER_LIT -> Strategy.SHADER_LIT;
            case MAGNIFY -> Strategy.MAGNIFY;
        };
    }
}
