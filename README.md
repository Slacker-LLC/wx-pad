# 微信平板模式（极简版）

这是一个 Android Xposed 模块。它让微信把手机识别为 Lenovo TB-9707F 平板，并修改平板登录校验结果。此功能用于让同一微信号在手机和平板上同时在线。

本项目是「飞鱼」（`com.sky.ant.plus`）的独立重写版，与原版作者无关。它只保留平板登录功能。

| 项目 | 信息 |
|---|---|
| 模块包名 | `cc.liujiale.wxpad` |
| 桌面显示名 | 微信平板模式（极简版） |
| Xposed API | libxposed API 102（`io.github.libxposed.api`） |
| 作用域 | `com.tencent.mm`，由模块固定 |
| 构建产物 | `build/wxpad.apk` |
| 依赖 | 无第三方运行时依赖 |
| 许可 | Apache-2.0；图标素材除外，见 [NOTICE.md](NOTICE.md) |

> **使用前请阅读[免责声明](DISCLAIMER.md)。**修改微信客户端行为可能带来账号风险。

## 安装与启用

1. 按下方说明构建 APK，并安装 `build/wxpad.apk`。
2. 在支持 libxposed API 102 的框架里启用本模块，例如 Vector 或新版 LSPosed。模块列表会把它显示为 `API 102`。
3. 微信会被自动勾选，作用域由模块固定为 `com.tencent.mm`，无需手动添加。
4. 停用原版「飞鱼」，避免两个模块同时 Hook 同一逻辑。
5. 强制停止微信，再重新打开。

框架只会在启用或重新启用模块时读取作用域。如果微信没有被勾上，请停用后重新启用模块。

## 构建

需要 JDK 17 和 Android SDK（build-tools 36.0.0、platforms/android-36）。按需修改 `build.sh` 中的 SDK 与签名文件路径，然后运行：

```bash
bash build.sh
```

APK 输出到 `build/wxpad.apk`。`api-src/` 中的 libxposed API 桩只用于编译，不会打包进 APK。

## 自动适配与限制

模块扫描微信 APK 中的 DEX 字符串，查找目标方法，再用反射检查方法签名。它不依赖固定的微信类名，并会缓存扫描结果。

自动适配不代表支持所有版本。目前已在微信 8.0.79（versionCode 3200）上验证。微信更新后，如果相关字符串或方法签名改变，模块可能无法生效。可在框架日志中按 tag `wxpad` 查找 `kind N not hooked` 或 `scan found 0 target(s)`。

模块只对微信包名 `com.tencent.mm` 生效。微信仍会按原流程发送平板登录校验请求；本模块不单独发起网络请求。

## 许可与风险

项目代码以 Apache License 2.0 发布，见 [LICENSE](LICENSE)。应用图标复用自原版「飞鱼」，不包含在本项目许可证内；来源与再分发说明见 [NOTICE.md](NOTICE.md)。

修改微信客户端行为可能违反微信用户协议，也可能触发账号风控。使用前请阅读[免责声明](DISCLAIMER.md)，并自行评估风险。
