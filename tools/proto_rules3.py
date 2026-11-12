"""框线识别原型 v3（移植目标：ImageMeasure.detectRules）

实测结论（这张真机照片，四点矫正之后）：
- 印刷表格线还有 5~6° 倾斜：单行只剩几十像素短段，"某行最长墨迹段"必然漏检（原实现全漏）；
- 左右竖框倾斜不一致（−7.3° 与 +4.9°）→ 残余透视/剪切，任何"全局转正"都只能救一半；
- 纸箱背景是深色，纸边亮度只比纸低 5~10，靠亮度/侧边判据分不干净。

所以最终策略：不求逐像素还原畸变，只求**还原表格结构**——
1) 分别估横、竖倾角，把墨迹转正后膨胀+开运算抽出长条候选；
2) 两侧都是亮纸 + 沿线覆盖率 过滤（去掉纸边、投影、条码竖条）；
3) 横线按"跨度互相重叠"聚成一簇，取中位跨度定表格左右边界；
4) 竖线缺失的边界用簇的中位端点补齐（原标签的表格就是矩形，这一步把畸变抹平）；
5) 所有线按中线输出：横线 y = 线中心，跨度补齐到 [x_left, x_right]；竖线同理。
"""
import sys
import numpy as np
import cv2


def ink_binary(gray, block=31, c=12):
    ad = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                               cv2.THRESH_BINARY_INV, block, c)
    return cv2.morphologyEx(ad, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))


def skew(ink, near_horizontal=True, rng=10.0, scale=0.3):
    small = cv2.resize(ink, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    h, ww = small.shape
    best, bang = -1.0, 0.0
    for a in list(np.arange(-rng, rng + 1e-6, 0.5)) + list(np.arange(-0.4, 0.41, 0.1)):
        M = cv2.getRotationMatrix2D((ww / 2, h / 2), a, 1.0)
        r = cv2.warpAffine(small, M, (ww, h), flags=cv2.INTER_NEAREST)
        proj = r.sum(axis=1 if near_horizontal else 0, dtype=np.float64)
        score = float(np.percentile(proj, 99.5))
        if score > best:
            best, bang = score, a
    return float(bang)


def _rot(ink, ang):
    h, w = ink.shape
    M = cv2.getRotationMatrix2D((w / 2, h / 2), ang, 1.0)
    return cv2.warpAffine(ink, M, (w, h), flags=cv2.INTER_NEAREST), M


def _unrot(M, x, y):
    Mi = cv2.invertAffineTransform(M)
    p = Mi @ np.array([x, y, 1.0])
    return float(p[0]), float(p[1])


def extract(ink, ang, horiz, min_frac=0.14):
    h, w = ink.shape
    r, M = _rot(ink, ang)
    if horiz:
        k = max(3, int(h * 0.004) | 1)
        band = cv2.dilate(r, cv2.getStructuringElement(cv2.MORPH_RECT, (1, k)))
        band = cv2.morphologyEx(band, cv2.MORPH_OPEN,
                                cv2.getStructuringElement(cv2.MORPH_RECT,
                                                          (max(9, int(w * 0.18)), 1)))
    else:
        k = max(3, int(w * 0.004) | 1)
        band = cv2.dilate(r, cv2.getStructuringElement(cv2.MORPH_RECT, (k, 1)))
        band = cv2.morphologyEx(band, cv2.MORPH_OPEN,
                                cv2.getStructuringElement(cv2.MORPH_RECT,
                                                          (1, max(9, int(h * 0.15)))))
    n, _, stats, _ = cv2.connectedComponentsWithStats(band, 8)
    out = []
    for i in range(1, n):
        x, y, bw, bh, _ = stats[i]
        if horiz:
            if bw < min_frac * w or bh > 0.05 * h:
                continue
            yc = y + bh / 2.0
            p0 = _unrot(M, float(x), yc)
            p1 = _unrot(M, float(x + bw), yc)
            out.append(dict(kind="H", a=p0[0], b=p1[0], y=(p0[1] + p1[1]) / 2.0,
                            thick=max(2.5, bh * 0.5)))
        else:
            if bh < min_frac * h or bw > 0.05 * w:
                continue
            xc = x + bw / 2.0
            p0 = _unrot(M, xc, float(y))
            p1 = _unrot(M, xc, float(y + bh))
            out.append(dict(kind="V", a=p0[1], b=p1[1], x=(p0[0] + p1[0]) / 2.0,
                            thick=max(2.5, bw * 0.5)))
    return out


def bright_flanks(gray, item, gap=8, min_flank=150.0, core_dark=20.0):
    """判据：线的两侧都得是亮纸，而且**线芯明显比两侧暗**。

    要点：候选线的 y 是"整条斜线的平均 y"，直接取那一行当线芯会落在白纸上（斜线在
    局部并不在那个 y），所以先在 ±6 像素里找"沿线最暗的一行"，再以它为线芯量两侧。
    """
    h, w = gray.shape
    if item["kind"] == "H":
        y = int(round(item["y"])); x0 = max(0, int(it_a(item))); x1 = min(w, int(it_b(item)))
        if x1 - x0 < 8:
            return False
        best, bestY = 1e9, y
        for yy in range(max(1, y - 6), min(h - 2, y + 7)):
            m = float(gray[yy, x0:x1].mean())
            if m < best:
                best, bestY = m, yy
        t = gray[max(0, bestY - gap - 3):max(0, bestY - 2), x0:x1]
        b = gray[min(h, bestY + 3):min(h, bestY + gap + 4), x0:x1]
        core = gray[bestY:bestY + 1, x0:x1]
    else:
        x = int(round(item["x"])); y0 = max(0, int(it_a(item))); y1 = min(h, int(it_b(item)))
        if y1 - y0 < 8:
            return False
        best, bestX = 1e9, x
        for xx in range(max(1, x - 6), min(w - 2, x + 7)):
            m = float(gray[y0:y1, xx].mean())
            if m < best:
                best, bestX = m, xx
        t = gray[y0:y1, max(0, bestX - gap - 3):max(0, bestX - 2)]
        b = gray[y0:y1, min(w, bestX + 3):min(w, bestX + gap + 4)]
        core = gray[y0:y1, bestX:bestX + 1]
    if t.size == 0 or b.size == 0 or core.size == 0:
        return False
    tm, bm = float(t.mean()), float(b.mean())
    cm = float(core.mean())
    return min(tm, bm) > min_flank and cm < min(tm, bm) - core_dark


def it_a(item):
    return item["a"]


def it_b(item):
    return item["b"]


def build_grid(hs, vs, w, h, tol=0.06):
    """把候选横线聚成表格，补齐左右边框，输出规整的 2 竖 + N 横。"""
    hs = sorted(hs, key=lambda z: z["y"])
    if len(hs) < 2:
        return []
    # 以最长的横线为基准聚簇（跨度重叠 ≥60%）
    base = max(hs, key=lambda z: z["b"] - z["a"])
    bl = min(base["a"], base["b"])
    br = max(base["a"], base["b"])
    cluster = []
    for it in hs:
        l_, r_ = min(it["a"], it["b"]), max(it["a"], it["b"])
        ov = min(br, r_) - max(bl, l_)
        if ov >= 0.6 * min(br - bl, r_ - l_):
            cluster.append(it)
    if len(cluster) < 2:
        cluster = hs
    # 去掉挤在一起的（同一根线被拆成两条）
    cluster.sort(key=lambda z: z["y"])
    uniq = []
    for it in cluster:
        if uniq and abs(it["y"] - uniq[-1]["y"]) < tol * h:
            if it["b"] - it["a"] > uniq[-1]["b"] - uniq[-1]["a"]:
                uniq[-1] = it
        else:
            uniq.append(it)
    xl = float(np.median([min(it["a"], it["b"]) for it in uniq]))
    xr = float(np.median([max(it["a"], it["b"]) for it in uniq]))
    # 纸边/背景边缘比表格线"更宽"（贯穿整幅），按中位跨度剔掉；比中位窄的是被反光
    # 截断的真线，不能剔（后面会补齐到边框）。
    span_med = float(np.median([max(it["a"], it["b"]) - min(it["a"], it["b"]) for it in uniq]))
    uniq = [it for it in uniq
            if (max(it["a"], it["b"]) - min(it["a"], it["b"])) <= 1.15 * span_med]
    if len(uniq) < 2:
        return []
    yt = min(it["y"] for it in uniq)
    yb = max(it["y"] for it in uniq)
    out = []
    for it in uniq:
        out.append(dict(kind="H", a=xl, b=xr, y=it["y"], thick=it["thick"]))
    # 竖框：已有的取中位 x，缺失的用 xl/xr 补
    vsel = [v for v in vs if v["a"] <= yb + tol * h and v["b"] >= yt - tol * h]
    for want in (xl, xr):
        cand = [v for v in vsel if abs(v["x"] - want) <= 0.06 * w]
        if cand:
            v = max(cand, key=lambda z: z["b"] - z["a"])
            out.append(dict(kind="V", a=yt, b=yb, x=v["x"], thick=v["thick"]))
        else:
            out.append(dict(kind="V", a=yt, b=yb, x=want, thick=max(3.0, 0.004 * w)))
    return out


def run(path):
    gray = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    h, w = gray.shape
    ink = ink_binary(gray)
    ah, av = skew(ink, True), skew(ink, False)
    # 纸面亮度按分位数自适应：曝光偏暗的照片纸面只有 160 左右，写死 150 会把真线全滤掉
    lvl = 0.80 * float(np.percentile(gray, 90))
    hs = [i for i in extract(ink, ah, True) if bright_flanks(gray, i, min_flank=lvl)]
    vs = [i for i in extract(ink, av, False) if bright_flanks(gray, i, min_flank=lvl)]
    grid = build_grid(hs, vs, w, h)
    vis = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    for it in grid:
        if it["kind"] == "H":
            y = it["y"]
            cv2.rectangle(vis, (int(it["a"]), int(y - 2)), (int(it["b"]), int(y + 2)), (0, 0, 255), 2)
        else:
            x = it["x"]
            cv2.rectangle(vis, (int(x - 2), int(it["a"])), (int(x + 2), int(it["b"])), (255, 0, 0), 2)
    cv2.putText(vis, f"aH={ah:.1f} aV={av:.1f} H={len(hs)} V={len(vs)} grid={len(grid)}",
                (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 200, 0), 2)
    dst = path.rsplit(".", 1)[0] + "_lines.png"
    cv2.imwrite(dst, vis)
    for it in grid:
        if it["kind"] == "H":
            print(f"H x={it['a']:.0f}..{it['b']:.0f} y={it['y']:.0f} t={it['thick']:.1f}")
        else:
            print(f"V x={it['x']:.0f} y={it['a']:.0f}..{it['b']:.0f} t={it['thick']:.1f}")
    print("overlay:", dst)


if __name__ == "__main__":
    run(sys.argv[1])
