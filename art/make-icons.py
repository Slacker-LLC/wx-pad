# 图标生成工具：由 art/*.svg 生成 res/ 下的图标资源。
# 只在改图标时手动运行，不参与构建。用法：
#
#   python -I art/make-icons.py build
#
# 三步：写 HTML 包装 → Chrome 无头截图 512px PNG（透明底）→ PIL 缩放为 5 档 webp。
# res/ 下的图标资源是生成物，别直接改；要改图标就改 art/ 下的 SVG 再跑一遍。
# 中间产物放在 _work/icon-render/（已被 .gitignore 忽略）。
# 本机没有 ImageMagick 与 Inkscape，所以借 Chrome 渲染 SVG。
# Chrome 路径可用环境变量 CHROME 覆盖。

import os
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ART = ROOT / "art"
WORK = ROOT / "_work" / "icon-render"
RES = ROOT / "res"
CHROME = os.environ.get("CHROME", r"C:\Program Files\Google\Chrome\Application\chrome.exe")

SIZES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
# (art/ 下的源文件, res/ 下的资源名)
VARIANTS = [("wxpad-icon", "ic_launcher"), ("wxpad-icon-round", "ic_launcher_round")]

HTML_TPL = (
    '<!doctype html><meta charset="utf-8">'
    "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}"
    "svg{display:block;width:512px;height:512px}</style>"
)


def render(html, png, profile):
    cmd = [
        CHROME,
        "--headless=new",
        "--disable-gpu",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-extensions",
        "--disable-background-networking",
        "--user-data-dir=" + str(profile),
        "--hide-scrollbars",
        "--force-device-scale-factor=1",
        "--window-size=512,512",
        "--default-background-color=00000000",   # 透明底
        "--virtual-time-budget=3000",
        "--screenshot=" + str(png),
        html.as_uri(),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if not png.exists():
        raise SystemExit("Chrome 没有产出截图：" + (r.stderr or r.stdout or "")[-2000:])


def build():
    from PIL import Image

    if not pathlib.Path(CHROME).exists():
        raise SystemExit("找不到 Chrome：" + CHROME + "（可用环境变量 CHROME 指定）")

    WORK.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="wxpad-icon-") as td:
        td = pathlib.Path(td)
        profile = td / "profile"
        profile.mkdir()
        for src_name, res_name in VARIANTS:
            svg = (ART / (src_name + ".svg")).read_text(encoding="utf-8")
            html = td / (src_name + ".html")
            html.write_text(HTML_TPL + svg, encoding="utf-8")

            png = WORK / (src_name + "-512.png")
            render(html, png, profile)

            im = Image.open(png).convert("RGBA")
            w, h = im.size
            px = im.load()
            print(src_name, "size", im.size,
                  "corner_alpha", px[0, 0][3], px[w - 1, 0][3],
                  "center_alpha", px[w // 2, h // 2][3])
            if im.size != (512, 512) or px[0, 0][3] != 0 or px[w // 2, h // 2][3] != 255:
                raise SystemExit("渲染结果不对：应为 512×512、四角透明、中心不透明")

            for dpi, size in SIZES:
                out = im.resize((size, size), Image.LANCZOS)
                p = RES / ("mipmap-" + dpi) / (res_name + ".webp")
                out.save(p, "WEBP", lossless=True)
                print("  ", p.relative_to(ROOT), os.path.getsize(p), "bytes")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "build":
        build()
    else:
        print(__doc__)
        sys.exit(2)
