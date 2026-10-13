package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * 编译桩：仅提供与 Xposed 框架一致的类型签名。
 */
public class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public boolean isFirstApplication;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
    }
}
