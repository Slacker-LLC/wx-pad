package cc.liujiale.wxpad;

/**
 * 一个待 hook 的目标：类名 + 方法名 + 语义种类。
 */
final class Target {

    /** 本地设备判定（静态、无参、返回 boolean）：hook 前置，直接给 true。 */
    static final int KIND_LOCAL_DEVICE = 0;

    /** 服务器校验（实例、(String, String, Continuation) 返回 Object）：hook 后置，仅当结果为 Boolean 时改成 true。 */
    static final int KIND_CGI_CHECK = 1;

    /** 种类总数：调用方按种类清点「哪些目标已经挂上」。 */
    static final int KIND_COUNT = 2;

    final int kind;
    final String className;
    final String methodName;

    Target(int kind, String className, String methodName) {
        this.kind = kind;
        this.className = className;
        this.methodName = methodName;
    }

    /** 缓存行格式：kind|类名|方法名 */
    @Override
    public String toString() {
        return kind + "|" + className + "|" + methodName;
    }

    static Target parse(String line) {
        if (line == null) {
            return null;
        }
        String[] p = line.split("\\|");
        if (p.length != 3 || p[1].length() == 0 || p[2].length() == 0) {
            return null;
        }
        int k;
        try {
            k = Integer.parseInt(p[0]);
        } catch (Throwable t) {
            return null;
        }
        if (k != KIND_LOCAL_DEVICE && k != KIND_CGI_CHECK) {
            return null;
        }
        return new Target(k, p[1], p[2]);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Target)) {
            return false;
        }
        Target t = (Target) o;
        return kind == t.kind && className.equals(t.className) && methodName.equals(t.methodName);
    }

    @Override
    public int hashCode() {
        return (kind * 31 + className.hashCode()) * 31 + methodName.hashCode();
    }
}
