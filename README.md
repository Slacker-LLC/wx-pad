# 微信平板模式（极简版）

一个 Xposed 模块，让微信以为本机是一台联想平板，从而解锁平板登录模式 ——
同一个微信号可以在手机和平板（也包括伪装成平板的第二台手机）上**同时在线**。

它是「飞鱼」（`com.sky.ant.plus`）的**自动适配重写版**：不硬编码任何微信类名，
微信升级换类名后自己找得到目标；同时砍掉了原版的全部附加功能。

图标复用原版，功能与原版一致，但**与原版作者无关** —— 这是本项目的独立重写，
不是原版的官方后续版本。

- 包名：`com.sky.ant.min`
- 应用显示名：手机桌面上叫「飞鱼极简版」（沿用原版图标，老用户一眼认得出）
- 产物：`build/fy-min.apk`（约 54 KB，其中约 33 KB 是图标；原版 1.46 MB）
- 依赖：**零**（无 androidx、无 Kotlin，整个 APK 只有一个 dex）
- 许可：Apache-2.0（图标素材除外，见 [NOTICE.md](NOTICE.md)）

> ⚠️ **使用前请先读[免责声明](DISCLAIMER.md)**：本模块 Hook 修改微信客户端行为，
> 存在账号风控风险，后果自负。

---

## 一、它到底做了什么

只 hook 两个方法：

| # | 语义 | hook 方式 |
|---|---|---|
| 0 | 本地判定「当前设备是不是 Lenovo TB-9707F 平板」 | `beforeHookedMethod` 里无条件 `setResult(true)` |
| 1 | 向服务器校验「能否以平板身份登录」的协程请求 | `afterHookedMethod` 里把 `Boolean` 结果改成 `true` |

第 0 个是纯本地计算（读 `Build.MODEL` 比对字符串，没有副作用），所以直接短路返回 true。
第 1 个会真的发网络请求，只在拿到应答后改结果，不干扰请求本身。

这两个方法是静态分析微信 8.0.79 得到的：`com.tencent.mm.ui.j9.a()` 和
`l41.j1.a(String, String, Continuation)`。

---

## 二、自动适配是怎么做的

### 不用类名，用**语义锚点**

微信每次升级都会混淆、重排类名（8.0.72 的类名到 8.0.79 就全没了），
但字符串字面量往往保留下来。所以本模块扫微信 APK 里的 dex，
按字符串锚点反查「哪段代码用了这个字符串」，从而定位方法。

锚点表（`DexScanner.ANCHORS`），按优先级排列：

| 目标 | 优先级 | 锚点 | 说明 |
|---|---|---|---|
| 本地判定 | 0 | `"Lenovo TB-9707F"` | 完整设备型号，最精确 |
| 本地判定 | 1 | `"lenovo"` | 品牌串，兜底 |
| 服务器校验 | 2 | `"/cgi-bin/micromsg-bin/checkloginaspad"` | 接口 URL，最精确 |
| 服务器校验 | 3 | `"MicroMsg.CgiCheckLoginAsPad"` | 协程日志 tag |
| 服务器校验 | 4 | `"doCgi callback loginAsPad:"` | 同方法内的另一条日志 |
| 服务器校验 | 5 | `"doCgi deviceBrand:"` | 同方法内的又一条日志 |
| 服务器校验 | 6 | `"checkloginaspad"` | URL 子串，兜底 |
| 服务器校验 | 7 | `"CgiCheckLoginAsPad"` | tag 子串，兜底 |

每个目标都挂了多个**互不相干**的锚点：只要微信还留着其中任意一个，定位就不会断。
4、5 是同一方法里另外两条日志文案，2、3 是接口路径和协程 tag，
改动它们的收益对微信来说是零，所以多条同时失效的概率极低。

### 候选池：锚点之间可以互相顶替

锚点给出的方法**全部保留**，按锚点优先级排序去重，交给调用方逐个做反射校验，
第一个签名通过的生效。命中即停就没有这个余地了。

实测微信 8.0.79 的扫描结果：

```
RESULT 0|com.tencent.mm.ui.j9|a        ← 锚点 0 命中，最可信
RESULT 0|com.tencent.mm.matrix.p|run   ← 锚点 1（"lenovo" 子串）的噪声
RESULT 0|com.tencent.mm.matrix.o|j
RESULT 0|com.tencent.mm.ui.sj|g
RESULT 1|l41.j1|a
```

`"lenovo"` 这种短词在一个 dex 里能命中好几个方法，其中三个是无关代码。
排序让正确的排第一；**万一哪天真排不到第一了，反射校验也能把它们全挡掉**，
不会挂错方法。6 个 CGI 锚点在 8.0.79 上全部指向同一个 `l41.j1.a`，
去重后只剩一条 —— 它们真正的价值不是提供备选，而是「谁失效都还有别人兜着」。

### 扫描流程

1. 打开微信的 `base.apk` 及全部 split APK，遍历里面的每个 `.dex`；
2. 一次遍历 `string_ids` 表，把所有锚点的字符串序号（strIdx）一次找齐
   —— 先按「整串相等」匹配，没命中的再按「子串包含」兜底；
3. 在每个 dex 里搜 `const-string` / `const-string/jumbo` 指令，反查引用该字符串的方法；
4. 沿 `method_id` → `class_def` 还原出 `类名 + 方法名`；
5. **反射二次校验**：候选按锚点优先级排好后逐个 `Class.forName` + 比对方法签名，
   第一个通过的生效，其余丢弃。这一步保证即使扫到同名类也不会挂错方法：

   - 本地判定：`static boolean ()`
   - 服务器校验：非 static，前两个参数是 `String`、最后一个是 `Continuation`、返回 `Object`

   第二条不写死 `kotlin.coroutines.Continuation` 这个全限定名 —— Kotlin 挂起函数
   编译到 JVM 上本来就是 `(普通参数…, Continuation) → Object`，按这个语义判断，
   微信换协程库或改包名都不会让校验失效。

   某个目标一个都没通过时，日志会把试过的候选全部列出来，方便对照 dex 排查。

### 缓存

第一次启动要扫约 200 MB 的 dex（微信 8.0.79 实测 1.3 秒，只在首次）。
结果写进微信自己的 `files/fymin.targets`，缓存键是
**微信 versionCode + APK 总字节数** —— 微信一升级立刻失效重扫。

读缓存时对每条记录重跑一次反射校验：单条失效只丢那一条（同一目标的
其他候选还能用），某个目标一个候选都不剩了才整份作废、重新扫描。

### 热修复清理

启动时删掉微信 `dataDir` 下的 `tinker` / `tinker_server` / `tinker_temp` 目录
（原版行为，防止热修复把旧 class 覆盖回来）。

---

## 三、相比原版砍掉了什么

| 原版有 | 极简版 |
|---|---|
| 按微信版本硬编码 4 张类名/方法名表 | 锚点扫描 + 反射校验，自动适配 |
| Compose 设置页、开关项 | 无界面，装上即生效 |
| 公益跳转弹窗、说明文案、教程 | 无 |
| 微信搜一搜卡片、LauncherUI 菜单注入 | 无 |
| 自研封装框架 / 组件工厂（Kotlin + androidx） | 无，纯 Java 标准库 |
| 网络图床 / 更新检查 | 无，模块本身不联网 |
| 要求 versionCode ∈ [3100, 3180] | 不限制版本 |

原版在微信 8.0.79（versionCode 3200）上因为类名全部消失、且超出它的版本窗口，
已经完全失效；本模块在 8.0.79 上实测定位正确。

---

## 四、构建

需要 JDK 17 + Android SDK（build-tools 36.0.0、platforms/android-36）。
构建脚本里的 SDK 路径和签名 keystore 路径按需修改。

```bash
bash build.sh
```

流程：`javac` 编译 → `d8` 转 dex → `aapt2 compile` 编译资源 → `aapt2 link` 打包
→ 注入 `classes.dex` → `zipalign` 对齐 → `apksigner` 签名 → 自检。

**关键约束**：`api-src/` 下的 Xposed 接口桩**只用于 javac 编译**，
绝不能进 APK（运行时必须用框架注入的真实类）。脚本通过「d8 只喂
`build/classes/com/sky/ant/min`」来保证这一点，构建后可用 dexdump 复核。

---

## 五、桌面验证（不参与 APK 构建）

`verify/` 下有个能在电脑 JDK 上直接跑扫描器的入口，用来验证锚点是否还能命中：

```bash
javac -encoding UTF-8 -nowarn -d verify/build \
    src/com/sky/ant/min/DexScanner.java src/com/sky/ant/min/Target.java \
    verify/ScanTest.java verify/de/robv/android/xposed/XposedBridge.java

java -cp verify/build com.sky.ant.min.ScanTest "path/to/wechat/base.apk"
```

（输出目录特意放在 `verify/build` 而不是 `build/verify`：`build.sh` 开头会 `rm -rf build`。）

- 不带 `--probe`：跑完整扫描，输出 `RESULT <kind>|<class>|<method>` 和耗时；
- 加 `--probe`：逐个 dex 打印每个锚点的 strIdx、命中的类名方法名、逐 dex 耗时。

在微信 8.0.79 上的实测结果（`--probe` 摘要）：

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

8 个锚点全部命中。6、7 的 strIdx 与 2、3 相同 —— 完整字面量还在时，
子串锚点必然命中同一处；它们只在完整字面量被改写后才发挥作用。

---

## 六、安装与启用

1. 手机装 `build/fy-min.apk`；
2. 在 LSPosed / Vector 里启用本模块 —— **微信会被自动勾上**（见下），不用手动找；
3. 强制停止微信，再打开；
4. 看日志确认（见下）。

### 作用域为什么不用手动勾

模块清单里声明了 `xposedscope`：

```xml
<meta-data android:name="xposedscope" android:value="com.tencent.mm" />
```

LSPosed 系框架（含 Vector）在被启用时会读这个 meta-data，把列出的包**预勾选**，
所以第一次启用就能直接用。多个包用 `;` 分隔，`android` 表示系统框架；本模块只需要微信。

两点说明：

- **框架里的作用域列表仍然显示全部已安装应用**，模块无权过滤成「只显示微信」。
  那个列表由框架 UI 渲染，模块能做的只是「推荐勾谁」，不能改列表本身。
- 预勾选**只在模块被启用（或重新启用）时读取**。已经装过旧版的话，
  要停用再重新启用一次，新清单才会生效。

> **启用本模块前请先停用原版「飞鱼」**：两者 hook 同一处逻辑，
> 同时开启会互相覆盖，也可能让微信的平板登录校验行为变得不确定。

### 日志

本模块所有日志以 `fy-min: ` 开头，可在 LSPosed 日志里过滤 `fy-min`：

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

若出现 `kind N not hooked` 或 `scan found 0 target(s)`，说明微信改版把锚点字符串也改掉了，
需要更新 `DexScanner.ANCHORS` —— 用第五节的 `--probe` 模式逐个 dex 看锚点还剩几个能命中。

---

## 七、已知限制与风险

- **锚点依赖字符串字面量**：这是本模块最脆的一环。缓解措施是每个目标挂 2~6 个
  互不相干的锚点 + 候选池 + 反射校验，单个锚点失效不影响功能；
  只有**全部**锚点同时被改掉才会失守。失守是**静默**的（功能不生效，不会崩溃），
  靠日志里的 `kind N not hooked` 排查。
- **模板串类型的字符串不能用**：同一方法里的 `"call to 'resume' before 'invoke'
  with coroutine"` 是 Kotlin 编译器生成的，全 APK 到处都有，做锚点会扫出一堆噪声，
  所以没进锚点表。
- **扫描耗时**：首次启动要解压全部 dex，微信本体大时约数百毫秒到数秒；
  之后走缓存。缓存文件在微信 `files/` 下，不是模块自己的目录。
- **不是安全边界**：本模块只对 `com.tencent.mm` 生效，其他应用不受影响。
- **账号风险**：修改微信客户端行为（伪装设备型号 + 篡改平板登录校验应答）
  属于客户端行为改造，理论上存在被风控判定为异常的**账号风险**。
  是否使用请自行权衡。

---

## 八、许可证与第三方素材

本项目以 **Apache License 2.0** 发布，全文见 [LICENSE](LICENSE)，版权归 `Slacker-LLC` 所有。

Apache-2.0 是宽松型许可证，允许商用、修改、再分发，也允许闭源衍生，条件只有三条：
保留版权与许可声明、标明改过的文件、再分发时保留 [NOTICE.md](NOTICE.md) 里的署名信息。
相比 MIT，它多给了一条**明确的专利授权**（第 3 条），对被许可方更有保障。

**使用前请阅读 [DISCLAIMER.md](DISCLAIMER.md)**（免责声明），要点：

- 本模块是非官方项目，与腾讯 / 微信无任何关系；
- 修改微信客户端行为可能违反其用户协议，**存在账号被风控的风险**，后果由使用者自负；
- 本模块不申请任何权限、不联网、不收集任何数据；
- 软件按「现状」提供，不附带任何担保。

### 图标

应用图标**复用自原版「飞鱼」**（作者：星空下的风），以便老用户识别：

- API 26+ 走自适应图标（`res/mipmap-anydpi-v26/`）：白色背景 + 飞鱼矢量前景 + 同款 monochrome；
- API 24/25 回退到 5 档密度的 webp 位图兜底。

**这些图形的著作权属于原作者，不在本项目的 Apache-2.0 许可覆盖范围内。**
完整的来源对照表与再分发注意事项见 [NOTICE.md](NOTICE.md)；
替换图标只需改 `res/` 下的文件，不需要动任何 Java 代码。

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
