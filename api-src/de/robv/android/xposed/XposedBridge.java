package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * 编译桩：仅提供与 Xposed 框架一致的静态方法签名。
 */
public class XposedBridge {

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }
}
