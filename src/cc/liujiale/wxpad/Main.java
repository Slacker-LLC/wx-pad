package cc.liujiale.wxpad;

import android.content.pm.ApplicationInfo;
import android.util.Log;

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

import io.github.libxposed.api.XposedModule;

/**
 * 微信平板模式（极简版）：让微信以为自己在联想平板上运行。
 *
 * 一共只做两件事：
 *   1. 本地：把「当前设备是 Lenovo TB-9707F 平板」的判定改成 true；
 *   2. 联网：微信向服务器校验「能否以平板身份登录」时，把应答改成 true。
 *
 * 走 libxposed API 102（io.github.libxposed.api），不是旧的 de.robv.android.xposed。
 * 入口类由 META-INF/xposed/java_init.list 声明，作用域由 META-INF/xposed/scope.list 声明。
 *
 * 与原版「飞鱼」的区别：
 *   - 不硬编码任何类名/方法名，靠语义字符串锚点在 dex 里现场定位，微信升级自动适配；
 *   - 没有设置界面、没有网络图床、没有作者署名、没有多余 hook；
 *   - 没有任何三方依赖（无 androidx、无 kotlin），整个 APK 只有一个 dex。
 */
public class Main extends XposedModule {

    /** 日志 tag，在框架日志页里按它过滤。 */
    private static final String TAG = "wxpad";

    /** 只处理微信。 */
    private static final String TARGET_PKG = "com.tencent.mm";

    /** 扫描结果缓存文件名（放在微信自己的 files 目录下）。 */
    private static final String CACHE_FILE = "wxpad.targets";
    private static final String CACHE_HEAD = "wxpad v1";

    /** 微信的热修复目录，直接删掉，避免它把旧 class 覆盖回来。 */
    private static final String[] TINKER_DIRS = { "tinker", "tinker_server", "tinker_temp" };

    /** 日志。 */
    private void logi(String msg) {
        log(Log.INFO, TAG, msg);
    }

    private void logw(String msg) {
        log(Log.WARN, TAG, msg);
    }

    private void loge(String msg, Throwable t) {
        log(Log.ERROR, TAG, msg, t);
    }

    /**
     * 包就绪时回调。此刻微信的 classloader 已经建好（{@link PackageReadyParam#getClassLoader()}），
     * 又还没到 {@code Application.onCreate()}，是挂 hook 的时机。
     *
     * 之所以不用更早的 onPackageLoaded：微信带自定义 AppComponentFactory，
     * 两个 classloader 未必是同一个，而我们要 hook 的类由后者加载。
     */
    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) {
            return;
        }
        try {
            run(param);
        } catch (Throwable t) {
            loge("fatal: " + t, t);
        }
    }

    private void run(PackageReadyParam param) {
        long t0 = System.currentTimeMillis();
        ApplicationInfo ai = param.getApplicationInfo();
        ClassLoader loader = param.getClassLoader();
        long versionCode = versionCodeOf(ai);
        logi("start, wechat versionCode=" + versionCode);

        // libxposed 的 log() 是模块实例方法，静态的 DexScanner 拿不到，这里接上
        DexScanner.setLogger(msg -> logi(msg));

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
            targets = loadCache(cache, versionCode, apkSize, loader);
            if (targets != null) {
                logi("cache hit, " + targets.size() + " target(s)");
            }
        }
        if (targets == null) {
            logi("scanning " + paths.length + " apk(s)...");
            try {
                targets = DexScanner.scan(paths);
            } catch (Throwable t) {
                loge("scan failed: " + t, t);
                targets = new ArrayList<Target>();
            }
            logi("scan found " + targets.size() + " target(s)");
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
            if (hookOne(loader, t)) {
                hooked[t.kind] = true;
            }
        }
        for (int k = 0; k < Target.KIND_COUNT; k++) {
            if (!hooked[k]) {
                reportMiss(targets, k);
            }
        }
        logi("ready in " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 一个目标都没挂上时，把试过的候选全打出来，方便对照 dex 排查。 */
    private void reportMiss(List<Target> targets, int kind) {
        int n = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).kind == kind) {
                n++;
            }
        }
        logw("kind " + kind + " not hooked, " + n + " candidate(s) rejected:");
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.kind == kind) {
                logw("  - " + t);
            }
        }
    }

    // ───────────────────────── hook ─────────────────────────

    /** 尝试 hook 一个候选，挂上返回 true。 */
    private boolean hookOne(ClassLoader loader, Target t) {
        try {
            Method m = resolve(loader, t);
            if (m == null) {
                logw("signature mismatch: " + t);
                return false;
            }
            Hooker hooker;
            if (t.kind == Target.KIND_LOCAL_DEVICE) {
                // 纯本地计算，没有副作用：不往下走，直接判定「是平板」
                hooker = chain -> Boolean.TRUE;
            } else {
                // 保留真实网络请求，只把应答改成 true
                hooker = chain -> {
                    Object r = chain.proceed();
                    // true 不用动；false 是「不是平板」的应答，null 是异常路径，
                    // 两种都按「校验通过」处理
                    if (Boolean.TRUE.equals(r)) {
                        return r;
                    }
                    if (r instanceof Boolean || r == null) {
                        return Boolean.TRUE;
                    }
                    return r;
                };
            }
            // PROTECTIVE：hooker 自己抛的异常由框架吞掉并记日志，
            // 不会波及微信。proceed() 抛的异常不受影响，照常往上抛。
            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker);
            logi("hooked [" + (t.kind == Target.KIND_LOCAL_DEVICE ? "local" : "cgi") + "] " + m);
            return true;
        } catch (Throwable e) {
            loge("hook failed " + t + ": " + e, e);
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
                    logi("cleaned " + f.getAbsolutePath());
                }
            } catch (Throwable t) {
                loge("clean " + f + " failed: " + t, t);
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
                    logw("cached target stale: " + line);
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
                    logw("cache has no usable kind " + k + ", rescan");
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
            loge("cache save failed: " + t, t);
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
