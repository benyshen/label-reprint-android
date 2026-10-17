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

# 主体尺寸（dp）。官方的 72 安全区是最保守值（"任何遮罩都不切"），实际留黑太多：
# 用户反馈"画面偏上、黑区大"。而实测这台 TC73 的启动器是**圆角方形**遮罩（圆角约占 20~25%），
# 84dp 的四个角仍落在遮罩内（圆角 25% 时角点到弧心距离 21 < 半径 27）。
# 代价：若某个启动器用**正圆**遮罩，相机的左上角 / 袋子的右下角会被啃掉约 3~5dp
# （那两处本身是圆角，视觉上看不出来）。详见 docs/V1.5-版本记录.md。
SAFE_DP = 84

# 与 ZebraPalette 一致的黑底：图标背景和 App 主题同一套色
BG = (0, 0, 0)

# 判"有内容"的 alpha 门槛（0~255）。源图边缘有极淡的残影（alpha 16~31），
# 拿 getbbox() 会把裁剪框撑到整张画布，主体就被挤到一角 —— 这正是上一版"画面偏上"的原因。
ALPHA_BBOX_CUT = 32


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
    """按**真正有内容**的范围裁剪后等比缩到 box×box 内。

    裁剪不能用整个通道的 getbbox()：源图四周有 alpha 16~31 的淡残影，会把框撑到整张画布，
    主体就被挤到上方、四周留一大片黑（上一版的问题）。
    """
    mask = im.getchannel("A").point(lambda v: 255 if v >= ALPHA_BBOX_CUT else 0)
    bbox = mask.getbbox()
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
        # 模拟三种 launcher 遮罩：圆形 / squircle（主流）/ 圆角方，看主体有没有被切
        def masked(kind: str, size: int) -> Image.Image:
            m = Image.new("L", (master, master), 0)
            d = ImageDraw.Draw(m)
            if kind == "circle":
                d.ellipse([0, 0, master - 1, master - 1], fill=255)
            else:
                radius = int(master * (0.30 if kind == "squircle" else 0.12))
                d.rounded_rectangle([0, 0, master - 1, master - 1], radius=radius, fill=255)
            shot = Image.new("RGBA", (master, master), BG + (255,))
            shot.alpha_composite(fg_master)
            out = Image.new("RGBA", (master, master), (0, 0, 0, 0))
            out.paste(shot, (0, 0), m)
            return out.resize((size, size), Image.LANCZOS)

        kinds = ("circle", "squircle", "square")
        cell, pad = 220, 20
        sheet = Image.new("RGB", (pad + (cell + pad) * 3, pad * 2 + cell), (245, 245, 245))
        for i, kind in enumerate(kinds):
            tile = Image.new("RGBA", (cell, cell), (245, 245, 245, 255))
            tile.alpha_composite(masked(kind, cell), (0, 0))
            sheet.paste(tile, (pad + i * (cell + pad), pad))
        os.makedirs(os.path.dirname(os.path.abspath(args.preview)), exist_ok=True)
        sheet.save(args.preview)

        # 桌面真实大小 48px（三档并排），检查缩小后还认不认得出
        small = Image.new("RGBA", (48 * 3 + 8 * 4, 48 + 16), (245, 245, 245, 255))
        for i, kind in enumerate(kinds):
            small.alpha_composite(masked(kind, 48), (8 + i * 56, 8))
        small_path = args.preview.replace(".png", "_48.png")
        small.save(small_path)
        print(f"preview {args.preview} / {small_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
