package com.i50208.taczpip.client;

import java.lang.reflect.Method;

/** 仅通过 Iris 公开 API 判断光影是否开启，不改动其内部管线或全局开关。 */
public final class IrisCompat {
    private static final Object API;
    private static final Method IS_SHADER_PACK_IN_USE;

    static {
        Object api = null;
        Method method = null;
        try {
            Class<?> clazz = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = clazz.getMethod("getInstance").invoke(null);
            method = clazz.getMethod("isShaderPackInUse");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            api = null;
            method = null;
        }
        API = api;
        IS_SHADER_PACK_IN_USE = method;
    }

    private IrisCompat() { }

    public static boolean shadersInUse() {
        if (API == null || IS_SHADER_PACK_IN_USE == null) return false;
        try {
            return Boolean.TRUE.equals(IS_SHADER_PACK_IN_USE.invoke(API));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
