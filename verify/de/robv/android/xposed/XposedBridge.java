package de.robv.android.xposed;

/**
 * 桌面验证用的桩：把日志打到 stdout。
 * 只用于在电脑上跑扫描器，不参与 APK 构建。
 */
public class XposedBridge {
    public static void log(String s) {
        System.out.println("[log] " + s);
    }

    public static void log(Throwable t) {
        t.printStackTrace(System.out);
    }
}
