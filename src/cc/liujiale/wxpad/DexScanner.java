package cc.liujiale.wxpad;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 迷你 DEX 扫描器：不依赖任何三方库，靠「字符串锚点」定位 hook 目标。
 *
 * 为什么不用类名：微信每次升级都会混淆重排类名
 * （原模块就是硬编码 8.0.72/74/76/78 四份类名表，8.0.79 全部失效）。
 * 而语义字符串——服务器接口路径、设备型号——改名的代价极高，几乎不变。
 *
 * 定位流程：
 *   1. 在 dex 的 string_ids 表里找到锚点字符串的索引 strIdx；
 *   2. 解析 class_defs → class_data_item → code_item，建立「代码区间 → 方法」映射；
 *   3. 线性扫描整个 dex 字节，找引用 strIdx 的 const-string / const-string/jumbo 指令；
 *   4. 指令地址二分反查所属方法，解出 (类名, 方法名)。
 * 调用方拿到类名后还会用反射校验方法签名，双重确认后才 hook；
 * 每个锚点给出的候选全部保留，某个锚点给出的方法签名对不上时能自动退到下一个。
 *
 * 之所以要线性扫字节而不是顺着方法查：常量池引用藏在指令流里，
 * 穷举指令比反汇编便宜得多，而且对任何混淆都免疫。
 */
final class DexScanner {

    private static final String TAG = "scan: ";

    /**
     * 日志出口。libxposed 的 log() 是 {@link Main} 的实例方法，本类是静态工具，
     * 拿不到那个实例，所以由 Main 在挂 hook 前把自己的 logi() 装进来。
     * 没装（例如脱离框架直接跑扫描）就静默丢弃，不影响扫描本身。
     */
    interface Logger {
        void log(String msg);
    }

    private static volatile Logger sLogger;

    static void setLogger(Logger l) {
        sLogger = l;
    }

    private static void log(String msg) {
        Logger l = sLogger;
        if (l != null) {
            l.log(TAG + msg);
        }
    }

    /**
     * 全部锚点。约定：完整的字面量排在同组的子串形式前面 —— 整串相等比对更快，
     * 子串包含只在微信改了字面量时才兜底。
     * 一次遍历 string_ids 会把它们全部找出来，所以这里合在一张表里。
     *
     * 每个 hook 目标都挂了多个互不相干的锚点：只要微信还留着其中任意一个，
     * 定位就不会断。表里的下标同时就是优先级，越小越可信。
     */
    private static final String[] ANCHORS = {
            /* 0 */ "Lenovo TB-9707F",                       // 本地：设备型号
            /* 1 */ "lenovo",                                // 本地：品牌串兜底
            /* 2 */ "/cgi-bin/micromsg-bin/checkloginaspad",  // 服务器：接口 URL
            /* 3 */ "MicroMsg.CgiCheckLoginAsPad",           // 服务器：协程日志 tag
            /* 4 */ "doCgi callback loginAsPad:",            // 服务器：同方法内的另一条日志
            /* 5 */ "doCgi deviceBrand:",                    // 服务器：同方法内的又一条日志
            /* 6 */ "checkloginaspad",                       // 服务器：URL 子串兜底
            /* 7 */ "CgiCheckLoginAsPad",                    // 服务器：tag 子串兜底
    };

    /** hook#0 的锚点（下标指向 {@link #ANCHORS}），数组顺序即优先级。 */
    private static final int[] ORDER_LOCAL = { 0, 1 };

    /** hook#1 的锚点（下标指向 {@link #ANCHORS}），数组顺序即优先级。 */
    private static final int[] ORDER_CGI = { 2, 3, 4, 5, 6, 7 };

    /** 单个 dex 里候选方法数上限，防御畸形 dex。 */
    private static final int MAX_SITES = 64;

    /** 每个目标保留的候选方法数上限（多的排在后面，本来就轮不到）。 */
    private static final int MAX_TARGETS = 16;

    private DexScanner() {
    }

    /** 扫描给定的全部 apk / split apk，返回发现的目标方法。 */
    static List<Target> scan(String[] apkPaths) {
        List<Candidate> local = new ArrayList<Candidate>();
        List<Candidate> cgi = new ArrayList<Candidate>();

        for (String path : apkPaths) {
            ZipFile zf = null;
            try {
                zf = new ZipFile(path);
                for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (!isDexEntry(e.getName())) {
                        continue;
                    }
                    byte[] dex = readEntry(zf, e);
                    if (dex == null || dex.length < 112) {
                        continue;
                    }
                    // 一个 dex 只读一次、只遍历一遍 string_ids，全部锚点一起找；
                    // 方法映射表也只建一次，8 个锚点共用（建表要分配几万个对象，
                    // 每个锚点重建一次的话这里就是主耗时）
                    int[] idx = findStringIndices(dex, ANCHORS);
                    List<MethodRef> map = buildMethodMap(dex);
                    if (map.isEmpty()) {
                        continue;
                    }
                    Collections.sort(map, METHOD_ORDER);
                    probe(dex, map, idx, ORDER_LOCAL, local);
                    probe(dex, map, idx, ORDER_CGI, cgi);
                }
            } catch (Throwable t) {
                log("open " + path + " failed: " + t);
            } finally {
                if (zf != null) {
                    try {
                        zf.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        List<Target> out = new ArrayList<Target>();
        collect(local, Target.KIND_LOCAL_DEVICE, out);
        collect(cgi, Target.KIND_CGI_CHECK, out);
        return out;
    }

    /**
     * 收集该 dex 上**所有**锚点的命中结果，并打上锚点优先级。
     *
     * 这里不挑选、不提前返回：谁更可信交给签名校验去判断。
     * 首选锚点给出的方法万一签名对不上（微信重构了方法），
     * 还能自动退到下一个锚点的候选 —— 命中即停就没有这个余地。
     * strIdx 是 {@link #findStringIndices} 预先算好的，这里不再重复遍历 string_ids。
     */
    private static void probe(byte[] dex, List<MethodRef> map, int[] strIdx, int[] order, List<Candidate> sink) {
        for (int k = 0; k < order.length; k++) {
            int a = order[k];              // ANCHORS 下标，同时也是优先级
            int idx = strIdx[a];
            if (idx < 0) {
                continue;
            }
            sink.addAll(sitesOf(dex, map, idx, a));
        }
    }

    /**
     * 按锚点优先级排序后全部保留（去重），不做「只留最优」的裁剪。
     * 调用方会从头开始逐个做反射校验，第一个通过的生效。
     */
    private static void collect(List<Candidate> all, int kind, List<Target> out) {
        if (all.isEmpty()) {
            return;
        }
        Collections.sort(all, CANDIDATE_ORDER);   // 稳定排序，同优先级的保持扫描顺序
        int base = out.size();                    // 上限按种类各算各的，互不挤占
        for (int i = 0; i < all.size() && out.size() - base < MAX_TARGETS; i++) {
            Candidate c = all.get(i);
            Target t = new Target(kind, c.className, c.methodName);
            if (!out.contains(t)) {
                out.add(t);
            }
        }
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = new Comparator<Candidate>() {
        public int compare(Candidate a, Candidate b) {
            return a.anchor - b.anchor;
        }
    };

    // ───────────────────────── 单个 dex 的处理 ─────────────────────────

    /** 找引用某个字符串常量的位置，反查出所属的类和方法。priority 越小越优先。 */
    private static List<Candidate> sitesOf(byte[] dex, List<MethodRef> map, int strIdx, int priority) {
        List<Candidate> out = new ArrayList<Candidate>();
        try {
            List<int[]> sites = findConstStringSites(dex, strIdx, map);
            for (int i = 0; i < sites.size() && out.size() < MAX_SITES; i++) {
                int[] s = sites.get(i);
                String cn = className(dex, s[0]);
                String mn = methodName(dex, s[1]);
                if (cn == null || mn == null) {
                    continue;
                }
                Candidate c = new Candidate();
                c.anchor = priority;
                c.className = cn;
                c.methodName = mn;
                if (!out.contains(c)) {
                    out.add(c);
                }
            }
        } catch (Throwable t) {
            log("scan sites failed: " + t);
        }
        return out;
    }

    /**
     * 在 string_ids 表里一次性找出全部锚点，返回等长数组（未命中为 -1）。
     *
     * 两层匹配，同一次遍历里完成：
     *   1) 整串相等 —— 轻量扫描，同时用首字节位图筛掉绝大多数串，这是常态下走的路；
     *   2) 子串包含 —— 微信把字面量改了（加前缀、换后缀）时的兜底。
     * 遍历覆盖了 18 个 dex 的字符串总长度，能省一趟就省一趟，所以全部锚点一起找。
     *
     * 注意：string_ids 是按字符串内容的 UTF-16 码点值排序的，
     * 不是按数据在文件里的偏移排序，所以不能拿偏移做二分。
     */
    private static int[] findStringIndices(byte[] dex, String[] anchors) {
        int n = anchors.length;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = -1;
        }
        int size = u32(dex, 0x38);   // string_ids_size
        int off = u32(dex, 0x3c);    // string_ids_off
        if (size <= 0 || off <= 0 || off + size * 4 > dex.length) {
            return out;
        }
        int remain = n;

        // ── 第一层：整串相等 ──
        boolean[] firstOk = new boolean[128];      // 锚点都是 ASCII，首字节只会落在这里
        for (int k = 0; k < n; k++) {
            firstOk[anchors[k].charAt(0) & 0x7f] = true;
        }
        byte[][] pats = new byte[n][];
        for (int k = 0; k < n; k++) {
            pats[k] = asciiBytes(anchors[k]);
        }
        for (int i = 0; i < size && remain > 0; i++) {
            int so = u32(dex, off + (i << 2));     // string_data_off
            if (so <= 0 || so >= dex.length) {
                continue;
            }
            int[] cur = { so };
            int utf16 = uleb(dex, cur);            // utf16_size，也是字符数
            int d = cur[0];                        // MUTF-8 数据起点
            if (d >= dex.length) {
                continue;
            }
            int b0 = dex[d] & 0xff;
            if (b0 >= 0x80 || !firstOk[b0]) {
                continue;                          // 非 ASCII 开头，或首字节不对
            }
            for (int k = 0; k < n; k++) {
                if (out[k] >= 0 || utf16 != anchors[k].length()) {
                    continue;
                }
                if (regionEquals(dex, d, pats[k]) && dex[d + pats[k].length] == 0) {
                    out[k] = i;                    // 结尾的 NUL 保证是整串相等
                    remain--;
                }
            }
        }
        if (remain == 0) {
            return out;
        }

        // ── 第二层：子串包含 ──
        boolean[] need = new boolean[n];
        int needCount = 0;
        for (int k = 0; k < n; k++) {
            if (out[k] < 0) {
                need[k] = true;
                needCount++;
            }
        }
        for (int i = 0; i < size && needCount > 0; i++) {
            int so = u32(dex, off + (i << 2));
            if (so <= 0 || so >= dex.length) {
                continue;
            }
            int[] cur = { so };
            uleb(dex, cur);                        // 跳过 utf16_size
            int d = cur[0];
            int end = indexOfNul(dex, d);          // 串内不含 NUL（U+0000 编码为 C0 80）
            if (end < 0) {
                continue;
            }
            for (int k = 0; k < n; k++) {
                if (need[k] && end - d >= pats[k].length && contains(dex, d, end, pats[k])) {
                    out[k] = i;
                    need[k] = false;
                    needCount--;
                }
            }
        }
        return out;
    }

    private static boolean regionEquals(byte[] b, int off, byte[] pat) {
        for (int i = 0; i < pat.length; i++) {
            if (off + i >= b.length) {
                return false;
            }
            if (b[off + i] != pat[i]) {
                return false;
            }
        }
        return true;
    }

    /** 从 off 起找 MUTF-8 串的结束 NUL，没找到返回 -1。 */
    private static int indexOfNul(byte[] b, int off) {
        for (int p = off; p < b.length; p++) {
            if (b[p] == 0) {
                return p;
            }
        }
        return -1;
    }

    /** 锚点全 ASCII，低 7 位就是它的 MUTF-8 字节。 */
    private static byte[] asciiBytes(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            out[i] = (byte) (s.charAt(i) & 0x7f);
        }
        return out;
    }

    /**
     * 在 [from, to) 内查找 pat。
     * MUTF-8 的多字节序列首字节 >= 0xC0、后续字节在 0x80-0xBF，都不可能是 ASCII 字节，
     * 所以纯 ASCII 子串不会跨字符误匹配，直接按字节找是安全的。
     */
    private static boolean contains(byte[] b, int from, int to, byte[] pat) {
        int last = to - pat.length;
        for (int p = from; p <= last; p++) {
            if (b[p] != pat[0]) {
                continue;
            }
            int k = 1;
            while (k < pat.length && b[p + k] == pat[k]) {
                k++;
            }
            if (k == pat.length) {
                return true;
            }
        }
        return false;
    }

    // ─────────────────── class_defs / class_data / code_item ───────────────────

    /**
     * 建立「指令区间 → (classIdx, methodIdx)」映射。
     * class_data_item 里 direct_methods 与 virtual_methods 的 method_idx_diff
     * 是各自从 0 开始累加的，必须分开处理。
     */
    private static List<MethodRef> buildMethodMap(byte[] dex) {
        List<MethodRef> list = new ArrayList<MethodRef>();
        int size = u32(dex, 0x60);   // class_defs_size
        int off = u32(dex, 0x64);    // class_defs_off
        if (size <= 0 || off <= 0 || off + size * 32 > dex.length) {
            return list;
        }
        for (int i = 0; i < size; i++) {
            int base = off + i * 32;
            int classIdx = u32(dex, base);           // class_idx
            int dataOff = u32(dex, base + 0x18);     // class_data_off
            if (dataOff <= 0 || dataOff >= dex.length) {
                continue;
            }
            int[] cur = { dataOff };
            int sf = uleb(dex, cur);    // static_fields_size
            int inf = uleb(dex, cur);   // instance_fields_size
            int dm = uleb(dex, cur);    // direct_methods_size
            int vm = uleb(dex, cur);    // virtual_methods_size
            if (sf < 0 || inf < 0 || dm < 0 || vm < 0 || sf + inf > 200000 || dm + vm > 200000) {
                continue;   // 畸形数据，跳过
            }
            for (int k = 0, nf = sf + inf; k < nf; k++) {   // 跳过字段表
                uleb(dex, cur);
                uleb(dex, cur);
            }
            addMethods(dex, list, classIdx, cur, dm);
            addMethods(dex, list, classIdx, cur, vm);
        }
        return list;
    }

    private static void addMethods(byte[] dex, List<MethodRef> list, int classIdx, int[] cur, int count) {
        int methodIdx = 0;   // direct / virtual 各自从 0 起算
        for (int k = 0; k < count; k++) {
            methodIdx += uleb(dex, cur);      // method_idx_diff
            uleb(dex, cur);                   // access_flags
            int codeOff = uleb(dex, cur);     // code_off
            if (codeOff <= 0 || codeOff + 16 > dex.length) {
                continue;                     // abstract / native 方法没有代码
            }
            int insnsSize = u32(dex, codeOff + 0x0c);
            if (insnsSize <= 0) {
                continue;
            }
            long start = (long) codeOff + 0x10;              // insns 起始
            long end = start + (long) insnsSize * 2;         // 每条指令 2 字节
            if (end > dex.length) {
                end = dex.length;
            }
            MethodRef r = new MethodRef();
            r.start = (int) start;
            r.end = (int) end;
            r.classIdx = classIdx;
            r.methodIdx = methodIdx;
            list.add(r);
        }
    }

    private static final Comparator<MethodRef> METHOD_ORDER = new Comparator<MethodRef>() {
        public int compare(MethodRef a, MethodRef b) {
            return a.start < b.start ? -1 : (a.start == b.start ? 0 : 1);
        }
    };

    // ─────────────────── 扫指令流找常量引用 ───────────────────

    /**
     * 在 dex 字节里找引用 strIdx 的 const-string 指令：
     *   0x1a const-string        [1a, AA, lo, hi]
     *   0x1b const-string/jumbo  [1b, AA, b0, b1, b2, b3]
     * 命中位置再二分反查所属方法。
     */
    private static List<int[]> findConstStringSites(byte[] dex, int strIdx, List<MethodRef> map) {
        List<int[]> out = new ArrayList<int[]>();
        byte b0 = (byte) (strIdx & 0xff);
        byte b1 = (byte) ((strIdx >>> 8) & 0xff);
        byte b2 = (byte) ((strIdx >>> 16) & 0xff);
        byte b3 = (byte) ((strIdx >>> 24) & 0xff);
        boolean fits16 = strIdx <= 0xffff;   // 索引超 16 位时只能走 jumbo，避免误配
        int n = dex.length;
        for (int p = 0; p + 6 <= n; p++) {
            int op = dex[p] & 0xff;
            if (op == 0x1a) {
                if (fits16 && dex[p + 2] == b0 && dex[p + 3] == b1) {
                    record(map, p, out);
                }
            } else if (op == 0x1b) {
                if (dex[p + 2] == b0 && dex[p + 3] == b1 && dex[p + 4] == b2 && dex[p + 5] == b3) {
                    record(map, p, out);
                }
            }
            if (out.size() >= MAX_SITES) {
                break;
            }
        }
        return out;
    }

    private static void record(List<MethodRef> map, int p, List<int[]> out) {
        int lo = 0;
        int hi = map.size() - 1;
        int found = -1;
        while (lo <= hi) {   // 最后一个 start <= p 的区间
            int mid = (lo + hi) >>> 1;
            if (map.get(mid).start <= p) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found >= 0) {
            MethodRef r = map.get(found);
            if (p < r.end) {
                out.add(new int[] { r.classIdx, r.methodIdx });
            }
        }
    }

    // ─────────────────── 索引 → 名字 ───────────────────

    /** type_idx → 类全名（点分隔）。 */
    private static String className(byte[] dex, int typeIdx) {
        int size = u32(dex, 0x40);   // type_ids_size
        int off = u32(dex, 0x44);    // type_ids_off
        if (typeIdx < 0 || typeIdx >= size) {
            return null;
        }
        String d = stringById(dex, u32(dex, off + (typeIdx << 2)));
        if (d == null || d.length() < 2 || d.charAt(0) != 'L' || d.charAt(d.length() - 1) != ';') {
            return null;   // 数组或基本类型，不是类
        }
        return d.substring(1, d.length() - 1).replace('/', '.');
    }

    /** method_idx → 方法名。 */
    private static String methodName(byte[] dex, int methodIdx) {
        int size = u32(dex, 0x58);   // method_ids_size
        int off = u32(dex, 0x5c);    // method_ids_off
        if (methodIdx < 0 || methodIdx >= size) {
            return null;
        }
        // method_id_item: class_idx(u2) proto_idx(u2) name_idx(u4)
        return stringById(dex, u32(dex, off + methodIdx * 8 + 4));
    }

    private static String stringById(byte[] dex, int strIdx) {
        int size = u32(dex, 0x38);
        int off = u32(dex, 0x3c);
        if (strIdx < 0 || strIdx >= size) {
            return null;
        }
        int so = u32(dex, off + (strIdx << 2));
        if (so <= 0 || so >= dex.length) {
            return null;
        }
        return decodeMutf8(dex, so + ulebSize(dex, so));
    }

    /** DEX 的 MUTF-8 解码（与标准 UTF-8 的区别：U+0000 编码为 C0 80，且以 NUL 结尾）。 */
    private static String decodeMutf8(byte[] b, int off) {
        StringBuilder sb = new StringBuilder(32);
        int p = off;
        while (p < b.length) {
            int a = b[p++] & 0xff;
            if (a == 0) {
                break;
            }
            if (a < 0x80) {
                sb.append((char) a);
            } else if ((a & 0xe0) == 0xc0) {
                if (p >= b.length) {
                    break;
                }
                int c = b[p++] & 0xff;
                sb.append((char) (((a & 0x1f) << 6) | (c & 0x3f)));
            } else if ((a & 0xf0) == 0xe0) {
                if (p + 1 >= b.length) {
                    break;
                }
                int c = b[p++] & 0xff;
                int d = b[p++] & 0xff;
                sb.append((char) (((a & 0x0f) << 12) | ((c & 0x3f) << 6) | (d & 0x3f)));
            } else {
                break;   // 非法或 4 字节序列，DEX 里不该出现
            }
        }
        return sb.toString();
    }

    // ─────────────────── 字节读取工具 ───────────────────

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    /** ULEB128 解码，游标由 cur[0] 带入带出。 */
    private static int uleb(byte[] b, int[] cur) {
        int p = cur[0];
        int result = 0;
        int shift = 0;
        int x;
        do {
            if (p >= b.length) {
                cur[0] = p;
                return result;
            }
            x = b[p++] & 0xff;
            result |= (x & 0x7f) << shift;
            shift += 7;
        } while ((x & 0x80) != 0 && shift < 32);
        cur[0] = p;
        return result;
    }

    /** ULEB128 占用的字节数。 */
    private static int ulebSize(byte[] b, int off) {
        int n = 1;
        while (off + n < b.length && (b[off + n - 1] & 0x80) != 0 && n < 5) {
            n++;
        }
        return n;
    }

    // ─────────────────── zip 读写 ───────────────────

    /** 只认 classes.dex / classesN.dex，跳过资源与其他条目。 */
    private static boolean isDexEntry(String name) {
        if (name == null || !name.startsWith("classes") || !name.endsWith(".dex")) {
            return false;
        }
        if (name.indexOf('/') >= 0) {
            return false;
        }
        for (int i = 7; i < name.length() - 4; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 手写流拷贝，避开 API 33 才有的 InputStream.readAllBytes。 */
    private static byte[] readEntry(ZipFile zf, ZipEntry e) {
        InputStream in = null;
        try {
            in = zf.getInputStream(e);
            long sz = e.getSize();
            int cap = (sz > 0 && sz < Integer.MAX_VALUE) ? (int) sz : 65536;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(cap, 1024));
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            log("read " + e.getName() + " failed: " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ─────────────────── 内部数据 ───────────────────

    private static final class Candidate {
        int anchor;
        String className;
        String methodName;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Candidate)) {
                return false;
            }
            Candidate c = (Candidate) o;
            return className.equals(c.className) && methodName.equals(c.methodName);
        }

        @Override
        public int hashCode() {
            return className.hashCode() * 31 + methodName.hashCode();
        }
    }

    private static final class MethodRef {
        int start;
        int end;
        int classIdx;
        int methodIdx;
    }
}
