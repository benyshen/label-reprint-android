"""量源图里那个"被中文压住的小二维码"的真实尺寸。

原理：二维码左上角的定位图案（finder pattern）是 7x7 模块的方环，只要它没被遮住，
就是一个孤立连通域，宽 = 7 × 模块尺寸。有了模块尺寸就能推出整码边长（版本1 = 21 模块）。
这样不依赖"检测器给多大的框"，能独立验证检测框是否被遮挡截短。
"""
import sys

import cv2
import numpy as np

SRC = "samples_user/source_calib.png"


def finder_sizes(bw, x0, y0, x1, y1, min_side=12):
    """在区域内找近似方形、内部有洞的连通域 = 定位图案"""
    sub = bw[y0:y1, x0:x1].copy()
    n, labels, stats, cents = cv2.connectedComponentsWithStats(sub, connectivity=8)
    out = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if w < min_side or h < min_side:
            continue
        ar = w / h
        if not (0.75 < ar < 1.33):
            continue
        # 方环 = 实心面积远小于外框面积
        fill = area / (w * h)
        # 洞里还有一块（内白心），用外框中心点是否为背景来判断
        cx, cy = int(cents[i][0]), int(cents[i][1])
        hole = sub[cy, cx] == 0 if 0 <= cy < sub.shape[0] and 0 <= cx < sub.shape[1] else False
        out.append((x + x0, y + y0, w, h, round(fill, 2), hole))
    return sorted(out, key=lambda b: (b[1], b[0]))


def main() -> int:
    img = cv2.imread(SRC, cv2.IMREAD_GRAYSCALE)
    _, bw = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    print(f"{SRC} {img.shape[1]}x{img.shape[0]}")

    # 大二维码（真值已知：px x=27..142 y=49..162，宽 115）用来标定方法是否可信
    print("\n[大 QR 区域] 应能找到一个 ~7 模块的方环：")
    for b in finder_sizes(bw, 20, 40, 150, 170):
        print(f"  x={b[0]:4d} y={b[1]:4d} w={b[2]:3d} h={b[3]:3d} fill={b[4]} 洞={b[5]}"
              f"  -> 模块={b[2] / 7:.2f}px, 整码21模块={b[2] / 7 * 21:.0f}px")

    print("\n[小 QR 区域] (x 15..150, y 180..290)：")
    for b in finder_sizes(bw, 15, 180, 150, 290):
        print(f"  x={b[0]:4d} y={b[1]:4d} w={b[2]:3d} h={b[3]:3d} fill={b[4]} 洞={b[5]}"
              f"  -> 模块={b[2] / 7:.2f}px, 整码21模块={b[2] / 7 * 21:.0f}px")
    return 0


if __name__ == "__main__":
    sys.exit(main())
