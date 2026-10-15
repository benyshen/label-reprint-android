"""量化对比：源标签照片 vs 本 App 打印成品照片。

思路：两张照片里都有右侧的刻度尺（长刻度带 100~500 数字）。刻度尺在物理标签上的位置是固定的，
所以用刻度尺做公共坐标基准，把两张图对齐后，就能直接读出每个要素（尤其小二维码）的纵向偏差。

为了不依赖人工读数，这里先做形态学分析：阈值 -> 连通域 -> 按面积/长宽比分类，
把二维码（密密麻麻的小块）、文字、刻度尺分开，再打印各自的包围盒。
"""
import sys

import cv2
import numpy as np

FILES = {
    "SOURCE ": "samples_user/source_calib.png",
    "PRINTED": "samples_user/printed_from_it.png",
}


def components(path: str):
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise SystemExit(f"读不到 {path}")
    # Otsu 自适应阈值：两张图的曝光/背景差别大，固定阈值不可比
    _, bw = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    # 去噪：孤立小点（纸张纹理/水印）
    bw = cv2.morphologyEx(bw, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    n, labels, stats, cents = cv2.connectedComponentsWithStats(bw, connectivity=8)
    out = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area < 12:
            continue
        out.append((x, y, w, h, area, cents[i][0], cents[i][1]))
    return img.shape, out, bw


def dilate_groups(bw, k=9):
    """把紧邻的小块并成"要素块"（二维码是很多小块、文字是很多笔画）"""
    d = cv2.dilate(bw, np.ones((k, k), np.uint8), iterations=2)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(d, connectivity=8)
    boxes = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area < 400 or w < 8 or h < 8:
            continue
        boxes.append((x, y, w, h, area))
    # 用原始墨迹重新收紧每个块（膨胀过会让边界虚大）
    tight = []
    for (x, y, w, h, _a) in boxes:
        sub = bw[y:y + h, x:x + w]
        ys, xs = np.nonzero(sub)
        if len(xs) == 0:
            continue
        tight.append((x + xs.min(), y + ys.min(),
                      xs.max() - xs.min() + 1, ys.max() - ys.min() + 1))
    return sorted(tight, key=lambda b: b[1])


def main() -> int:
    for tag, path in FILES.items():
        shape, comps, bw = components(path)
        print(f"\n===== {tag} {path} {shape[1]}x{shape[0]} 墨迹块 {len(comps)} =====")
        groups = dilate_groups(bw)
        for (x, y, w, h) in groups:
            # 长宽比 > 6 且很窄 -> 刻度尺竖线/长刻度；又小又方 -> 单个文字/数字
            kind = "?"
            ar = w / h
            if ar > 5 and h > 40:
                kind = "RULER/竖线"
            elif w > 40 and h > 40 and 0.6 < ar < 1.7:
                kind = "QR 或方块图"
            elif h <= 26 and w <= 40:
                kind = "小字/刻度"
            elif ar > 1.5:
                kind = "文字行"
            print(f"  {kind:10s} x={x:4d} y={y:4d} w={w:4d} h={h:4d}  (底={y + h})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
