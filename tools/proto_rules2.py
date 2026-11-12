"""框线识别原型 v2（PC 调参 → 移植 ImageMeasure.detectRules）

结论(实测)：
- 标签照片即使做过四点矫正，印刷表格线仍有 3~6° 倾斜：单行里只剩几十像素的短段，
  按"某一行最长墨迹段"找线必然全部漏检 —— 原实现就是这么错的；
- 纸箱背景是深色，任何"长暗行"判据都会把纸边/背景当线；
- 横线和竖线的残差倾斜不一样（四点矫正后仍可能差 1~2°），必须分别估角。

做法：
1) 墨迹 = 局部自适应阈值（不依赖纸面分割）；
2) 横线：用 HoughLinesP 的长直段按长度加权估横线倾角 → 转正 → 竖向膨胀 1×k 桥接 →
   横向开运算只留长条 → 得到 (x0,x1,y中线)；
3) 竖线：同法单独估竖线倾角；
4) 判据：线两侧都得是亮纸（真印刷线在白纸上；纸边/阴影只有一侧亮）+ 沿线覆盖率。
5) 输出时把线的中线映射回原图坐标，厚度按实际线宽给（3~4px），不要用旋转后的 bbox
   —— 那会把一条横线变成一个 100px 高的块。
"""
import sys
import numpy as np
import cv2


def ink_binary(gray, block=31, c=12):
    ad = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                               cv2.THRESH_BINARY_INV, block, c)
    return cv2.morphologyEx(ad, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))


def _hough(ink, w, near_horizontal=True, min_len_frac=0.18):
    sk = cv2.morphologyEx(ink, cv2.MORPH_OPEN,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))
    segs = cv2.HoughLinesP(sk, 1, np.pi / 360, threshold=70,
                           minLineLength=int(min_len_frac * w), maxLineGap=12)
    if segs is None:
        return []
    out = []
    for x1, y1, x2, y2 in segs[:, 0]:
        a = float(np.degrees(np.arctan2(y2 - y1, x2 - x1)))
        ln = float(np.hypot(x2 - x1, y2 - y1))
        if near_horizontal and abs(a) < 12:
            out.append((a, ln))
        elif (not near_horizontal) and abs(abs(a) - 90) < 12:
            out.append((a - 90.0 if a > 0 else a + 90.0, ln))
    return out


def skew(ink, w, near_horizontal=True, rng=10.0, scale=0.3):
    """在 ±rng 度里搜"投影最尖"的角度：横线看行投影的峰值，竖线看列投影的峰值。
    （按 Hough 段取中位角在这张图上会给出 −2.5°，而实际左右边框各是 −7.3°/+4.9°，
      倾斜本身还不一致，只有让投影峰值自己找才稳。）"""
    small = cv2.resize(ink, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    h, ww = small.shape
    best, bang = -1.0, 0.0
    for a in list(np.arange(-rng, rng + 1e-6, 0.5)) + list(np.arange(-0.5, 0.51, 0.1)):
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


def _unrot_pt(M, x, y):
    Mi = cv2.invertAffineTransform(M)
    p = Mi @ np.array([x, y, 1.0])
    return float(p[0]), float(p[1])


def extract(ink, ang, horiz, min_frac=0.16, max_thick_frac=0.012):
    """在转正后的墨迹图上抽长条，返回 [{x0,x1,y,w(厚度)}]（原图坐标）。"""
    h, w = ink.shape
    r, M = _rot(ink, ang)
    if horiz:
        k = max(3, int(h * 0.004) | 1)
        band = cv2.dilate(r, cv2.getStructuringElement(cv2.MORPH_RECT, (1, k)))
        band = cv2.morphologyEx(band, cv2.MORPH_OPEN,
                                cv2.getStructuringElement(cv2.MORPH_RECT,
                                                          (max(9, int(w * 0.2)), 1)))
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
            if bw < min_frac * w or bh > max_thick_frac * h * 4:
                continue
            yc = y + bh / 2.0
            x0, x1 = float(x), float(x + bw)
            px0, py0 = _unrot_pt(M, x0, yc)
            px1, py1 = _unrot_pt(M, x1, yc)
            out.append(dict(kind="H", a=px0, b=px1, y=(py0 + py1) / 2.0,
                            thick=max(2.5, bh * 0.5)))
        else:
            if bh < min_frac * h or bw > max_thick_frac * w * 4:
                continue
            xc = x + bw / 2.0
            y0, y1 = float(y), float(y + bh)
            px0, py0 = _unrot_pt(M, xc, y0)
            px1, py1 = _unrot_pt(M, xc, y1)
            out.append(dict(kind="V", a=min(py0, py1), b=max(py0, py1),
                            x=(px0 + px1) / 2.0, thick=max(2.5, bw * 0.5)))
    return out


def bright_flanks(gray, item, gap=6, min_mean=150.0, max_diff=22.0):
    """线两侧都得是亮纸，而且两侧亮度接近。
    真印刷线压在白纸上，两侧都是纸；纸边/投影线一侧是纸箱（暗且更不均匀），
    所以加"两侧亮度差 < max_diff"这条，把纸边当成框线的那类误检挡掉。"""
    h, w = gray.shape
    if item["kind"] == "H":
        y = int(round(item["y"])); x0 = max(0, int(item["a"])); x1 = min(w, int(item["b"]))
        if x1 - x0 < 8:
            return False
        top = gray[max(0, y - gap - 2):max(0, y - 1), x0:x1]
        bot = gray[min(h, y + 2):min(h, y + gap + 3), x0:x1]
        if top.size == 0 or bot.size == 0:
            return False
        t, b = float(top.mean()), float(bot.mean())
    else:
        x = int(round(item["x"])); y0 = max(0, int(item["a"])); y1 = min(h, int(item["b"]))
        if y1 - y0 < 8:
            return False
        lef = gray[y0:y1, max(0, x - gap - 2):max(0, x - 1)]
        rig = gray[y0:y1, min(w, x + 2):min(w, x + gap + 3)]
        if lef.size == 0 or rig.size == 0:
            return False
        t, b = float(lef.mean()), float(rig.mean())
    return min(t, b) > min_mean and abs(t - b) < max_diff


def run(path):
    gray = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    h, w = gray.shape
    ink = ink_binary(gray)
    ah = skew(ink, w, True)
    av = skew(ink, w, False)
    items = extract(ink, ah, True) + extract(ink, av, False)
    vis = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    keep = []
    for it in items:
        if not bright_flanks(gray, it):
            continue
        keep.append(it)
        if it["kind"] == "H":
            y = it["y"]
            cv2.rectangle(vis, (int(it["a"]), int(y - 2)), (int(it["b"]), int(y + 2)), (0, 0, 255), 2)
        else:
            x = it["x"]
            cv2.rectangle(vis, (int(x - 2), int(it["a"])), (int(x + 2), int(it["b"])), (255, 0, 0), 2)
    cv2.putText(vis, f"aH={ah:.2f} aV={av:.2f} found={len(items)} keep={len(keep)}",
                (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 200, 0), 2)
    dst = path.rsplit(".", 1)[0] + "_lines.png"
    cv2.imwrite(dst, vis)
    for it in keep:
        if it["kind"] == "H":
            print(f"H x={it['a']:.0f}..{it['b']:.0f} y={it['y']:.0f} t={it['thick']:.1f}")
        else:
            print(f"V x={it['x']:.0f} y={it['a']:.0f}..{it['b']:.0f} t={it['thick']:.1f}")
    print("overlay:", dst)


if __name__ == "__main__":
    run(sys.argv[1])
