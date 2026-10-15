"""以刻度尺为公共基准，量化两图里每个二维码的纵向位置。

两张照片的拍摄距离不同（打印那张更远），所以像素坐标不能直接比。
但两张图里都有 100/200/300/400/500 刻度数字，它们物理位置相同 ——
把像素 y 换成"刻度值"这个无量纲坐标，两张图就能直接比了。
"""
import sys

import cv2
import numpy as np

IMAGES = {
    "SOURCE ": ("samples_user/source_calib.png", None),
    "PRINTED": ("samples_user/printed_from_it.png", None),
}
# 刻度数字在各自图里的 y 位置（人工/OCR 校准得到，见下）
RULER_Y = {
    "SOURCE ": {100: 84, 200: 173, 300: 260, 400: 349, 500: 436},
    "PRINTED": {100: 85, 200: 138, 300: 196, 400: 254, 500: 310},
}


def fit_ruler(ys: dict):
    """刻度值 -> 像素 y 的线性映射（最小二乘）"""
    v = np.array(sorted(ys))
    y = np.array([ys[k] for k in v], dtype=float)
    a, b = np.polyfit(v, y, 1)
    return lambda val: a * val + b, (v.min(), v.max())


def square_blobs(path, min_side, max_side):
    """找近似方形的块（二维码定位图案 + 单个汉字都会命中，靠尺寸与成组性筛）"""
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    _, bw = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    bw = cv2.morphologyEx(bw, cv2.MORPH_CLOSE, np.ones((2, 2), np.uint8))
    n, labels, stats, cents = cv2.connectedComponentsWithStats(bw, connectivity=8)
    out = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if not (min_side <= w <= max_side and min_side <= h <= max_side):
            continue
        if not (0.7 < w / h < 1.4):
            continue
        out.append((x, y, w, h, area))
    return sorted(out, key=lambda b: (b[1], b[0]))


def main() -> int:
    for tag, (path, _) in IMAGES.items():
        to_px, (vmin, vmax) = fit_ruler(RULER_Y[tag])
        px_of = lambda val: to_px(val)  # noqa: E731
        print(f"\n===== {tag} {path}  刻度拟合：v={vmin}->y={px_of(vmin):.0f}, "
              f"v={vmax}->y={px_of(vmax):.0f} =====")
        blobs = square_blobs(path, 6, 45)
        print(f"  方形块 {len(blobs)} 个（前 22 个）：")
        for (x, y, w, h, area) in blobs[:22]:
            print(f"    x={x:4d} y={y:4d} w={w:3d} h={h:3d}  "
                  f"刻度值 y={((y - to_px(0)) / (to_px(100) - to_px(0)) * 100):7.1f}  "
                  f"底={((y + h - to_px(0)) / (to_px(100) - to_px(0)) * 100):7.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
