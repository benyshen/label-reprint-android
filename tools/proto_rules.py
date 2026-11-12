"""框线识别原型（PC 上调参）——最终要移植到 ImageMeasure.detectRules。

思路：
1) 纸面掩膜：标签是白纸，纸箱是深色，用大津 + 最大连通域拿到纸面，线必须在纸面内；
2) 墨迹二值：自适应阈值（纸面内局部均值 - delta），比全局阈值抗反光；
3) 去斜：粗扫 -8..8 度，取向投影最尖锐的角度（表格线本来平行，斜了行投影就糊）；
4) 形态学开运算抽长条：横核 (w/25,1)、竖核 (1,h/25)，只留细长连通域；
5) 合并共线、按"沿线覆盖率"过滤，最后按原角度转回原图坐标。
"""
import sys
import numpy as np
import cv2


def paper_mask(gray):
    """纸面掩膜：标签是白纸（亮度明显高于纸箱背景）。

    用大津阈值不可靠 —— 照片里有反光的高光块，大津会切在 210 以上，把纸面判成"暗"，
    掩膜反而圈住了高光和外框。改成"大津与 65 分位取大"，再用最大连通域 + 闭运算填洞。
    """
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    otsu, _ = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    thr = max(float(otsu), float(np.percentile(gray, 65)))
    _, th = cv2.threshold(blur, thr, 255, cv2.THRESH_BINARY)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(th, 8)
    if n <= 1:
        return th
    areas = stats[1:, cv2.CC_STAT_AREA]
    i = 1 + int(np.argmax(areas))
    m = (lab == i).astype(np.uint8) * 255
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    # 内部填洞：从边界 floodFill 反选
    ff = m.copy()
    hh, ww = m.shape
    mask = np.zeros((hh + 2, ww + 2), np.uint8)
    cv2.floodFill(ff, mask, (0, 0), 255)
    m = cv2.bitwise_or(m, cv2.bitwise_not(ff))
    return m


def ink_binary(gray, pm, delta=28, block=31):
    """墨迹二值：局部自适应阈值（抗反光），不做纸面限制（纸面掩膜不可靠），
    背景/纸边的假线后面用"两侧是否都是亮纸"来剔除。"""
    ad = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                               cv2.THRESH_BINARY_INV, block if block % 2 else block + 1, 12)
    return cv2.morphologyEx(ad, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))


def bright_flanks(gray, b, horiz, gap=6, min_mean=150.0):
    """纸面印的线，上下（左右）两侧都是白纸；纸箱边缘/阴影线只有一侧是亮的。
    这是替代纸面分割的判据：不依赖阈值分割，反光和斜边都不影响。"""
    h, w = gray.shape
    x0, y0, x1, y1 = [int(round(v)) for v in b]
    x0 = max(0, x0); x1 = min(w, x1); y0 = max(0, y0); y1 = min(h, y1)
    if x1 - x0 < 4 or y1 - y0 < 1:
        return False
    if horiz:
        a0 = max(0, y0 - gap - 2); a1 = max(0, y0 - 1)
        b0 = min(h, y1 + 1); b1 = min(h, y1 + gap + 2)
        if a1 <= a0 or b1 <= b0:
            return False
        top = float(gray[a0:a1, x0:x1].mean())
        bot = float(gray[b0:b1, x0:x1].mean())
        return top > min_mean and bot > min_mean
    else:
        a0 = max(0, x0 - gap - 2); a1 = max(0, x0 - 1)
        b0 = min(w, x1 + 1); b1 = min(w, x1 + gap + 2)
        if a1 <= a0 or b1 <= b0:
            return False
        left = float(gray[y0:y1, a0:a1].mean())
        right = float(gray[y0:y1, b0:b1].mean())
        return left > min_mean and right > min_mean


def best_angle(ink, rng=10.0, step=0.5):
    """粗估表格线的倾斜角：转到某个角度时，行的墨迹投影最"尖"（线都落在少数几行里）。"""
    small = cv2.resize(ink, None, fx=0.35, fy=0.35, interpolation=cv2.INTER_AREA)
    h, w = small.shape
    best, bang = -1.0, 0.0
    for a in np.arange(-rng, rng + 1e-6, step):
        M = cv2.getRotationMatrix2D((w / 2, h / 2), a, 1.0)
        r = cv2.warpAffine(small, M, (w, h), flags=cv2.INTER_NEAREST)
        rows = r.sum(axis=1, dtype=np.float64)
        cols = r.sum(axis=0, dtype=np.float64)
        score = float((rows ** 2).sum() + (cols ** 2).sum())
        if score > best:
            best, bang = score, a
    # 细化
    for a in np.arange(bang - step, bang + step + 1e-6, 0.05):
        M = cv2.getRotationMatrix2D((w / 2, h / 2), a, 1.0)
        r = cv2.warpAffine(small, M, (w, h), flags=cv2.INTER_NEAREST)
        rows = r.sum(axis=1, dtype=np.float64)
        cols = r.sum(axis=0, dtype=np.float64)
        score = float((rows ** 2).sum() + (cols ** 2).sum())
        if score > best:
            best, bang = score, a
    return bang


def extract(ink, min_frac_w=0.16, min_frac_h=0.10, thick_tol=0.02):
    """抽横线/竖线。

    关键点：印刷线即使只斜 1°，整条横线也会跨几行像素，单行里只留下几十像素的短段，
    按"单行最长墨迹段"找线必然漏检（现在的实现就栽在这里）。做法是先在**垂直方向**
    轻度膨胀把斜线连成一条带（横线用 1×k 竖核），再用超宽的开运算核只保留长条：
    横线 → 横核 (w/4,1)；竖线 → 竖核 (1,h/4)。膨胀只发生在垂直方向，不会把相邻两条线粘一起。
    """
    h, w = ink.shape
    out = []

    # 横线：先竖向着色（1 x kv），再用横向开运算
    kv = max(3, int(h * 0.004) | 1)          # 约 5~9 行，盖住 1~2° 的倾斜
    hz = cv2.dilate(ink, cv2.getStructuringElement(cv2.MORPH_RECT, (1, kv)))
    hz = cv2.morphologyEx(hz, cv2.MORPH_OPEN,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (max(9, int(w * 0.25)), 1)))
    n, _, stats, _ = cv2.connectedComponentsWithStats(hz, 8)
    for i in range(1, n):
        x, y, bw, bh, _ = stats[i]
        if bw >= min_frac_w * w and bh <= thick_tol * h * 3:
            out.append([x, y, x + bw, y + bh])

    # 竖线：先横向着色（kh x 1），再用竖向开运算
    kh = max(3, int(w * 0.004) | 1)
    vt = cv2.dilate(ink, cv2.getStructuringElement(cv2.MORPH_RECT, (kh, 1)))
    vt = cv2.morphologyEx(vt, cv2.MORPH_OPEN,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(9, int(h * 0.2)))))
    n, _, stats, _ = cv2.connectedComponentsWithStats(vt, 8)
    for i in range(1, n):
        x, y, bw, bh, _ = stats[i]
        if bh >= min_frac_h * h and bw <= thick_tol * w * 3:
            out.append([x, y, x + bw, y + bh])
    return out


def merge(boxes, tol=4.0, overlap=0.55):
    boxes = [list(map(float, b)) for b in boxes]
    keep = []
    for b in sorted(boxes, key=lambda z: -((z[2] - z[0]) + (z[3] - z[1]))):
        horiz = (b[2] - b[0]) >= (b[3] - b[1])
        hit = False
        for k in keep:
            kh_ = (k[2] - k[0]) >= (k[3] - k[1])
            if kh_ != horiz:
                continue
            if horiz:
                if abs((k[1] + k[3]) - (b[1] + b[3])) / 2 > tol:
                    continue
                ov = min(k[2], b[2]) - max(k[0], b[0])
                if ov >= overlap * min(k[2] - k[0], b[2] - b[0]):
                    k[0] = min(k[0], b[0]); k[2] = max(k[2], b[2])
                    k[1] = min(k[1], b[1]); k[3] = max(k[3], b[3])
                    hit = True
                    break
            else:
                if abs((k[0] + k[2]) - (b[0] + b[2])) / 2 > tol:
                    continue
                ov = min(k[3], b[3]) - max(k[1], b[1])
                if ov >= overlap * min(k[3] - k[1], b[3] - b[1]):
                    k[0] = min(k[0], b[0]); k[2] = max(k[2], b[2])
                    k[1] = min(k[1], b[1]); k[3] = max(k[3], b[3])
                    hit = True
                    break
        if not hit:
            keep.append(b)
    return keep


def coverage(ink, b, horiz, band=4):
    h, w = ink.shape
    x0, y0, x1, y1 = [int(round(v)) for v in b]
    x0 = max(0, x0); y0 = max(0, y0); x1 = min(w, x1); y1 = min(h, y1)
    if x1 <= x0 or y1 <= y0:
        return 0.0
    if horiz:
        yc = (y0 + y1) // 2
        ya, yb = max(0, yc - band), min(h, yc + band + 1)
        seg = ink[ya:yb, x0:x1].max(axis=0) > 0
    else:
        xc = (x0 + x1) // 2
        xa, xb = max(0, xc - band), min(w, xc + band + 1)
        seg = ink[y0:y1, xa:xb].max(axis=1) > 0
    return float(seg.mean()) if seg.size else 0.0


def rot_back(b, ang, w, h):
    M = cv2.getRotationMatrix2D((w / 2, h / 2), ang, 1.0)
    Mi = cv2.invertAffineTransform(M)
    pts = np.array([[b[0], b[1], 1], [b[2], b[1], 1], [b[2], b[3], 1], [b[0], b[3], 1]], np.float32)
    q = pts @ Mi.T
    return [float(q[:, 0].min()), float(q[:, 1].min()), float(q[:, 0].max()), float(q[:, 1].max())]


def skew_angle(ink, w, h):
    """用 HoughLinesP 抽长直段，按长度加权取"近水平段"的中位角。
    投影峰值法在这种照片上会被文字和纸边带偏（实测给 1.25°，真值 ~5°）。"""
    sk = cv2.morphologyEx(ink, cv2.MORPH_OPEN,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))
    segs = cv2.HoughLinesP(sk, 1, np.pi / 360, threshold=80,
                           minLineLength=int(0.2 * w), maxLineGap=12)
    if segs is None:
        return 0.0
    vals = []
    for x1, y1, x2, y2 in segs[:, 0]:
        a = np.degrees(np.arctan2(y2 - y1, x2 - x1))
        ln = float(np.hypot(x2 - x1, y2 - y1))
        if abs(a) < 12:
            vals.append((a, ln))
    if not vals:
        return 0.0
    vals.sort(key=lambda z: z[0])
    tot = sum(z[1] for z in vals)
    acc = 0.0
    for a, ln in vals:
        acc += ln
        if acc >= tot / 2:
            return float(a)
    return float(vals[-1][0])


def main(path):
    gray = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    h, w = gray.shape
    ink0 = ink_binary(gray, None)
    ang = skew_angle(ink0, w, h)
    M = cv2.getRotationMatrix2D((w / 2, h / 2), ang, 1.0)
    ink = cv2.warpAffine(ink0, M, (w, h), flags=cv2.INTER_NEAREST)
    gray_r = cv2.warpAffine(gray, M, (w, h), flags=cv2.INTER_LINEAR)
    raw = extract(ink)
    mg = merge(raw)
    good = []
    for b in mg:
        horiz = (b[2] - b[0]) >= (b[3] - b[1])
        long = (b[2] - b[0]) if horiz else (b[3] - b[1])
        if not bright_flanks(gray_r, b, horiz):
            continue
        cov = coverage(ink, b, horiz)
        if cov < 0.85 and long < 0.45 * (w if horiz else h):
            continue
        good.append(b)
    vis = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    out = []
    for b in good:
        bb = rot_back(b, ang, w, h)
        out.append(bb)
        cv2.rectangle(vis, (int(bb[0]), int(bb[1])), (int(bb[2]), int(bb[3])), (0, 0, 255), 2)
    cv2.putText(vis, f"angle={ang:.2f} raw={len(raw)} merged={len(mg)} final={len(out)}",
                (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 200, 0), 2)
    dst = path.rsplit(".", 1)[0] + "_lines.png"
    cv2.imwrite(dst, vis)
    for b in out:
        horiz = (b[2] - b[0]) >= (b[3] - b[1])
        print(("H" if horiz else "V"),
              f"x={b[0]:.0f}..{b[2]:.0f} y={b[1]:.0f}..{b[3]:.0f}",
              f"len={(b[2]-b[0]) if horiz else (b[3]-b[1]):.0f}")
    print("angle", round(ang, 2), "overlay:", dst)


if __name__ == "__main__":
    main(sys.argv[1])
