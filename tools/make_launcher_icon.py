#!/usr/bin/env python3
"""把一张「黑底 + 绿色相机 + 黄色标签袋」的图做成安卓自适应启动图标。

为什么不是简单缩一张 PNG：
- **黑底必须抠掉**。自适应图标的前景层是透明层，系统会按壁纸/形状重新裁切；留一块死黑在浅色壁纸上
  就是一块突兀的黑方块。
- **主体要落在安全区里**。108dp 画布里只有中间 72dp（66.7%）保证不被任何 launcher 的圆形/水滴形遮罩切掉，
  原图主体本身就贴边（相机偏左上、袋子偏右下），直接铺满会被切。

抠图原理：图是**在纯黑上合成**的，所以 `P = C * a`（预乘 alpha）。于是
    a = max(R,G,B) / 255      → 反解 C = P / a
边缘抗锯齿自然得到半透明过渡，颜色也不会被"拉黑"。

用法：python tools/make_launcher_icon.py <源图> [--preview shots/icon_preview.png]
输出：android-app/app/src/main/res/mipmap-*/ic_launcher_foreground.png
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "android-app", "app", "src", "main", "res")

# 自适应图标的密度档：108dp 画布
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
CANVAS_DP = 108
SAFE_DP = 72          # 系统遮罩保证不切的区域（108 的 66.7%）

# 与 ZebraPalette 一致的黑底：图标背景和 App 主题同一套色
BG = (0, 0, 0)


def key_out_black(im: Image.Image) -> Image.Image:
    """黑底抠成透明（预乘反解），并清掉近黑的噪声。"""
    im = im.convert("RGBA")
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, _ = px[x, y]
            a = max(r, g, b) / 255.0
            if a < 0.06:              # 原图底色是 (8,6,8) 这种近黑噪声，留着会有灰蒙蒙的一层
                px[x, y] = (0, 0, 0, 0)
                continue
            # 反预乘：三通道除以 alpha，还原"原本的颜色"
            px[x, y] = (
                min(255, round(r / a)),
                min(255, round(g / a)),
                min(255, round(b / a)),
                round(a * 255),
            )
    return im


def fit_into(im: Image.Image, box: int) -> Image.Image:
    """等比缩放到 box×box 内（不放大超过原尺寸太多，保持清晰）。"""
    bbox = im.getbbox()
    if bbox:
        im = im.crop(bbox)
    w, h = im.size
    scale = box / max(w, h)
    return im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("--preview", default=None)
    args = ap.parse_args()

    art = key_out_black(Image.open(args.src))

    # 先按最大画布的 108 生成一张母版，再逐密度高质量缩小（比每档单独缩更锐利）
    master = DENSITIES["xxxhdpi"]
    safe = round(master * SAFE_DP / CANVAS_DP)
    fg_master = Image.new("RGBA", (master, master), (0, 0, 0, 0))
    inner = fit_into(art, safe)
    fg_master.alpha_composite(inner, ((master - inner.width) // 2, (master - inner.height) // 2))

    for dpi, size in DENSITIES.items():
        out_dir = os.path.join(RES, f"mipmap-{dpi}")
        os.makedirs(out_dir, exist_ok=True)
        img = fg_master if size == master else fg_master.resize((size, size), Image.LANCZOS)
        path = os.path.join(out_dir, "ic_launcher_foreground.png")
        img.save(path)
        print(f"{dpi:>8} {size:>4}px  {path}")

        # monochrome 层（Android 13+ 主题图标）：只保留轮廓，系统自己染色
        solid = Image.new("RGBA", img.size, (255, 255, 255, 255))
        solid.putalpha(img.getchannel("A"))
        solid.save(os.path.join(out_dir, "ic_launcher_monochrome.png"))

    if args.preview:
        # 模拟 launcher：黑底 + 圆形遮罩，看主体有没有被切、条形码还认得出吗
        prev = Image.new("RGBA", (master, master), BG + (255,))
        prev.alpha_composite(fg_master)
        mask = Image.new("L", (master, master), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, master - 1, master - 1], fill=255)
        circle = Image.new("RGBA", (master, master), (0, 0, 0, 0))
        circle.paste(prev, (0, 0), mask)
        os.makedirs(os.path.dirname(os.path.abspath(args.preview)), exist_ok=True)
        circle.resize((256, 256), Image.LANCZOS).save(args.preview)
        # 同时给一张 48px 的小尺寸，检查"在桌面上只有这么大"时的可读性
        small = circle.resize((48, 48), Image.LANCZOS)
        small_path = args.preview.replace(".png", "_48.png")
        small.save(small_path)
        print(f"preview {args.preview} / {small_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
