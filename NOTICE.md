# 第三方素材与来源声明

本项目主体代码以 MIT 许可证发布（见 [LICENSE](LICENSE)）。
但**下列第三方内容不在 MIT 许可的覆盖范围内**，其权利归各自权利人所有。

---

## 一、图标素材（复用自原版「飞鱼」）

本模块的应用图标**复用原版「飞鱼」模块的图标**，以便老用户能够直接识别。原版信息：

| 项目 | 值 |
| --- | --- |
| 模块名 | 飞鱼 |
| 包名 | `com.sky.ant.plus` |
| 作者 | 星空下的风 |

对应关系：

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

**这些图形的著作权属于原版「飞鱼」的作者。** 本项目出于识别性与兼容性目的复用，是作者本人（Slacker-LLC）做出的技术选择。

如果你打算再分发本项目，请自行确认该图形素材的授权状态。若有疑问，建议先联系原作者取得许可，或替换为原创图标——替换只需改动上表列出的 `res/` 下文件，**不需要改动任何 Java 代码或构建脚本**。

## 二、Xposed API 编译桩

`api-src/` 下的接口定义是依据 Xposed 框架公开 API 编写的**编译期桩**，用途只是让模块代码能通过 `javac` 编译。

- 这些桩**不会**被打包进 APK：构建脚本只把 `build/classes/com/sky/ant/min` 交给 `d8`（见 [build.sh](build.sh) 第 2 步），`api-src/` 整个目录被排除在外；
- 运行时使用的是由框架注入的真实实现；
- Xposed 框架及其 API 由 rovo89 等开发者以 Apache License 2.0 发布。

## 三、不含第三方运行时依赖

本模块不依赖任何第三方库，不包含 GPL 代码。APK 内只有三类内容：

1. `classes.dex` —— 本项目编译产物
2. `AndroidManifest.xml` + `assets/xposed_init`
3. `res/` 下的图标资源（见第一节）
