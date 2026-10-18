package com.i50208.taczpip.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * 镜内那一遍和 Voxy 超视距 LOD 的对接。
 *
 * <p>Voxy 在开光影时会在主画面那一遍开头，把"主画面矩阵"写进
 * {@code IrisUtil.CAPTURED_VIEWPORT_PARAMETERS}；随后画区块时如果这份还在，
 * 它就直接拿那份视口画超视距，不再按当前投影重建。
 *
 * <p>镜内那一遍是小视野：如果还用主画面那份视口，超视距远景就按没放大的角度画进
 * 已经放大过的画面里 —— 实机就是"超视距有时有、有时没有"，以及镜内外画面叠在一起。
 * 这里在镜内那一遍开始前把那份缓存清掉，让 Voxy 按这一遍真正的小视野矩阵重新建视口。
 *
 * <p>全部反射访问：没装 Voxy 时零开销。
 */
final class VoxyLensCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("scope_lens_zoom");
    private static boolean probed;
    private static boolean ready;
    private static boolean logged;
    private static Field capturedField;
    private static Field usedIrisViewportField;

    private VoxyLensCompat() { }

    /** 镜内那一遍开始前调用：丢掉主画面留下来的视口缓存。 */
    static void prepareCapturePass() {
        if (!probed) probe();
        if (!ready) return;
        try {
            capturedField.set(null, null);
            if (usedIrisViewportField != null) usedIrisViewportField.setBoolean(null, false);
            if (!logged) {
                logged = true;
                LOGGER.info("[scope_lens_zoom] 已对接 Voxy：镜内那一遍按小视野重建超视距视口");
            }
        } catch (Throwable ignored) {
            ready = false;
        }
    }

    private static void probe() {
        probed = true;
        try {
            Class<?> clazz = Class.forName("me.cortex.voxy.client.core.util.IrisUtil");
            capturedField = clazz.getField("CAPTURED_VIEWPORT_PARAMETERS");
            try {
                usedIrisViewportField = clazz.getField("USED_IRIS_VIEWPORT");
            } catch (NoSuchFieldException ignored) {
                usedIrisViewportField = null;
            }
            ready = true;
        } catch (Throwable ignored) {
            ready = false;
        }
    }
}
