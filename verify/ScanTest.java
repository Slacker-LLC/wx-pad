package cc.liujiale.wxpad;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 桌面验证入口（不参与 APK 构建）：
 *   ScanTest <apk...>          走完整 scan()
 *   ScanTest --probe <apk>     逐个 dex 打印每个锚点的命中情况与耗时
 */
public class ScanTest {

    public static void main(String[] args) throws Exception {
        if (args.length > 1 && "--probe".equals(args[0])) {
            probe(args[1]);
            return;
        }
        long t0 = System.currentTimeMillis();
        List<Target> ts = DexScanner.scan(args);
        for (int i = 0; i < ts.size(); i++) {
            System.out.println("RESULT " + ts.get(i));
        }
        System.out.println("count=" + ts.size() + " elapsed=" + (System.currentTimeMillis() - t0) + "ms");
    }

    static void probe(String apk) throws Exception {
        Method findIdx = DexScanner.class.getDeclaredMethod("findStringIndices", byte[].class, String[].class);
        findIdx.setAccessible(true);
        Method buildMap = DexScanner.class.getDeclaredMethod("buildMethodMap", byte[].class);
        buildMap.setAccessible(true);
        Method sitesOf = DexScanner.class.getDeclaredMethod("sitesOf", byte[].class, List.class, int.class, int.class);
        sitesOf.setAccessible(true);
        Field orderF = DexScanner.class.getDeclaredField("METHOD_ORDER");
        orderF.setAccessible(true);
        @SuppressWarnings("unchecked")
        Comparator<Object> order = (Comparator<Object>) orderF.get(null);

        Field anchorsF = DexScanner.class.getDeclaredField("ANCHORS");
        anchorsF.setAccessible(true);
        String[] anchors = (String[]) anchorsF.get(null);

        long t0 = System.currentTimeMillis();
        ZipFile zf = new ZipFile(apk);
        try {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".dex")) {
                    continue;
                }
                long t1 = System.currentTimeMillis();
                byte[] dex = read(zf.getInputStream(e));
                int[] idx = (int[]) findIdx.invoke(null, dex, anchors);
                @SuppressWarnings("unchecked")
                List<Object> map = (List<Object>) buildMap.invoke(null, dex);
                Collections.sort(map, order);
                StringBuilder sb = new StringBuilder();
                sb.append(n).append(" (").append(dex.length).append(" bytes), methods=").append(map.size());
                int found = 0;
                for (int i = 0; i < anchors.length; i++) {
                    if (idx[i] < 0) {
                        continue;
                    }
                    found++;
                    sb.append("\n    [").append(i).append("] \"").append(anchors[i])
                      .append("\"  strIdx=").append(idx[i]);
                    List<?> hits = (List<?>) sitesOf.invoke(null, dex, map, idx[i], i);
                    sb.append("  sites=").append(hits.size());
                    for (int k = 0; k < hits.size(); k++) {
                        Object c = hits.get(k);
                        Field fc = c.getClass().getDeclaredField("className");
                        Field fm = c.getClass().getDeclaredField("methodName");
                        fc.setAccessible(true);
                        fm.setAccessible(true);
                        sb.append("\n         -> ").append(fc.get(c)).append('.').append(fm.get(c));
                    }
                }
                if (found > 0) {
                    System.out.println(sb.toString());
                }
                System.out.println("    .. " + n + " " + (System.currentTimeMillis() - t1) + "ms");
            }
        } finally {
            zf.close();
        }
        System.out.println("total=" + (System.currentTimeMillis() - t0) + "ms");
    }

    static byte[] read(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
