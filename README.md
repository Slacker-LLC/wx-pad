# 微信平板模式（极简版）

本模块是一个 Xposed 模块。它让微信认为本机是一台联想平板。微信据此允许本机以平板模式登录。
结果：同一个微信号可以在手机和平板上**同时在线**。「平板」也包括伪装成平板的第二台手机。

本模块是「飞鱼」（`com.sky.ant.plus`）的重写版，功能与原版相同。
重写版不写死任何微信类名。微信升级换类名后，本模块自己找得到目标方法。
重写版还删除了原版的全部附加功能。

本模块复用原版图标。本模块与原版作者无关。
它是独立重写，不是原版的官方后续版本。

| 项目 | 值 |
|---|---|
| 包名 | `com.sky.ant.min` |
| 应用显示名 | 「飞鱼极简版」（手机桌面上显示） |
| 产物 | `build/fy-min.apk`（约 54 KB，其中约 33 KB 是图标；原版 1.46 MB） |
| 依赖 | **零**。不使用 androidx，不使用 Kotlin，整个 APK 只有一个 dex |
| 许可 | Apache-2.0。图标素材除外，见 [NOTICE.md](NOTICE.md) |

> ⚠️ **使用前请先读[免责声明](DISCLAIMER.md)。**
> 本模块修改微信客户端的行为，存在账号风控风险。后果由使用者承担。

---

## 一、本模块做什么

本模块只 Hook 两个方法。

| # | 目标 | Hook 方式 |
|---|---|---|
| 0 | 判断本机是不是 Lenovo TB-9707F 平板 | `beforeHookedMethod` 中无条件 `setResult(true)` |
| 1 | 向服务器校验「能否以平板身份登录」的协程请求 | `afterHookedMethod` 中把 `Boolean` 结果改成 `true` |

方法 0 是纯本地计算。它读取 `Build.MODEL`，再比较字符串。这个方法没有副作用。
所以本模块直接返回 `true`，不再执行原方法。

方法 1 会发送网络请求。本模块等应答到达后修改结果，不干扰请求本身。

这两个方法来自对微信 8.0.79 的静态分析：
`com.tencent.mm.ui.j9.a()` 和 `l41.j1.a(String, String, Continuation)`。

---

## 二、自动适配的原理

### 本模块不依赖类名

微信每次升级都会混淆类名，也会重排类名。
例如：微信 8.0.72 的类名，到 8.0.79 全部消失。但是字符串字面量通常会保留下来。

本模块扫描微信 APK 里的 dex 文件。它按字符串找到相关代码，再反查方法。
这些字符串叫**锚点**。

锚点表在 `DexScanner.ANCHORS` 中，按优先级排列：

| 目标 | 优先级 | 锚点 | 说明 |
|---|---|---|---|
| 本地判定 | 0 | `"Lenovo TB-9707F"` | 完整设备型号，最精确 |
| 本地判定 | 1 | `"lenovo"` | 品牌串，兜底 |
| 服务器校验 | 2 | `"/cgi-bin/micromsg-bin/checkloginaspad"` | 接口 URL，最精确 |
| 服务器校验 | 3 | `"MicroMsg.CgiCheckLoginAsPad"` | 协程日志标签 |
| 服务器校验 | 4 | `"doCgi callback loginAsPad:"` | 同一方法内的另一条日志 |
| 服务器校验 | 5 | `"doCgi deviceBrand:"` | 同一方法内的又一条日志 |
| 服务器校验 | 6 | `"checkloginaspad"` | URL 子串，兜底 |
| 服务器校验 | 7 | `"CgiCheckLoginAsPad"` | 标签子串，兜底 |

每个目标都挂多个**互不相关**的锚点。只要微信保留其中一个锚点，本模块就能找到目标方法。

锚点 2 和 3 是接口路径与协程标签，锚点 4 和 5 是同一方法内的另外两条日志文案。
微信修改这些字符串没有任何收益。所以多个锚点同时失效的概率极低。

### 候选池：锚点可以互相替补

本模块保留锚点找到的**全部**方法，并按锚点优先级排序去重。
调用方逐个做反射校验。第一个通过校验的方法生效。

如果找到第一个方法就停止，本模块就没有替补可用。

微信 8.0.79 的实际扫描结果：

```
RESULT 0|com.tencent.mm.ui.j9|a        ← 锚点 0 命中，最可信
RESULT 0|com.tencent.mm.matrix.p|run   ← 锚点 1（"lenovo" 子串）的噪声
RESULT 0|com.tencent.mm.matrix.o|j
RESULT 0|com.tencent.mm.ui.sj|g
RESULT 1|l41.j1|a
```

`"lenovo"` 是一个短词，它在一个 dex 里命中多个方法，其中三个方法与本模块无关。
排序把正确的方法放在第一。如果正确的方法排不到第一，反射校验也会挡掉其他方法。
本模块不会挂错方法。

6 个 CGI 锚点在 8.0.79 上全部指向 `l41.j1.a`，去重后只剩一条。
这些锚点的价值不是提供替补，而是互相兜底。

### 扫描流程

本模块按以下步骤扫描：

1. 打开微信的 `base.apk` 和全部 split APK。
2. 遍历其中每个 `.dex` 文件。
3. 遍历一次 `string_ids` 表，一次找齐全部锚点的字符串序号（strIdx）。
   先按整串相等匹配，未命中的锚点再按子串包含匹配。
4. 在每个 dex 里搜索 `const-string` 与 `const-string/jumbo` 指令，反查引用该字符串的方法。
5. 沿 `method_id` 与 `class_def` 还原类名和方法名。
6. 做反射校验。候选按锚点优先级排序后逐个检查，第一个通过的生效，其余丢弃。

反射校验的签名规则：

| 目标 | 规则 |
|---|---|
| 本地判定 | `static boolean ()` |
| 服务器校验 | 非 static；前两个参数是 `String`；最后一个是 `Continuation`；返回 `Object` |

第二条规则不写死 `kotlin.coroutines.Continuation` 这个全限定名。
Kotlin 挂起函数编译到 JVM 后，签名总是 `(普通参数…, Continuation) → Object`。
本模块按这个语义判断。微信更换协程库或修改包名，校验都不会失效。

如果某个目标没有任何候选通过校验，日志会列出全部试过的候选。
你可以对照 dex 文件排查。

### 缓存

第一次启动时，本模块扫描约 200 MB 的 dex 数据。
在微信 8.0.79 上，这次扫描耗时 1.3 秒。此后不再扫描。

扫描结果写入微信的 `files/fymin.targets`。
缓存键是**微信 versionCode + APK 总字节数**。微信升级后缓存立即失效，本模块重新扫描。

读取缓存时，本模块对每条记录重新做一次反射校验。
单条记录失效只丢那一条，同一目标的其他候选仍然可用。
某个目标的所有候选都失效后，整份缓存作废，本模块重新扫描。

### 热修复清理

启动时，本模块删除微信 `dataDir` 下的 `tinker`、`tinker_server`、`tinker_temp` 目录。
这是原版的行为，目的是防止热修复把旧 class 覆盖回来。

---

## 三、与原版的差异

| 项目 | 原版 | 极简版 |
|---|---|---|
| 版本适配 | 按微信版本写死 4 张类名/方法名表 | 锚点扫描 + 反射校验，自动适配 |
| 设置界面 | Compose 设置页与开关项 | 无界面，安装后直接生效 |
| 附加内容 | 公益跳转弹窗、说明文案、教程 | 无 |
| 功能注入 | 微信搜一搜卡片、LauncherUI 菜单 | 无 |
| 框架 | 自研封装框架与组件工厂（Kotlin + androidx） | 无，只用 Java 标准库 |
| 网络 | 网络图床与更新检查 | 无，本模块不联网 |
| 版本限制 | 要求 versionCode ∈ [3100, 3180] | 不限制版本 |

原版在微信 8.0.79（versionCode 3200）上已经完全失效。
原因有两个：类名全部消失；版本号超出原版的窗口。

本模块在微信 8.0.79 上定位正确。

---

## 四、构建

### 环境要求

- JDK 17
- Android SDK，含 build-tools 36.0.0 与 platforms/android-36

### 步骤

1. 按需修改 `build.sh` 中的 SDK 路径和签名 keystore 路径。
2. 运行构建：

```bash
bash build.sh
```

构建流程共 8 步：

1. `javac` 编译 Java 源码。
2. `d8` 转成 dex。
3. `aapt2 compile` 编译资源。
4. `aapt2 link` 打包。
5. 注入 `classes.dex`。
6. `zipalign` 对齐。
7. `apksigner` 签名。
8. 自检。

### 关键约束

`api-src/` 下的 Xposed 接口桩**只用于 javac 编译**。
这些桩**绝不能**进入 APK，运行时必须使用框架注入的真实类。

构建脚本只把 `build/classes/com/sky/ant/min` 交给 `d8`，以此保证这一点。
构建完成后，可以用 dexdump 复核。

---

## 五、桌面验证

`verify/` 下有一个验证入口。它在电脑 JDK 上直接运行扫描器，用来验证锚点是否还能命中。
这个工具不参与 APK 构建。

### 编译

```bash
javac -encoding UTF-8 -nowarn -d verify/build \
    src/com/sky/ant/min/DexScanner.java src/com/sky/ant/min/Target.java \
    verify/ScanTest.java verify/de/robv/android/xposed/XposedBridge.java
```

输出目录用 `verify/build`，不用 `build/verify`。因为 `build.sh` 开头会执行 `rm -rf build`。

### 运行

```bash
java -cp verify/build com.sky.ant.min.ScanTest "path/to/wechat/base.apk"
```

两种模式：

| 参数 | 行为 |
|---|---|
| 不带 `--probe` | 运行完整扫描，输出 `RESULT <kind>\|<class>\|<method>` 和耗时 |
| 带 `--probe` | 逐个 dex 输出每个锚点的 strIdx、命中的类名与方法名、逐 dex 耗时 |

微信 8.0.79 上的实测结果（`--probe` 摘要）：

```
classes.dex (8905396 bytes), methods=55620
    [0] "Lenovo TB-9707F"                  strIdx=18555  sites=1
         -> com.tencent.mm.ui.j9.a
    [1] "lenovo"                           strIdx=49817  sites=2
         -> com.tencent.mm.matrix.p.run
         -> com.tencent.mm.ui.j9.a
classes13.dex (11020968 bytes), methods=48101
    [1] "lenovo"                           strIdx=66311  sites=2
         -> com.tencent.mm.matrix.o.j
         -> com.tencent.mm.ui.sj.g
classes5.dex (11515744 bytes), methods=54608
    [2] "/cgi-bin/micromsg-bin/checkloginaspad"  strIdx=3843   sites=1
         -> l41.j1.a
    [3] "MicroMsg.CgiCheckLoginAsPad"       strIdx=28214  sites=1
         -> l41.j1.a
    [4] "doCgi callback loginAsPad:"        strIdx=39592  sites=1
         -> l41.j1.a
    [5] "doCgi deviceBrand:"                strIdx=39593  sites=1
         -> l41.j1.a
    …
total=1437ms
```

8 个锚点全部命中。锚点 6 和 7 的 strIdx 与锚点 2、3 相同。
完整字面量还在时，子串锚点必然命中同一处。
只有在完整字面量被改写后，子串锚点才发挥作用。

---

## 六、安装与启用

1. 把 `build/fy-min.apk` 安装到手机。
2. 在 LSPosed 或 Vector 里启用本模块。微信会被自动勾选，不用手动查找。
3. 强制停止微信，再重新打开。
4. 查看日志确认。

### 作用域为什么自动勾选

模块清单里声明了 `xposedscope`：

```xml
<meta-data android:name="xposedscope" android:value="com.tencent.mm" />
```

LSPosed 系框架（包括 Vector）在启用模块时读取这个 meta-data，把列出的包**预勾选**。
所以第一次启用就能直接用。多个包用 `;` 分隔，`android` 表示系统框架。本模块只需要微信。

两点说明：

- 框架的作用域列表仍然显示全部已安装应用。这个列表由框架 UI 渲染，模块无权过滤。
  模块只能「推荐勾选」，不能修改列表本身。
- 框架**只在模块被启用或重新启用时**读取 `xposedscope`。
  如果你装过旧版，请先停用模块再重新启用，新清单才会生效。

> **注意**：启用本模块前，请先停用原版「飞鱼」。
> 两个模块 Hook 同一处逻辑。同时启用会互相覆盖，也可能让微信的平板登录校验结果变得不确定。

### 日志

本模块的日志都以 `fy-min: ` 开头。在 LSPosed 日志里过滤 `fy-min` 即可。

```
fy-min: start, wechat versionCode=3200
fy-min: scanning 2 apk(s)...            ← 或 cache hit, N target(s)
fy-min: scan found 5 target(s)
fy-min: signature mismatch: 0|com.tencent.mm.matrix.p|run    ← 噪声候选被签名挡掉，正常
fy-min: signature mismatch: 0|com.tencent.mm.matrix.o|j
fy-min: signature mismatch: 0|com.tencent.mm.ui.sj|g
fy-min: hooked [local] public static boolean com.tencent.mm.ui.j9.a()
fy-min: hooked [cgi] public final java.lang.Object l41.j1.a(java.lang.String,java.lang.String,kotlin.coroutines.Continuation)
fy-min: ready in 1381ms
```

如果日志里出现下面两种情况，说明微信改版时把锚点字符串也改掉了：

| 日志 | 含义 |
|---|---|
| `kind N not hooked` | 目标 N 没有挂上 |
| `scan found 0 target(s)` | 一个目标都没有找到 |

处理方法：更新 `DexScanner.ANCHORS`。
用第五节的 `--probe` 模式逐个 dex 检查，看还剩几个锚点能命中。

---

## 七、已知限制与风险

### 锚点依赖字符串字面量

这是本模块最薄弱的一环。本模块有三项缓解措施：

- 每个目标挂 2~6 个互不相关的锚点。
- 锚点找到全部候选，而不是找到第一个就停。
- 反射校验筛掉错误候选。

单个锚点失效不影响功能。只有**全部**锚点同时被改掉才会失守。
失守是**静默**的：功能不生效，但不会崩溃。用日志里的 `kind N not hooked` 排查。

### 模板字符串不能用作锚点

同一方法里的 `"call to 'resume' before 'invoke' with coroutine"` 由 Kotlin 编译器生成。
这个字符串遍布整个 APK，用它做锚点会扫出大量噪声，所以它不在锚点表里。

### 扫描耗时

首次启动时，本模块解压全部 dex。微信本体较大时，耗时为数百毫秒到数秒。
此后使用缓存。缓存文件位于微信的 `files/` 目录，不在模块自己的目录下。

### 作用范围

本模块只对 `com.tencent.mm` 生效，其他应用不受影响。本模块不是安全边界。

### 账号风险

本模块修改微信客户端的行为，修改点有两处：伪装设备型号；修改平板登录校验的应答。

这类客户端改造存在被风控判定为异常的**账号风险**。是否使用，请自行权衡。

---

## 八、许可证与第三方素材

### 许可证

本项目以 **Apache License 2.0** 发布，全文见 [LICENSE](LICENSE)。版权归 `Slacker-LLC` 所有。

Apache-2.0 是宽松型许可证，允许以下行为：

- 商用
- 修改
- 再分发
- 闭源衍生

条件是三条：

1. 保留版权与许可声明。
2. 标明修改过的文件。
3. 再分发时保留 [NOTICE.md](NOTICE.md) 中的署名信息。

相比 MIT，Apache-2.0 多一条**明确的专利授权**（第 3 条），对被许可方更有保障。

### 免责声明

使用前请阅读 [DISCLAIMER.md](DISCLAIMER.md)。要点如下：

- 本模块是非官方项目，与腾讯公司和微信没有关系。
- 修改微信客户端的行为可能违反微信用户协议。**存在账号被风控的风险**，后果由使用者承担。
- 本模块不申请任何权限，不联网，不收集任何数据。
- 本软件按「现状」提供，不附带任何担保。

### 图标

应用图标**复用自原版「飞鱼」**（作者：星空下的风），目的是方便老用户识别。

- API 26+ 使用自适应图标（`res/mipmap-anydpi-v26/`）：白色背景 + 飞鱼矢量前景 + 同款 monochrome。
- API 24/25 回退到 5 档密度的 webp 位图。

**这些图形的著作权属于原作者，不在本项目的 Apache-2.0 许可覆盖范围内。**

完整的来源对照表与再分发注意事项见 [NOTICE.md](NOTICE.md)。
替换图标只需修改 `res/` 下的文件，不需要改动任何 Java 代码。

---

## 九、目录结构

```
AndroidManifest.xml          模块清单（xposedmodule / xposeddescription / xposedminversion / xposedscope）
assets/xposed_init           入口声明：com.sky.ant.min.Main
build.sh                     构建脚本（Windows / Git Bash）
api-src/                     Xposed API 编译桩（不进 APK）
src/com/sky/ant/min/
    Main.java                入口：清热修复 → 查缓存 / 扫描 → hook
    DexScanner.java          dex 扫描器：锚点 → const-string → 方法
    Target.java              扫描结果的数据载体
res/                         应用图标（复用原版飞鱼素材，见 NOTICE.md）
    drawable/ic_launcher_foreground.xml       飞鱼 logo 矢量图形
    mipmap-anydpi-v26/*.xml                   自适应图标（API 26+）
    mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/  位图兜底（API 24/25），方形 + 圆形各一套
    values/colors.xml                         图标背景色
verify/                      桌面验证工具（不进 APK）
build/                       构建产物：fy-min.apk
LICENSE                      Apache License 2.0 全文
NOTICE.md                    第三方素材来源声明（图标、Xposed API 桩）
DISCLAIMER.md                免责声明
```

---

本文档按 **ASD-STE100**（Simplified Technical English，简化技术英语）的写作原则撰写：
一句一义、主动语态、术语一致、复杂内容改用列表与表格。
