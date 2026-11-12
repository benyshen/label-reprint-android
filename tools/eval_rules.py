"""现场 14 张标签照片 → 纸面四点矫正 → 框线识别 → 叠加图 + 汇总表（PC 侧验证）。

用法: python tools/eval_rules.py [目录]
输出: shots/eval/<name>_lines.png 和 shots/eval_sheet.png
"""
import glob
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
import proto_rules3 as R


def paper_quad(gray):
    """找白纸的四个角：亮度阈值 + 最大连通域 + 最小外接矩形（标签本身就是矩形）。

    先前用 approxPolyDP 取四边形，遇到不规则亮块会给出重复点（退化四边形），
    透视矩阵直接算出空白图。minAreaRect 不会退化，再按 tl/tr/br/bl 排序。
    """
    h, w = gray.shape
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    thr = max(float(np.percentile(blur, 72)), 110.0)
    _, th = cv2.threshold(blur, thr, 255, cv2.THRESH_BINARY)
    th = cv2.morphologyEx(th, cv2.MORPH_CLOSE, np.ones((11, 11), np.uint8))
    th = cv2.morphologyEx(th, cv2.MORPH_OPEN, np.ones((11, 11), np.uint8))
    cnts, _ = cv2.findContours(th, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None
    c = max(cnts, key=cv2.contourArea)
    if cv2.contourArea(c) < 0.01 * w * h:
        return None
    rect = cv2.minAreaRect(c)
    (cx, cy), (rw, rh), ang = rect
    if rw < 40 or rh < 40:
        return None
    quad = cv2.boxPoints(rect).astype(np.float32)
    # 统一成 tl,tr,br,bl（先按 y 分上下两对，再各自按 x 排序）
    idx = np.argsort(quad[:, 1])
    top, bot = quad[idx[:2]], quad[idx[2:]]
    top = top[np.argsort(top[:, 0])]
    bot = bot[np.argsort(bot[:, 0])]
    q = np.array([top[0], top[1], bot[1], bot[0]], np.float32)
    if np.unique(q, axis=0).shape[0] < 4:
        return None
    return q


def warp(gray, quad, long_side=1600):
    (tl, tr, br, bl) = quad
    wa = np.linalg.norm(br - bl); wb = np.linalg.norm(tr - tl)
    ha = np.linalg.norm(tr - br); hb = np.linalg.norm(tl - bl)
    W = int(max(wa, wb)); H = int(max(ha, hb))
    if W < 20 or H < 20:
        return None
    sc = long_side / float(max(W, H))
    W, H = max(16, int(W * sc)), max(16, int(H * sc))
    dst = np.array([[0, 0], [W - 1, 0], [W - 1, H - 1], [0, H - 1]], np.float32)
    M = cv2.getPerspectiveTransform(quad, dst)
    return cv2.warpPerspective(gray, M, (W, H), flags=cv2.INTER_AREA)


def eval_one(path, outdir):
    gray = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    quad = paper_quad(gray)
    if quad is None:
        return os.path.basename(path), None, "纸面没找到"
    work = warp(gray, quad)
    if work is None:
        return os.path.basename(path), None, "校正失败"
    ink = R.ink_binary(work)
    ah, av = R.skew(ink, True), R.skew(ink, False)
    lvl = 0.80 * float(np.percentile(work, 90))
    hs = [i for i in R.extract(ink, ah, True) if R.bright_flanks(work, i, min_flank=lvl)]
    vs = [i for i in R.extract(ink, av, False) if R.bright_flanks(work, i, min_flank=lvl)]
    grid = R.build_grid(hs, vs, work.shape[1], work.shape[0])
    vis = cv2.cvtColor(work, cv2.COLOR_GRAY2BGR)
    for it in grid:
        if it["kind"] == "H":
            y = it["y"]
            cv2.rectangle(vis, (int(it["a"]), int(y - 2)), (int(it["b"]), int(y + 2)), (0, 0, 255), 2)
        else:
            x = it["x"]
            cv2.rectangle(vis, (int(x - 2), int(it["a"])), (int(x + 2), int(it["b"])), (255, 0, 0), 2)
    nh = sum(1 for i in grid if i["kind"] == "H")
    nv = sum(1 for i in grid if i["kind"] == "V")
    cv2.putText(vis, f"H{nh} V{nv} aH{ah:.0f} aV{av:.0f}", (8, 28),
                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 220, 0), 2)
    name = os.path.splitext(os.path.basename(path))[0]
    cv2.imwrite(os.path.join(outdir, name + "_lines.png"), vis)
    cv2.imwrite(os.path.join(outdir, name + "_work.png"), work)
    return name, (nh, nv), None


def main(folder):
    outdir = os.path.join("shots", "eval")
    os.makedirs(outdir, exist_ok=True)
    rows = []
    for p in sorted(glob.glob(os.path.join(folder, "*.jpg"))):
        name, res, err = eval_one(p, outdir)
        rows.append((name, res, err))
        print(f"{name}: {res if res else err}")
    # 接触表
    pics = []
    for name, res, err in rows:
        f = os.path.join(outdir, name + "_lines.png")
        if os.path.exists(f):
            im = cv2.imread(f)
            h, w = im.shape[:2]
            s = 440.0 / w
            pics.append((name, cv2.resize(im, (440, int(h * s)))))
    if pics:
        th = max(p[1].shape[0] for p in pics) + 22
        cols = 4
        r = (len(pics) + cols - 1) // cols
        sheet = np.full((r * th, cols * 440, 3), 30, np.uint8)
        for i, (name, im) in enumerate(pics):
            rr, cc = divmod(i, cols)
            sheet[rr * th + 22:rr * th + 22 + im.shape[0], cc * 440:cc * 440 + im.shape[1]] = im
            cv2.putText(sheet, name[-6:], (cc * 440 + 4, rr * th + 16),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
        cv2.imwrite("shots/eval_sheet.png", sheet)
        print("sheet: shots/eval_sheet.png")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "samples_field")
