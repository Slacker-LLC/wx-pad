#!/usr/bin/env bash
# 微信平板模式（极简版）构建脚本（Windows / Git Bash）
#
# 流程：编译桩+模块 → d8 转 dex → aapt2 编译资源 → aapt2 打包 → 注入 dex 与 META-INF → 对齐 → 签名
# 关键约束：libxposed API 桩只用于编译，绝不进 dex——运行时必须用框架注入的真实类。

set -euo pipefail

SDK="C:/Users/liu-j/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
KS="C:/Users/liu-j/.android/debug.keystore"
KS_ALIAS="androiddebugkey"
KS_PASS="android"

cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes build/dex build/stage/META-INF/xposed

echo "=== 1/7 编译（libxposed API 桩 + 模块本体一起编） ==="
javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 \
    -classpath "$PLATFORM" \
    -d build/classes \
    $(find api-src src -name '*.java')

echo "=== 2/7 转 dex（只喂 cc/liujiale/wxpad，桩不进 APK） ==="
"$BT/d8.bat" --min-api 24 --lib "$PLATFORM" --output build/dex \
    $(find build/classes/cc/liujiale/wxpad -name '*.class')

echo "=== 3/7 编译资源（mipmap 图标 + colors + strings） ==="
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "=== 4/7 aapt2 打包 manifest + 资源 ==="
# 注意：编译产物 zip 必须作为位置参数传入。若用 -R 传入，aapt2 会按 overlay
# 语义处理并报 "resource ... does not override an existing resource"。
"$BT/aapt2.exe" link \
    -o build/base.apk \
    --manifest AndroidManifest.xml \
    -I "$PLATFORM" \
    --min-sdk-version 24 \
    --target-sdk-version 36 \
    build/res.zip

echo "=== 5/7 注入 classes.dex 与 META-INF/xposed ==="
# xposed/ 下的三份文件必须落在 APK 内的 META-INF/xposed/ 下：
# java_init.list 声明入口类，module.prop 声明 API 版本，scope.list 声明作用域。
# 框架按「有没有 META-INF/xposed/java_init.list」判定这是不是现代模块。
cp xposed/java_init.list xposed/module.prop xposed/scope.list build/stage/META-INF/xposed/
( cd build/dex && jar --update --file ../base.apk --no-manifest classes.dex )
( cd build/stage && jar --update --file ../base.apk --no-manifest META-INF )

echo "=== 6/7 对齐 + 签名 ==="
"$BT/zipalign.exe" -f 4 build/base.apk build/aligned.apk
"$BT/apksigner.bat" sign \
    --ks "$KS" \
    --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --out build/wxpad.apk \
    build/aligned.apk

echo "=== 7/7 自检 ==="
"$BT/apksigner.bat" verify --print-certs build/wxpad.apk
"$BT/aapt2.exe" dump badging build/wxpad.apk \
    | rg '^(package|application-label:|sdkVersion|targetSdkVersion|application-icon)'
echo "--- 模块声明（框架按这三份文件加载本模块） ---"
jar --list --file build/wxpad.apk | rg '^META-INF/xposed/'
ls -l build/wxpad.apk

echo
echo "产物：$(pwd)/build/wxpad.apk"
