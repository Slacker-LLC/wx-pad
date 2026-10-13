# 素材与来源声明

本项目的主体代码与图标均以 Apache License 2.0 发布。许可全文见 [LICENSE](LICENSE)。
本文件是 Apache-2.0 第 4(d) 条所指的 **NOTICE 文件**。
再分发本项目时，请保留本文件中的署名信息。

---

## 一、图标

图标（白底 + 绿色 `WX` + 深色 `PAD` 字标）为本项目原创，随本项目一起按 Apache-2.0 发布。
源文件在 `art/` 目录：

| 源文件 | 说明 |
| --- | --- |
| `art/wxpad-icon.svg` | 方形图标（白色圆角矩形底） |
| `art/wxpad-icon-round.svg` | 圆形图标（白色整圆底） |

`res/` 下的图标资源由这两个源文件生成，是生成物，不要直接改。
生成工具是 `art/make-icons.py`，用法见文件头注释。对应关系如下：

| 生成物 | 说明 |
| --- | --- |
| `res/drawable/ic_launcher_foreground.xml` | 矢量前景（API 26+ 自适应图标用），字标颜色 `#07A855` 与 `#15171C` |
| `res/values/colors.xml` 中的 `ic_launcher_background` | 背景色 `#ffffffff` |
| `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.webp` | 48/72/96/144/192 五档位图，API 24/25 的回退 |

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
3. `res/` 下的图标资源与字符串资源。图标为本项目原创，见第一节。
