package com.sky.ant.min;

import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 飞鱼极简版：让微信以为自己在联想平板上运行。
 *
 * 一共只做两件事：
 *   1. 本地：把「当前设备是 Lenovo TB-9707F 平板」的判定改成 true；
 *   2. 联网：微信向服务器校验「能否以平板身份登录」时，把应答改成 true。
 *
 * 与原版「飞鱼」的区别：
 *   - 不硬编码任何类名/方法名，靠语义字符串锚点在 dex 里现场定位，微信升级自动适配；
 *   - 没有设置界面、没有网络图床、没有作者署名、没有多余 hook；
 *   - 没有任何三方依赖（无 androidx、无 kotlin），整个 APK 只有一个 dex。
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "fy-min: ";

    /** 只处理微信。 */
    private static final String TARGET_PKG = "com.tencent.mm";

    /** 扫描结果缓存文件名（放在微信自己的 files 目录下）。 */
    private static final String CACHE_FILE = "fymin.targets";
    private static final String CACHE_HEAD = "fy-min v1";

    /** 微信的热修复目录，直接删掉，避免它把旧 class 覆盖回来。 */
    private static final String[] TINKER_DIRS = { "tinker", "tinker_server", "tinker_temp" };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        try {
            run(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "fatal: " + t);
            XposedBridge.log(t);
        }
    }

    private void run(XC_LoadPackage.LoadPackageParam lpparam) {
        long t0 = System.currentTimeMillis();
        ApplicationInfo ai = lpparam.appInfo;
        long versionCode = versionCodeOf(ai);
        XposedBridge.log(TAG + "start, wechat versionCode=" + versionCode);

        if (ai != null) {
            cleanTinker(ai.dataDir);
        }

        String[] paths = apkPaths(ai);
        long apkSize = 0;
        for (int i = 0; i < paths.length; i++) {
            File f = new File(paths[i]);
            if (f.isFile()) {
                apkSize += f.length();
            }
        }

        File cache = (ai == null || ai.dataDir == null) ? null : new File(ai.dataDir, CACHE_FILE);
        List<Target> targets = null;

        if (cache != null) {
            targets = loadCache(cache, versionCode, apkSize, lpparam.classLoader);
            if (targets != null) {
                XposedBridge.log(TAG + "cache hit, " + targets.size() + " target(s)");
            }
        }
        if (targets == null) {
            XposedBridge.log(TAG + "scanning " + paths.length + " apk(s)...");
            try {
                targets = DexScanner.scan(paths);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "scan failed: " + t);
                targets = new ArrayList<Target>();
            }
            XposedBridge.log(TAG + "scan found " + targets.size() + " target(s)");
            if (cache != null) {
                saveCache(cache, versionCode, apkSize, targets);
            }
        }

        // 扫描结果可能有好几个候选（多个锚点各给一个），逐个做签名校验，
        // 某个目标一旦挂上就不再试后面的 —— 锚点失效时这就是自动降级。
        boolean[] hooked = new boolean[Target.KIND_COUNT];
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.kind < 0 || t.kind >= Target.KIND_COUNT || hooked[t.kind]) {
                continue;
            }
            if (hookOne(lpparam.classLoader, t)) {
                hooked[t.kind] = true;
            }
        }
        for (int k = 0; k < Target.KIND_COUNT; k++) {
            if (!hooked[k]) {
                reportMiss(targets, k);
            }
        }
        XposedBridge.log(TAG + "ready in " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 一个目标都没挂上时，把试过的候选全打出来，方便对照 dex 排查。 */
    private void reportMiss(List<Target> targets, int kind) {
        int n = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).kind == kind) {
                n++;
            }
        }
        XposedBridge.log(TAG + "kind " + kind + " not hooked, " + n + " candidate(s) rejected:");
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.kind == kind) {
                XposedBridge.log(TAG + "  - " + t);
            }
        }
    }

    // ───────────────────────── hook ─────────────────────────

    /** 尝试 hook 一个候选，挂上返回 true。 */
    private boolean hookOne(ClassLoader loader, Target t) {
        try {
            Method m = resolve(loader, t);
            if (m == null) {
                XposedBridge.log(TAG + "signature mismatch: " + t);
                return false;
            }
            XC_MethodHook cb;
            if (t.kind == Target.KIND_LOCAL_DEVICE) {
                // 纯本地计算，没有副作用，直接跳过原逻辑返回 true
                cb = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                };
            } else {
                // 保留真实网络请求，只把应答改成 true
                cb = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        // true 不用动；false 是「不是平板」的应答，null 是异常路径，
                        // 两种都按「校验通过」处理
                        if (Boolean.TRUE.equals(r)) {
                            return;
                        }
                        if (r instanceof Boolean || r == null) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                };
            }
            XposedBridge.hookMethod(m, cb);
            XposedBridge.log(TAG + "hooked [" + (t.kind == Target.KIND_LOCAL_DEVICE ? "local" : "cgi")
                    + "] " + m);
            return true;
        } catch (Throwable e) {
            XposedBridge.log(TAG + "hook failed " + t + ": " + e);
            return false;
        }
    }

    /**
     * 反射校验：类名是在 dex 里扫出来的，这里再用方法签名二次确认，
     * 确保升版后即使扫到同名类也不会挂错方法。
     */
    static Method resolve(ClassLoader loader, Target t) {
        try {
            Class<?> c = Class.forName(t.className, false, loader);
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (!m.getName().equals(t.methodName)) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (t.kind == Target.KIND_LOCAL_DEVICE) {
                    // static boolean ()
                    if (Modifier.isStatic(m.getModifiers())
                            && ps.length == 0
                            && m.getReturnType() == Boolean.TYPE) {
                        return m;
                    }
                } else {
                    // Kotlin 挂起函数编译到 JVM 上就是 (普通参数..., Continuation) -> Object。
                    // 所以只检查「前两个是 String + 最后一个是 Continuation + 返回 Object」，
                    // 不写死 kotlin.coroutines.Continuation 这个全限定名 ——
                    // 它换实现、改名、或者协程库版本变了都不会让校验失效。
                    int n = ps.length;
                    if (!Modifier.isStatic(m.getModifiers())
                            && n >= 2
                            && ps[0] == String.class
                            && ps[1] == String.class
                            && ps[n - 1].getName().endsWith("Continuation")
                            && m.getReturnType() == Object.class) {
                        return m;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 类不存在或加载失败，交给调用方处理
        }
        return null;
    }

    // ───────────────────────── 热修复清理 ─────────────────────────

    private void cleanTinker(String dataDir) {
        if (dataDir == null) {
            return;
        }
        for (int i = 0; i < TINKER_DIRS.length; i++) {
            File f = new File(dataDir, TINKER_DIRS[i]);
            try {
                if (f.exists() && !f.isFile()) {
                    deleteTree(f);
                    XposedBridge.log(TAG + "cleaned " + f.getAbsolutePath());
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "clean " + f + " failed: " + t);
            }
        }
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    deleteTree(kids[i]);
                }
            }
        }
        f.delete();
    }

    // ───────────────────────── 缓存 ─────────────────────────

    /**
     * 读微信的 versionCode。
     * ApplicationInfo.versionCode 字段在 API 28 起弃用、新版 SDK 的 stub 里已删除，
     * 而 getLongVersionCode() 又是 API 28 才有的方法，所以两条路都走反射。
     */
    static long versionCodeOf(ApplicationInfo ai) {
        if (ai == null) {
            return -1;
        }
        try {
            Object v = ApplicationInfo.class.getField("versionCode").get(ai);
            if (v instanceof Integer) {
                return ((Integer) v).longValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object v = ApplicationInfo.class.getMethod("getLongVersionCode").invoke(ai);
            if (v instanceof Long) {
                return ((Long) v).longValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private List<Target> loadCache(File f, long versionCode, long apkSize, ClassLoader loader) {
        if (!f.isFile()) {
            return null;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            if (!CACHE_HEAD.equals(r.readLine())) {
                return null;
            }
            if (!("vc " + versionCode).equals(r.readLine())) {
                return null;   // 微信升级了，重新扫
            }
            if (!("sz " + apkSize).equals(r.readLine())) {
                return null;   // APK 变了，重新扫
            }
            List<Target> list = new ArrayList<Target>();
            boolean[] present = new boolean[Target.KIND_COUNT];
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }
                Target t = Target.parse(line);
                if (t == null) {
                    return null;   // 缓存格式不对，重新扫
                }
                if (resolve(loader, t) == null) {
                    XposedBridge.log(TAG + "cached target stale: " + line);
                    continue;      // 只丢这一条，同一目标的其他候选还能用
                }
                if (!list.contains(t)) {
                    list.add(t);
                    present[t.kind] = true;
                }
            }
            // 某个目标一个可用候选都不剩了，缓存整体作废、重扫一遍
            for (int k = 0; k < Target.KIND_COUNT; k++) {
                if (!present[k]) {
                    XposedBridge.log(TAG + "cache has no usable kind " + k + ", rescan");
                    return null;
                }
            }
            return list;
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void saveCache(File f, long versionCode, long apkSize, List<Target> targets) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(CACHE_HEAD + "\n");
            w.write("vc " + versionCode + "\n");
            w.write("sz " + apkSize + "\n");
            for (int i = 0; i < targets.size(); i++) {
                w.write(targets.get(i).toString() + "\n");
            }
            w.flush();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "cache save failed: " + t);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    /** 微信可能是 split apk，dex 分散在多个包里，全部都要扫。 */
    static String[] apkPaths(ApplicationInfo ai) {
        if (ai == null) {
            return new String[0];
        }
        List<String> list = new ArrayList<String>();
        if (ai.sourceDir != null) {
            list.add(ai.sourceDir);
        }
        if (ai.splitSourceDirs != null) {
            for (int i = 0; i < ai.splitSourceDirs.length; i++) {
                String s = ai.splitSourceDirs[i];
                if (s != null && !list.contains(s)) {
                    list.add(s);
                }
            }
        }
        return list.toArray(new String[list.size()]);
    }
}
