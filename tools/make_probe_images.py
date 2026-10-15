"""生成"实拍级"探测图：模拟真机 12MP 照片里的一张标签。

为什么要造：手上的 label_sample_*.png 是 2.5px/模块的合成图，DL 解不出一维码。
分不清是"模型不支持一维码"还是"这张图信息量不够"。这里固定画布 2400x1500
（≈3.6MP，接近真机照片裁掉背景后的量级），只改条码的绝对像素宽度，再加
轻微高斯模糊 + 传感器噪声模拟镜头与对焦，得到一组单调递变的样例。

结果读数：每个文件在 tools/probe_images/ 里，文件名带上 px/模块 便于对表。
"""
import os
import sys

import numpy as np
import zxingcpp
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "probe_images")
CANVAS_W, CANVAS_H = 2400, 1500
TEXT = "ABC123"


def code128_image(text: str, target_w: int) -> Image.Image:
    """生成条码并缩放到 target_w 像素宽（NEAREST 保硬边，先放大再缩避免模糊）"""
    base = zxingcpp.write_barcode(zxingcpp.BarcodeFormat.Code128, text, height=200)
    if not isinstance(base, Image.Image):  # 某些版本回 numpy
        base = Image.fromarray(np.array(base))
    base = base.convert("L")
    # 先放大到远大于目标，再用 NEAREST 缩，保证条边缘干净
    scale = max(1, int(np.ceil(target_w * 4 / base.width)))
    big = base.resize((base.width * scale, base.height * scale), Image.NEAREST)
    return big.resize((target_w, max(1, int(big.height * target_w / big.width))), Image.NEAREST)


def compose(barcode: Image.Image, blur: float, noise: float, bg: int = 245) -> Image.Image:
    canvas = Image.new("L", (CANVAS_W, CANVAS_H), bg)
    d = ImageDraw.Draw(canvas)
    x0 = (CANVAS_W - barcode.width) // 2
    y0 = 420
    canvas.paste(barcode, (x0, y0))
    # 人眼可读串（模拟标签上的 HRI）
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 64)
    except OSError:
        font = ImageFont.load_default()
    tw = d.textlength(TEXT, font=font)
    d.text((CANVAS_W / 2 - tw / 2, y0 + barcode.height + 24), TEXT, fill=0, font=font)
    if blur > 0:
        canvas = canvas.filter(ImageFilter.GaussianBlur(blur))
    if noise > 0:
        a = np.asarray(canvas).astype(np.float32)
        a += np.random.default_rng(7).normal(0, noise, a.shape)
        canvas = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    return canvas


def module_px(target_w: int) -> float:
    """Code128 编 ABC123 的模块数（含起始/校验/终止，实测 101 模块）"""
    return target_w / 101.0


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    # 每档：(条码像素宽, 模糊半径, 噪声 sigma)。模糊 0.8 ≈ 手持 1/30s 的轻微失焦
    cases = [
        (1010, 0.0, 0.0),   # 10.0 px/模块 理想
        (1010, 0.8, 3.0),   # 10.0 px/模块 实拍感
        (606, 0.8, 3.0),    #  6.0 px/模块 实拍感（本次重点）
        (404, 0.8, 3.0),    #  4.0 px/模块 实拍感
        (253, 0.8, 3.0),    #  2.5 px/模块 实拍感（= 现有样图档位）
        (606, 1.6, 6.0),    #  6.0 px/模块 + 重模糊，看鲁棒边界
    ]
    for w, blur, noise in cases:
        bmp = code128_image(TEXT, w)
        img = compose(bmp, blur, noise)
        tag = f"photo_m{w / 101.0:.1f}px_b{blur}_n{noise}".replace(".", "p")
        path = os.path.join(OUT, tag + ".png")
        img.save(path)
        # 回读验证：这档图我们自己能不能解（zxing-cpp 是传统算法，作下界参照）
        res = zxingcpp.read_barcodes(img)
        got = res[0].text if res else "NONE"
        print(f"{os.path.basename(path):40s} {img.size} zxing={got}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
