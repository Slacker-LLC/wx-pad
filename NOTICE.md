# 第三方素材与来源声明

本项目主体代码以 Apache License 2.0 发布。许可全文见 [LICENSE](LICENSE)。
本文件是 Apache-2.0 第 4(d) 条所指的 **NOTICE 文件**。
再分发本项目时，请保留本文件中的署名信息。
下列第三方内容不受 Apache-2.0 许可约束。相关权利归各自权利人所有。

---

## 一、图标素材

本项目复用原版「飞鱼」的应用图标，方便原版用户识别。
原版信息：

| 项目 | 值 |
| --- | --- |
| 模块名 | 飞鱼 |
| 包名 | `com.sky.ant.plus` |
| 作者 | 星空下的风 |

本项目文件与原版文件的对应关系：

| 本项目文件 | 来源（原版 `com.sky.ant.plus`） | 说明 |
| --- | --- | --- |
| `res/drawable/ic_launcher_foreground.xml` | `res/Qr.xml` | 飞鱼 logo 矢量图形，路径数据一致 |
| `res/mipmap-mdpi/ic_launcher.webp` | `res/d2.webp` | 48×48 位图兜底（API 24/25） |
| `res/mipmap-hdpi/ic_launcher.webp` | `res/MO.webp` | 72×72 |
| `res/mipmap-xhdpi/ic_launcher.webp` | `res/qs.webp` | 96×96 |
| `res/mipmap-xxhdpi/ic_launcher.webp` | `res/Sn.webp` | 144×144 |
| `res/mipmap-xxxhdpi/ic_launcher.webp` | `res/sK.webp` | 192×192 |
| `res/mipmap-mdpi/ic_launcher_round.webp` | `res/yw.webp` | 48×48，圆形版本 |
| `res/mipmap-hdpi/ic_launcher_round.webp` | `res/fq.webp` | 72×72 |
| `res/mipmap-xhdpi/ic_launcher_round.webp` | `res/u5.webp` | 96×96 |
| `res/mipmap-xxhdpi/ic_launcher_round.webp` | `res/j_.webp` | 144×144 |
| `res/mipmap-xxxhdpi/ic_launcher_round.webp` | `res/-6.webp` | 192×192 |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | `res/BW.xml` | 自适应图标结构（API 26+） |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | `res/0K.xml` | 同上（原版两个文件内容相同） |
| `res/values/colors.xml` | `color/ic_launcher_background` | 背景色 `#ffffffff` |

**这些图形的著作权属于原版「飞鱼」的作者。**
本项目作者 Slacker-LLC 决定复用这些图形，以保持识别性与兼容性。

如果你要再分发本项目，请先确认这些图标素材的授权状态。
如果不确定，请联系原作者取得许可，或换用原创图标。
要替换图标，只需修改上表中 `res/` 下的文件。
无需修改 Java 代码或构建脚本。

## 二、libxposed API 编译桩

`api-src/` 下的接口定义取自 libxposed API 102.0.0 的官方源码
（Maven 坐标 `io.github.libxposed:api:102.0.0`，来源为 `api-sources.jar`）。
只做了两处改动：删去 `@RequiresApi` 等注解；把 Java 17 的 `sealed` 与 `record`
改写成等价的 Java 8 写法，以配合构建脚本的 `-source 8 -target 8`。

这些定义只是**编译期桩**，只用于让 `javac` 编译本模块代码。

- 构建脚本不会把这些桩打包进 APK。脚本只把 `build/classes/cc/liujiale/wxpad` 交给 `d8`，见 [build.sh](build.sh) 第 2 步。因此，整个 `api-src/` 目录不会进入 APK。
- 运行时使用框架注入的真实实现。
- libxposed API 由 libxposed 项目以 Apache License 2.0 发布。

## 三、不含第三方运行时依赖

本模块不依赖第三方库。APK 不含 GPL 代码。
APK 只包含以下三类内容：

1. `classes.dex` —— 本项目的编译产物。
2. `AndroidManifest.xml`，以及 `META-INF/xposed/` 下的三份模块声明（`java_init.list`、`module.prop`、`scope.list`）。
3. `res/` 下的图标资源与字符串资源，见第一节。
