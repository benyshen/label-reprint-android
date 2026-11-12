"""框线识别原型 v4：Hough 累加 + 线段提取 + 垂直剖面校验（移植目标 ImageMeasure.detectRules）

v3 的教训：形态学抽长条 + "投影最尖角度"在真机照片上会碎/会跑偏——
  · 斜 2~3° 的横线在单行里只剩 20%，膨胀 7px 也接不上，一条线会碎成 3~4 段；
  · 投影峰值被纸面边缘（更黑、更直、更长）抢走，估出的角度是 0.2°，真线却是 3°；
  · "两侧亮度差"判据在纸面明暗渐变下必然误杀真线。

v4 做法：
  1) 墨迹二值（自适应阈值）；
  2) 对近水平角度做 Hough 累加（rho = y − tanθ·x），峰就是一条线，天然抗倾斜与断裂；
  3) 每个峰按 rho 收像素求线段端点（5%/95% 分位，抗噪点）；
  4) 校验：沿线段垂直方向采样剖面——线芯要暗、两侧要亮（用中位数，抗个别坏点）；
  5) 共线合并 → 表格聚簇 → 补左右边框 → 输出。
"""
import sys

import numpy as np
import cv2


def ink_binary(gray, block=41, c=10):
    ad = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                               cv2.THRESH_BINARY_INV, block, c)
    return cv2.morphologyEx(ad, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))


def hough(ink, horizontal=True, rng=10.0, step=0.5, scale=2):
    """累加 (theta, rho)，返回峰。horizontal=True 找近水平线。

    用 np.bincount 代替 np.add.at：后者在千万级像素上慢到没法迭代（14 张图要几分钟）。
    """
    small = ink[::scale, ::scale]
    sh, sw = small.shape
    ys, xs = np.nonzero(small)
    if ys.size == 0:
        return []
    thetas = np.arange(-rng, rng + 1e-9, step)
    base = (ys if horizontal else xs).astype(np.float32)
    other = np.abs(xs if horizontal else ys).astype(np.float32)
    rho_max = int(base.max() + np.tan(np.radians(rng)) * other.max()) + 4
    out = []
    thr = 0.16 * (sw if horizontal else sh)
    for th in thetas:
        rho = base - np.tan(np.radians(th)) * (xs if horizontal else ys)
        idx = np.clip(np.rint(rho).astype(np.int32), 0, rho_max)
        row = np.bincount(idx, minlength=rho_max + 1)
        # 局部极大且过阈值（按 3 像素邻域比较）
        cand = np.where(row >= thr)[0]
        if cand.size == 0:
            continue
        padded = row
        for r in cand:
            v = padded[r]
            lo = padded[max(0, r - 2):r + 3]
            if v >= lo.max():
                out.append((float(th), float(r) * scale, int(v)))
    return out


def segments(ink, gray, horizontal=True, rng=10.0):
    """Hough 峰 → 线段（含 theta、rho、端点、厚度、校验结果）"""
    h, w = ink.shape
    peaks = hough(ink, horizontal, rng=rng)
    if not peaks:
        return []
    ys, xs = np.nonzero(ink)
    res = []
    for th, rho, cnt in peaks:
        t = np.tan(np.radians(th))
        if horizontal:
            r = ys - t * xs
        else:
            r = xs - t * ys
        sel = np.abs(r - rho) <= 2.0
        if sel.sum() < 0.2 * (w if horizontal else h):
            continue
        a_all = np.sort(xs[sel] if horizontal else ys[sel])
        # 取最长"连续段"（允许 2% 宽度的空档）：孤立墨点（反光、条码、纸边）会把
        # 3/97 分位的跨度拉宽 200 多像素，实测左边框被推到纸边外。
        gapmax = max(6, int(0.02 * (w if horizontal else h)))
        best_lo, best_hi = 0, -1
        start = 0
        for i in range(1, len(a_all) + 1):
            if i == len(a_all) or a_all[i] - a_all[i - 1] > gapmax:
                if a_all[i - 1] - a_all[start] > best_hi - best_lo:
                    best_lo, best_hi = int(a_all[start]), int(a_all[i - 1])
                start = i
        lo, hi = float(best_lo), float(best_hi)
        if hi - lo < 0.20 * (w if horizontal else h):
            continue
        res.append(dict(kind="H" if horizontal else "V", th=th, rho=rho,
                        a=float(lo), b=float(hi), cnt=cnt))
    # 共线合并（同 theta、rho 相差 ≤6px、区间重叠）
    res.sort(key=lambda z: (round(z["th"], 1), z["rho"]))
    merged = []
    for it in res:
        hit = False
        for k in merged:
            if abs(k["th"] - it["th"]) <= 1.2 and abs(k["rho"] - it["rho"]) <= 8:
                ov = min(k["b"], it["b"]) - max(k["a"], it["a"])
                if ov > -0.15 * (it["b"] - it["a"]):
                    k["a"] = min(k["a"], it["a"]); k["b"] = max(k["b"], it["b"])
                    hit = True
                    break
        if not hit:
            merged.append(it)
    return merged


def verify(gray, it, samples=41):
    """沿线段取样本点，检查"线芯暗、两侧亮"（用中位数，抗坏点）。"""
    h, w = gray.shape
    th = it["th"]
    t = np.tan(np.radians(th))
    cs, cores, sides = [], [], []
    for i in range(samples):
        f = i / (samples - 1.0)
        a = it["a"] + f * (it["b"] - it["a"])
        if it["kind"] == "H":
            x = a; y = it["rho"] + t * x
            if not (2 <= y < h - 12) or not (0 <= x < w):
                continue
            yi = int(round(y))
            cores.append(gray[yi, int(x)])
            sides.append(min(gray[yi - 7, int(x)], gray[yi + 7, int(x)]))
        else:
            y = a; x = it["rho"] + t * y
            if not (2 <= x < w - 12) or not (0 <= y < h):
                continue
            xi = int(round(x))
            cores.append(gray[int(y), xi])
            sides.append(min(gray[int(y), xi - 7], gray[int(y), xi + 7]))
    if len(cores) < 8:
        return None
    core = float(np.median(cores))
    side = float(np.median(sides))
    cover = len(cores) / samples
    # 门槛按本图纸面亮度自适应：曝光暗的现场照片纸面只有 150~170
    lvl = 0.86 * float(np.median(gray[gray > np.percentile(gray, 60)]))
    ok = side > lvl and core < side - 12 and cover > 0.5
    return dict(ok=ok, core=core, side=side, cover=cover, lvl=lvl)


def build_grid(hs, vs, w, h, tol=0.06):
    if len(hs) < 2:
        return []
    hs = sorted(hs, key=lambda z: z["ry"])
    base = max(hs, key=lambda z: z["b"] - z["a"])
    bl, br = base["a"], base["b"]
    cluster = []
    for it in hs:
        ov = min(br, it["b"]) - max(bl, it["a"])
        if ov >= 0.5 * min(br - bl, it["b"] - it["a"]):
            cluster.append(it)
    if len(cluster) < 2:
        cluster = hs
    cluster.sort(key=lambda z: z["ry"])
    uniq = []
    for it in cluster:
        if uniq and abs(it["ry"] - uniq[-1]["ry"]) < tol * h:
            if it["b"] - it["a"] > uniq[-1]["b"] - uniq[-1]["a"]:
                uniq[-1] = it
        else:
            uniq.append(it)
    span_med = float(np.median([it["b"] - it["a"] for it in uniq]))
    uniq = [it for it in uniq if (it["b"] - it["a"]) <= 1.15 * span_med]
    # 只剔掉贴着图纸上下边缘的那一条（纸边/投影），不能用"离群间距"判据：
    # 条码那格本来就高，底边框与上一条的间隔天然超过整簇的 45%，会被误删。
    uniq = [it for it in uniq if 0.02 * h < it["ry"] < 0.97 * h]
    if len(uniq) < 2:
        return []
    if len(uniq) < 2:
        return []
    xl = float(np.median([it["a"] for it in uniq]))
    xr = float(np.median([it["b"] for it in uniq]))
    yt = min(it["ry"] for it in uniq)
    yb = max(it["ry"] for it in uniq)
    out = [dict(kind="H", a=xl, b=xr, pos=it["ry"], thick=3.0) for it in uniq]
    for want in (xl, xr):
        cand = [v for v in vs if abs(v["rx"] - want) <= 0.04 * w and v["b"] - v["a"] > 0.3 * h]
        if cand:
            v = max(cand, key=lambda z: z["b"] - z["a"])
            out.append(dict(kind="V", a=yt, b=yb, pos=v["rx"], thick=3.0))
        else:
            out.append(dict(kind="V", a=yt, b=yb, pos=want, thick=3.0))
    return out


def detect(gray, debug=False):
    h, w = gray.shape
    ink = ink_binary(gray)
    hs, vs = [], []
    for it in segments(ink, gray, True):
        v = verify(gray, it)
        it["ry"] = it["rho"] + np.tan(np.radians(it["th"])) * ((it["a"] + it["b"]) / 2)
        if v and v["ok"]:
            it["v"] = v
            hs.append(it)
        elif debug:
            print("  drop H th=%.1f rho=%.0f %s" % (it["th"], it["rho"], v))
    for it in segments(ink, gray, False):
        v = verify(gray, it)
        it["rx"] = it["rho"] + np.tan(np.radians(it["th"])) * ((it["a"] + it["b"]) / 2)
        if v and v["ok"]:
            it["v"] = v
            vs.append(it)
        elif debug:
            print("  drop V th=%.1f rho=%.0f %s" % (it["th"], it["rho"], v))
    return build_grid(hs, vs, w, h), hs, vs


def main(path, debug=False):
    gray = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    grid, hs, vs = detect(gray, debug)
    vis = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    for it in grid:
        if it["kind"] == "H":
            cv2.rectangle(vis, (int(it["a"]), int(it["pos"] - 2)), (int(it["b"]), int(it["pos"] + 2)), (0, 0, 255), 2)
        else:
            cv2.rectangle(vis, (int(it["pos"] - 2), int(it["a"])), (int(it["pos"] + 2), int(it["b"])), (255, 0, 0), 2)
    print(path, "H", len(hs), "V", len(vs), "grid", len(grid))
    for it in grid:
        print("  ", it["kind"], "pos=%.0f a=%.0f b=%.0f" % (it["pos"], it["a"], it["b"]))
    dst = path.rsplit(".", 1)[0] + "_v4.png"
    cv2.imwrite(dst, vis)
    return dst


if __name__ == "__main__":
    main(sys.argv[1], len(sys.argv) > 2)
