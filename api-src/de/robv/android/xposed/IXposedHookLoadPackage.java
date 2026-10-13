package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 编译桩：仅用于通过 javac 编译，运行时由 Xposed 框架提供真实实现。
 * 该目录的任何 class 都不得打进 APK，否则会覆盖框架类。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
