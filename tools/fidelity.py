"""Score a generated label against its source: ink-mask IoU + per-element position error.

The point of a number here: "looks about right" cannot tell a 5-dot drift from a 20-dot one,
and element drift is exactly what ruins a reprinted label.

Usage: python fidelity.py out/label_sample_1.zpl samples/label_sample_1.png --template 1
"""
import argparse, os, sys, urllib.request

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zpl_geometry as G
from render_check import render, side_by_side


def ink_mask(img, shape):
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    g = cv2.resize(g, (shape[1], shape[0]), interpolation=cv2.INTER_AREA)
    _, m = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    return m > 0


def blocks(mask):
    """row-bands of ink -> [(x0,y0,x1,y1)] (cheap element-ish segmentation)."""
    rows = mask.any(1)
    out, start = [], None
    for y, r in enumerate(rows):
        if r and start is None:
            start = y
        elif not r and start is not None:
            if y - start > 3:
                sub = mask[start:y]
                cols = np.where(sub.any(0))[0]
                out.append((int(cols.min()), start, int(cols.max()), y))
            start = None
    if start is not None:
        sub = mask[start:]
        cols = np.where(sub.any(0))[0]
        out.append((int(cols.min()), start, int(cols.max()), len(rows)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zpl")
    ap.add_argument("source")
    ap.add_argument("--template", type=int, default=G.DEFAULT_TEMPLATE)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    zpl = open(args.zpl, encoding="utf-8").read()
    t = G.TEMPLATES[args.template]
    rendered = render(zpl, (t["w_mm"], t["h_mm"]), 8)
    src = cv2.imread(args.source)
    shape = rendered.shape[:2]

    a = ink_mask(src, shape)
    b = ink_mask(rendered, shape)
    inter = np.logical_and(a, b).sum()
    union = np.logical_or(a, b).sum()
    iou = inter / max(1, union)

    print(f"label {args.zpl} vs {os.path.basename(args.source)}  (render {shape[1]}x{shape[0]})")
    print(f"  ink IoU           = {iou:.3f}   (source ink {a.sum()} px, render ink {b.sum()} px)")
    sa, sb = blocks(a), blocks(b)
    print(f"  ink rows          = {len(sa)} (source) vs {len(sb)} (render)")
    for i in range(min(len(sa), len(sb))):
        d = [abs(x - y) for x, y in zip(sa[i], sb[i])]
        # positions in render px; 8dpmm => 1 px = 1 dot here
        print(f"    band {i}: dx0={d[0]:3d} dy0={d[1]:3d} dx1={d[2]:3d} dy1={d[3]:3d} dots"
              f"   src={sa[i]} render={sb[i]}")
    if args.out:
        cv2.imwrite(args.out, side_by_side(src if src.shape[0] != shape[0] else src, rendered))


if __name__ == "__main__":
    main()
