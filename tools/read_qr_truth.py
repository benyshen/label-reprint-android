"""用 zxing-cpp 直接读两张照片里的二维码，拿到"内容 + 真实像素位置"。

为什么不用连通域猜：两张图里二维码数量、大小都不同，靠形状分类会混入水印/手/文字。
zxing 能直接给出每个符号的包围盒和内容，是这里唯一可信的真值来源。
"""
import sys

import zxingcpp

FILES = {
    "SOURCE ": "samples_user/source_calib.png",
    "PRINTED": "samples_user/printed_from_it.png",
}


def report(path: str) -> None:
    from PIL import Image
    im = Image.open(path).convert("RGB")
    res = zxingcpp.read_barcodes(im)
    print(f"\n===== {path} {im.size} 共 {len(res)} 个符号 =====")
    for r in res:
        p = r.position
        xs = [p.top_left.x, p.top_right.x, p.bottom_right.x, p.bottom_left.x]
        ys = [p.top_left.y, p.top_right.y, p.bottom_right.y, p.bottom_left.y]
        print(f"  {str(r.format):16s} '{r.text}'  "
              f"x={min(xs):4.0f}..{max(xs):4.0f}  y={min(ys):4.0f}..{max(ys):4.0f}  "
              f"高={max(ys) - min(ys):3.0f} 宽={max(xs) - min(xs):3.0f}")


def main() -> int:
    for path in FILES.values():
        report(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
