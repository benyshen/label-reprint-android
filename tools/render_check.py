"""Render generated ZPL with the Labelary service and put it next to the source photo,
so "does the ZPL reproduce the label" is a picture, not an opinion.

Usage: python render_check.py out/label_sample_1.zpl samples/label_sample_1.png [--size 2.36x1.57]
"""
import argparse, io, os, sys, urllib.request

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zpl_geometry as G

LABELARY = "http://api.labelary.com/v1/printers/{dpmm}dpmm/labels/{size}/0/"


def render(zpl, size_mm=(60, 40), dpmm=8):
    size = f"{size_mm[0] / 25.4:.2f}x{size_mm[1] / 25.4:.2f}"
    req = urllib.request.Request(LABELARY.format(dpmm=dpmm, size=size),
                                 data=zpl.encode("utf-8"),
                                 headers={"Accept": "image/png"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return cv2.imdecode(np.frombuffer(r.read(), np.uint8), cv2.IMREAD_COLOR)


def side_by_side(src_bgr, rendered):
    h = 320
    a = cv2.resize(src_bgr, (int(src_bgr.shape[1] * h / src_bgr.shape[0]), h), interpolation=cv2.INTER_NEAREST)
    b = cv2.resize(rendered, (int(rendered.shape[1] * h / rendered.shape[0]), h), interpolation=cv2.INTER_NEAREST)
    sep = np.full((h, 12, 3), 128, np.uint8)
    return np.hstack([a, sep, b])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zpl")
    ap.add_argument("source", nargs="?", default=None)
    ap.add_argument("--template", type=int, default=G.DEFAULT_TEMPLATE)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    zpl = open(args.zpl, encoding="utf-8").read()
    t = G.TEMPLATES[args.template]
    img = render(zpl, (t["w_mm"], t["h_mm"]), 8)
    outdir = args.out or os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "shots")
    os.makedirs(outdir, exist_ok=True)
    stem = os.path.splitext(os.path.basename(args.zpl))[0]
    render_path = os.path.join(outdir, stem + ".render.png")
    cv2.imwrite(render_path, img)
    print("rendered ->", render_path, img.shape)
    if args.source:
        src = cv2.imread(args.source)
        cmp_path = os.path.join(outdir, stem + ".compare.png")
        cv2.imwrite(cmp_path, side_by_side(src, img))
        print("compare  ->", cmp_path, "(left = source photo, right = ZPL render)")


if __name__ == "__main__":
    main()
