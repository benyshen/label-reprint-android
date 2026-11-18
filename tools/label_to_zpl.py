"""Label photo -> elements -> ZPL. PC reference implementation.

This is the algorithm the Android app must reproduce byte for byte; every Kotlin
bug then becomes a diff against this file instead of guesswork.

Usage:
  python label_to_zpl.py <image> [--template 1|2|3] [--out dir] [--json] [--debug viz.png]
"""
import argparse, json, os, sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zpl_geometry as G

try:
    import zxingcpp
except ImportError:  # pragma: no cover
    zxingcpp = None

# ---------------------------------------------------------------------------
# 1. barcode decoding
# ---------------------------------------------------------------------------
# zxing-cpp symbology_identifier -> our internal kind
KIND_2D = {"QRCode", "DataMatrix", "PDF417", "Aztec", "MaxiCode", "MicroQRCode", "CompactPDF417"}


def decode_barcodes(bgr):
    """-> list of {format, kind, text, quad(4x2 px), w, h}"""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    results = zxingcpp.read_barcodes(gray, formats=zxingcpp.BarcodeFormat.AllReadable)
    out = []
    for r in results:
        if not r.valid:
            continue
        p = r.position
        quad = np.array([[p.top_left.x, p.top_left.y], [p.top_right.x, p.top_right.y],
                         [p.bottom_right.x, p.bottom_right.y], [p.bottom_left.x, p.bottom_left.y]],
                        np.float32)
        x0, y0 = quad[:, 0].min(), quad[:, 1].min()
        x1, y1 = quad[:, 0].max(), quad[:, 1].max()
        fmt = str(r.format)
        kind = "barcode2d" if (r.symbology_identifier in KIND_2D or "QR" in fmt
                               or "Matrix" in fmt or "Aztec" in fmt or "PDF" in fmt
                               or "Maxi" in fmt) else "barcode1d"
        out.append({"format": fmt, "kind": kind, "text": r.text, "quad": quad,
                    "symbology": G.symbology(fmt),
                    "px": [float(x0), float(y0), float(x1), float(y1)]})
    return out


def measure_bar_rows(gray, x0, x1, y_hint, thr=0.30, min_run=5):
    """一维码的条高必须自己从图上量。

    zxing-cpp 的 Android 封装对一维码返回的是**扫描线**（实测 `lines=5` 也只回 2 点高的框、
    位置还落在条码下沿），不是一个符号包围盒；Python 端同一版本返回整框。所以两端都改成：
    在 zxing 给的 x 区间内按行统计墨迹占比，取包含 y_hint 的那一段连续墨迹带。

    实测分离度：条码行 ≈0.45、下方人眼可读串 ≤0.21、空白 0.00 -> 阈值 0.30 干净切开。
    """
    h, w = gray.shape[:2]
    xa = int(max(0, min(x0, x1)))
    xb = int(min(w, max(x0, x1) + 1))
    if xb - xa < 4:
        return None
    ratio = (gray[:, xa:xb] < 128).mean(1)
    rows = np.where(ratio >= thr)[0]
    if len(rows) == 0:
        return None
    runs, start, prev = [], rows[0], rows[0]
    for r in rows[1:]:
        if r != prev + 1:
            runs.append((int(start), int(prev)))
            start = r
        prev = r
    runs.append((int(start), int(prev)))
    runs = [r for r in runs if r[1] - r[0] + 1 >= min_run]
    if not runs:
        return None
    return min(runs, key=lambda r: abs((r[0] + r[1]) / 2.0 - y_hint))


def _overlaps(a, b, thr=0.5):
    """fraction of box a inside box b"""
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    area_a = max(1e-6, (a[2] - a[0]) * (a[3] - a[1]))
    return inter / area_a >= thr


# ---------------------------------------------------------------------------
# 2. text OCR (PP-OCRv4) and cleanup
# ---------------------------------------------------------------------------
def ocr_lines(bgr, det, rec, min_conf=0.5):
    sys.path.insert(0, "E:/workspace/paddleocr-android-app/tools")
    import ppocr_ref
    boxes = ppocr_ref.detect(det, bgr)
    out = []
    for box, text, conf in ppocr_ref.recognize(rec, bgr, boxes):
        t = text.strip()
        if not t or conf < min_conf:
            continue
        x0, y0 = box[:, 0].min(), box[:, 1].min()
        x1, y1 = box[:, 0].max(), box[:, 1].max()
        out.append({"text": t, "conf": float(conf), "quad": box,
                    "px": [float(x0), float(y0), float(x1), float(y1)]})
    return out


def _norm(s):
    return "".join(ch for ch in s.upper() if ch.isalnum())


def measure_ink(gray, px, thr=128, min_ratio=0.05):
    """元素框里的**墨迹**范围 (宽, 高)，单位像素。

    PP-OCR 的框是"行框"：上下留了行距、左右留了字距，比字形本身胖 10~20%。
    拿框高推字号就打出一号大的字（V1.9 那张输出就是这样）。字号必须按墨迹反推。
    """
    if gray is None:
        return None
    H, W = gray.shape
    x0 = max(0, min(W - 1, int(round(px[0]))))
    x1 = max(x0 + 1, min(W, int(round(px[2]))))
    y0 = max(0, min(H - 1, int(round(px[1]))))
    y1 = max(y0 + 1, min(H, int(round(px[3]))))
    ink = gray[y0:y1, x0:x1] < thr
    if not ink.any():
        return None
    rows = np.where(ink.mean(axis=1) >= min_ratio)[0]
    cols = np.where(ink.mean(axis=0) >= min_ratio)[0]
    if len(rows) == 0 or len(cols) == 0:
        return None
    return float(cols[-1] - cols[0] + 1), float(rows[-1] - rows[0] + 1)


def _runs(mask_row):
    """一维布尔数组的连续 True 段 [(start, end_exclusive)]。"""
    if not mask_row.any():
        return []
    d = np.diff(np.concatenate(([0], mask_row.astype(np.int8), [0])))
    return list(zip(np.where(d == 1)[0], np.where(d == -1)[0]))


def _longest_run(dark, bridge):
    """一维布尔序列里最长的一段墨迹（允许 bridge 个像素内的断点），返回 (起点, 终点, 长度)。

    允许桥接是因为照片里的框线常被反光、污点、折痕打断；不桥接的话一根线会被切成十几段。
    """
    n = len(dark)
    best = None
    i = 0
    while i < n:
        if not dark[i]:
            i += 1
            continue
        start = i
        end = i
        j = i + 1
        while j < n:
            if dark[j]:
                end = j
                j += 1
                continue
            # 往后看 bridge 个像素有没有接上
            k = j
            while k < n and k - j <= bridge and not dark[k]:
                k += 1
            if k < n and k - j <= bridge:
                end = k
                j = k + 1
            else:
                break
        length = end - start + 1
        if best is None or length > best[2]:
            best = (start, end, length)
        i = end + 1
    return best


def _scan_bands(dark, min_len, bridge, gap_rows=1):
    """在 dark[n][m]（行 = 扫描方向）里找"长线"。

    每一行取最长墨迹段；段长 ≥ min_len 的行记为命中行；相邻命中行（允许 gap_rows 行间隔）
    并成一条带，带的延伸范围取所有命中段的最小/最大列。
    这样即使线是斜的（同一根线在不同行只覆盖不同的一小段），带也能把整根线兜住 ——
    逐行分组做不到这件事，实测一张略微歪斜的分隔线被拆成 20 多段。
    """
    rows = []
    for i in range(dark.shape[0]):
        seg = _longest_run(dark[i], bridge)
        if seg is not None and seg[2] >= min_len:
            rows.append((i, seg[0], seg[1], seg[2]))
    bands = []
    for i, s, e, ln in rows:
        for b in bands:
            if i - b[1] <= gap_rows:
                b[1] = i
                b[2] = min(b[2], s)
                b[3] = max(b[3], e)
                b[4] = max(b[4], ln)
                break
        else:
            bands.append([i, i, s, e, ln])
    return [(b[0], b[1], b[2], b[3]) for b in bands]


def _pos(b, W, H):
    """线在长轴上的归一化位置（横线看 y，竖线看 x）。"""
    return ((b[1] + b[3]) / 2 / H) if (b[2] - b[0]) >= (b[3] - b[1]) else ((b[0] + b[2]) / 2 / W)


def detect_rules(gray, exclude_px=(), min_frac=0.22, thr_list=(110, 128, 150, 170), text_px=()):
    """从照片里找**长直线**（标签外框、分隔线）—— V1.9 完全没有这个能力，框体就全丢了。

    多档阈值取并集：同一张照片里外框左上段约 150 灰度、分隔线约 120、右下段更浅，
    固定 128 会漏，Otsu 会漏另一边，自适应阈值更差（实测只剩 2 条）。
    竖线靠转置扫描；落在条码框内的竖线丢掉 —— 条码的条本身就是一列列实心墨迹。
    """
    if gray is None:
        return []
    H, W = gray.shape
    bridge = max(4, W // 200)
    pad = max(3, W // 200)
    h_boxes, v_boxes = [], []
    for thr in thr_list:
        dark = gray < thr
        # 照片最外圈是背景/纸边阴影：不遮掉的话整列暗背景会被当成"竖线"，
        # 接着把标签范围的基准拉到 x=0（实测外框被画成 ^FO0 起、横贯整张）。
        dark[:pad, :] = False
        dark[-pad:, :] = False
        dark[:, :pad] = False
        dark[:, -pad:] = False
        for i0, i1, s, e in _scan_bands(dark, min_frac * W, bridge):
            h_boxes.append([float(s), float(i0), float(e + 1), float(i1 + 1)])
        for i0, i1, s, e in _scan_bands(dark.T, min_frac * H, bridge):
            v_boxes.append([float(i0), float(s), float(i1 + 1), float(e + 1)])

    def co_linear(a, b, axis_len):
        """同一根线的两段：轴向一致、中线对齐、厚度相近、中间空档很小。

        空档必须给得很小（2% 轴长）：之前给 0.15 会把"几笔不相关的文字竖画"链成一条
        贯穿整张标签的假竖线（实测 x=70 那条从品名拉到最底部）。
        """
        a_horiz = (a[2] - a[0]) >= (a[3] - a[1])
        if a_horiz != ((b[2] - b[0]) >= (b[3] - b[1])):
            return False
        if a_horiz:
            ta, tb = a[3] - a[1], b[3] - b[1]
            if max(ta, tb) > 2.5 * max(1.0, min(ta, tb)):
                return False
            ca, cb = (a[1] + a[3]) / 2, (b[1] + b[3]) / 2
            if abs(ca - cb) > max(10.0, max(ta, tb)):
                return False
            gap = max(a[0], b[0]) - min(a[2], b[2])
            return gap <= max(6.0, 0.02 * W)
        ta, tb = a[2] - a[0], b[2] - b[0]
        if max(ta, tb) > 2.5 * max(1.0, min(ta, tb)):
            return False
        ca, cb = (a[0] + a[2]) / 2, (b[0] + b[2]) / 2
        if abs(ca - cb) > max(10.0, max(ta, tb)):
            return False
        gap = max(a[1], b[1]) - min(a[3], b[3])
        return gap <= max(6.0, 0.02 * H)

    merged = []
    for b in h_boxes + v_boxes:
        for m in merged:
            if co_linear(m, b, W):
                m[0], m[1] = min(m[0], b[0]), min(m[1], b[1])
                m[2], m[3] = max(m[2], b[2]), max(m[3], b[3])
                break
        else:
            merged.append(list(b))

    def is_horiz(b):
        return (b[2] - b[0]) >= (b[3] - b[1])

    # 交叉线段互相延伸：**只补角** —— 竖线的端点必须就贴在横线附近才拉。
    # 之前不判距离，任何一根短的竖笔（字的竖画、噪点）都被拉到整张标签高，
    # 打出来是一排莫名的竖线。照片里框体的角常被裁掉/反光，补的正是这一段。
    tol_h = max(10.0, 0.025 * H)
    tol_w = max(10.0, 0.025 * W)
    hs = [b for b in merged if is_horiz(b)]
    vs = [b for b in merged if not is_horiz(b)]
    for h in hs:
        for v in vs:
            near = (abs(v[1] - h[1]) <= tol_h or abs(v[1] - h[3]) <= tol_h
                    or abs(v[3] - h[1]) <= tol_h or abs(v[3] - h[3]) <= tol_h)
            if not near:
                continue
            cy = (h[1] + h[3]) / 2
            if v[1] - tol_h <= cy <= v[3] + tol_h:
                h[0] = min(h[0], v[0])
                h[2] = max(h[2], v[2])
    for v in vs:
        for h in hs:
            near = (abs(h[0] - v[0]) <= tol_w or abs(h[0] - v[2]) <= tol_w
                    or abs(h[2] - v[0]) <= tol_w or abs(h[2] - v[2]) <= tol_w)
            if not near:
                continue
            cx = (v[0] + v[2]) / 2
            if h[0] - tol_w <= cx <= h[2] + tol_w:
                v[1] = min(v[1], h[1])
                v[3] = max(v[3], h[3])

    # 用最外侧的竖线/横线定出标签范围：越界的裁掉；贴着上下（左右）边的直接拉满整条 ——
    # 原标签的上下边框本来就是从左框到右框，照片里被反光/折痕截断的那一段必须补回来，
    # 否则打出来是"缺半个上边框"。判据用"中线离边 ≤5% 轴长"，比"差多少像素"稳。
    if hs and vs:
        fx0 = min(v[0] for v in vs)
        fx1 = max(v[2] for v in vs)
        fy0 = min(h[1] for h in hs)
        fy1 = max(h[3] for h in hs)
        for h in hs:
            cy = (h[1] + h[3]) / 2
            h[0] = max(h[0], fx0)
            h[2] = min(h[2], fx1)
            if abs(cy - fy0) <= 0.05 * H or abs(cy - fy1) <= 0.05 * H:
                h[0], h[2] = fx0, fx1
            else:
                if h[0] - fx0 <= 0.15 * W:
                    h[0] = fx0
                if fx1 - h[2] <= 0.15 * W:
                    h[2] = fx1
        for v in vs:
            cx = (v[0] + v[2]) / 2
            v[1] = max(v[1], fy0)
            v[3] = min(v[3], fy1)
            if abs(cx - fx0) <= 0.05 * W or abs(cx - fx1) <= 0.05 * W:
                v[1], v[3] = fy0, fy1
            else:
                if v[1] - fy0 <= 0.15 * H:
                    v[1] = fy0
                if fy1 - v[3] <= 0.15 * H:
                    v[3] = fy1

    def inside_barcode(b):
        """与条码区**区间相交**就丢掉。

        不能用中心点判：条码的竖条常被并成一条"跨越高度的假竖线"（实测从 y=197 拉到 430，
        穿过整行文字），它的 bbox 中心落在条码框外面，中心点判据就漏掉了，最后纸上多了
        七八根莫名其妙的竖线。
        """
        # 容差按图宽 2%（至少 8px）：条码最外一根条常常压在解码框边上几像素，
        # 容差 2px 时它会被当成一根贯穿标签的竖线（实测 x=401 那根）
        tol = max(8.0, 0.02 * W)
        # 长横线不参与排除：条码的条是竖直的，横贯整版的横线不可能是它的一部分，
        # 而下边框正好压在 HRI 带下方（实测被吃掉 -> "最底下那根出不来"）
        if (b[2] - b[0]) >= 0.25 * W:
            return False
        return any(b[0] < ex[2] + tol and b[2] > ex[0] - tol
                   and b[1] < ex[3] + tol and b[3] > ex[1] - tol for ex in exclude_px)

    def ink_fraction(b, thr=200, band=4):
        """沿线的**连续性**：沿线每个位置（±band 像素内）有没有墨。

        真印出来的线是连续的（>0.9）；由"几笔文字竖画 + 桥接"拼出来的假线只有 0.2~0.5
        （笔画之间有空白），这一条把 x=70 那种贯穿整张标签的假竖线挡在门外。
        必须按"沿线覆盖 + 容差带"量，不能直接数框内像素：照片里的框线是斜的，
        2 像素宽的细带装不住它，直接数量会把真线也判成 0.2 误杀（实测丢了品名上方的分隔线）。
        """
        x0 = max(0, int(round(b[0])))
        y0 = max(0, int(round(b[1])))
        x1 = min(gray.shape[1], max(x0 + 1, int(round(b[2]))))
        y1 = min(gray.shape[0], max(y0 + 1, int(round(b[3]))))
        if x1 - x0 < 2 or y1 - y0 < 2:
            return 1.0
        if (x1 - x0) >= (y1 - y0):
            yc = (y0 + y1) // 2
            sub = gray[max(0, yc - band):min(gray.shape[0], yc + band + 1), x0:x1]
            return float((sub < thr).any(axis=0).mean())
        xc = (x0 + x1) // 2
        sub = gray[y0:y1, max(0, xc - band):min(gray.shape[1], xc + band + 1)]
        return float((sub < thr).any(axis=1).mean())

    # 斜线/反光会把 bbox 撑厚（实测一根横线 bbox 高 24px，真笔画只有 3px）：
    # 按"中线 + 估算笔画厚度"重新拉成细线，印出来才是线而不是色块。
    def thin(b):
        x0, y0, x1, y1 = b
        w, h = x1 - x0, y1 - y0
        if w >= h:
            t = max(2.0, min(h, 0.004 * w + 2.0))
            cy = (y0 + y1) / 2
            return [x0, cy - t / 2, x1, cy + t / 2]
        t = max(2.0, min(w, 0.004 * h + 2.0))
        cx = (x0 + x1) / 2
        return [cx - t / 2, y0, cx + t / 2, y1]

    thinned = []
    for b in merged:
        if inside_barcode(b):
            continue
        b = thin(b)
        # 实心判据在 thin() 之后做：斜线的 bbox 带一圈白边，先在厚框上量会误杀真线
        # 覆盖率判据暂关（与安卓端一致）：这张鼠标垫照片上它把 4 条边框全杀了，
        # 覆盖率只有 0.5~0.6（细线+抗锯齿），而它原本是为了挡"文字笔画拼出的假线"。
        if ink_fraction(b) < 0.0:
            continue
        # 多档阈值对同一条线会测出几像素的差：重叠超过 70% 的只留最长的一条
        dup = False
        for i, o in enumerate(thinned):
            ix = min(b[2], o[2]) - max(b[0], o[0])
            iy = min(b[3], o[3]) - max(b[1], o[1])
            if ix <= 0 or iy <= 0:
                continue
            area_b = (b[2] - b[0]) * (b[3] - b[1])
            area_o = (o[2] - o[0]) * (o[3] - o[1])
            if ix * iy >= 0.7 * min(area_b, area_o):
                if area_b > area_o:
                    thinned[i] = b
                dup = True
                break
        if not dup:
            thinned.append(b)

    # 占跨度 ≥40% 的线补齐到整条：原标签的分隔线本来就是从左框到右框，照片里被反光/
    # 折痕截断的那半截必须补回来，否则打出来是"半截线"，比不画还难看。
    hh = [b for b in thinned if (b[2] - b[0]) >= (b[3] - b[1])]
    vv = [b for b in thinned if (b[2] - b[0]) < (b[3] - b[1])]
    if len(vv) >= 2:
        fx0 = min(b[0] for b in vv)
        fx1 = max(b[2] for b in vv)
        for b in hh:
            if (b[2] - b[0]) >= 0.4 * (fx1 - fx0):
                b[0], b[2] = fx0, fx1
    if len(hh) >= 2:
        fy0 = min(b[1] for b in hh)
        fy1 = max(b[3] for b in hh)
        for b in vv:
            if (b[3] - b[1]) >= 0.4 * (fy1 - fy0):
                b[1], b[3] = fy0, fy1

    # 最终长度门槛：短于跨度 8% 的"线"不可能是纸面印的框线/分隔线，都是文字笔画或
    # 裁切残渣（相机照片实测一次 20+ 段 3~12 点碎线，全落在 HRI 那排数字上）。
    # 放在最后做：前面的补边/裁边会改长度，只有这里才是最终长度。
    out = []
    for b in thinned:
        if (b[2] - b[0]) >= (b[3] - b[1]):
            if (b[2] - b[0]) < 0.08 * W:
                continue
        elif (b[3] - b[1]) < 0.08 * H:
            continue
        # 文字笔画：只有**短线**才按文字框排除。整根边框和某个文本框相交是常态
        # （左边框就压在文字框的左边），按相交判会把边框整条丢掉（实测只剩一条 y=554 的碎线）。
        short = (b[2] - b[0]) < 0.25 * W and (b[3] - b[1]) < 0.25 * H
        if short and any(b[0] < t[2] and b[2] > t[0] and b[1] < t[3] and b[3] > t[1]
                         for t in text_px):
            continue
        out.append(b)
    return out


def _is_hri_of(line_text, barcode_text):
    """这一行 OCR 是不是条码下方的人眼可读串（HRI）。

    原来只认"去掉非字母数字后完全相等"，实测漏掉两种：
    - 原标签 EAN-13 的 HRI 是 `6 934024 512123`，OCR 把分隔符读成引号 -> `934024"512123`
      （12 位，少一位），精确比对失败 -> 被当成普通文字单独发了一遍，
      纸面上就是 V1.9 那张 `934024"512123` 的独立文字行。
    - 条码勾了校验位时 HRI 会多/少一位。
    所以改成：两边都是数字时，只要较短的位数 ≥ 6 且被较长的包含，就算同一条 HRI。
    """
    if _norm(line_text) == _norm(barcode_text):
        return True
    d1 = "".join(ch for ch in line_text if ch.isdigit())
    d2 = "".join(ch for ch in barcode_text if ch.isdigit())
    if len(d1) < 6 or len(d2) < 6:
        return False
    short, long_ = (d1, d2) if len(d1) <= len(d2) else (d2, d1)
    return short in long_


def cleanup_text(lines, barcodes):
    """(a) drop OCR boxes that are really the barcode itself,
       (b) fold the human-readable line under each 1D barcode into that barcode
           (ZPL prints it via ^BC...,Y so it must NOT be a second text field)."""
    kept, dropped = [], []
    for ln in lines:
        hit = next((b for b in barcodes if _overlaps(ln["px"], b["px"])), None)
        if hit:
            dropped.append((ln, "overlaps barcode"))
            continue
        kept.append(ln)

    rest = []
    for ln in kept:
        owner = None
        for b in barcodes:
            if b["kind"] != "barcode1d" or b.get("hri"):
                continue
            if not _is_hri_of(ln["text"], b["text"]):
                continue
            # must sit below the barcode and overlap it horizontally
            bh = b["px"][3] - b["px"][1]
            below = 0 <= (ln["px"][1] - b["px"][3]) <= 1.2 * bh
            hov = min(ln["px"][2], b["px"][2]) - max(ln["px"][0], b["px"][0])
            if below and hov > 0.3 * (ln["px"][2] - ln["px"][0]):
                owner = b
                break
        if owner:
            owner["hri"] = True
            owner["hri_px"] = ln["px"]
            dropped.append((ln, "human-readable line of " + str(owner["text"])))
        else:
            rest.append(ln)
    return rest, dropped


# ---------------------------------------------------------------------------
# 3. pixel -> label dots
# ---------------------------------------------------------------------------
def content_bbox(elements):
    x0 = min(e["px"][0] for e in elements)
    y0 = min(e["px"][1] for e in elements)
    x1 = max(e["px"][2] for e in elements)
    y1 = max(e["px"][3] for e in elements)
    return np.array([x0, y0, x1, y1], float)


def map_to_label(elements, template, basis_rect=None, margin=None, top_offset=None):
    """Uniform scale + centring: `basis_rect` (default: union of all elements) -> printable area.

    When the basis already IS the label (`full` / `label`), margins must be ZERO: the photo's own
    white space defines where things sit. Adding our own margin on top shifts every element
    (measured: a systematic +16..22 dot offset = 2+ mm on a 60x40 label).
    """
    W, H = G.dims(template)
    if margin is None:
        margin = 0 if basis_rect is not None else G.MARGIN
    if top_offset is None:
        top_offset = 0 if basis_rect is not None else G.TOP_OFFSET
    bb = np.asarray(basis_rect, float) if basis_rect is not None else content_bbox(elements)
    cw, ch = bb[2] - bb[0], bb[3] - bb[1]
    usable_w, usable_h = W - 2 * margin, H - 2 * margin - top_offset
    scale = min(usable_w / cw, usable_h / ch)
    dx = margin + (usable_w - cw * scale) / 2.0
    dy = top_offset + margin + (usable_h - ch * scale) / 2.0

    def m(vx, vy):
        return dx + (vx - bb[0]) * scale, dy + (vy - bb[1]) * scale

    for e in elements:
        x0, y0 = m(e["px"][0], e["px"][1])
        x1, y1 = m(e["px"][2], e["px"][3])
        e["dots"] = [x0, y0, x1, y1]
        e["w"] = x1 - x0
        e["h"] = y1 - y0
        # 墨迹尺寸从像素换算到点阵 —— 之前直接拿像素当点用，字号大了 2 倍多
        if e.get("ink_w"):
            e["ink_w_dots"] = e["ink_w"] * scale
            e["ink_h_dots"] = e["ink_h"] * scale
        if e.get("hri_px"):
            _, hx1 = m(e["hri_px"][0], e["hri_px"][1])
            hy1 = m(e["hri_px"][0], e["hri_px"][3])[1]
            e["hri_h"] = hy1 - e["dots"][1] - e["h"]
    return scale, bb


def choose_barcode_params(e, template, scale):
    """pick ^BY module width / ^BQ magnification from the measured footprint."""
    W, _ = G.dims(template)
    usable_w = W - G.MARGIN - e["dots"][0]
    text = e["text"]
    if e["kind"] == "barcode1d":
        sym = e.get("symbology") or G.symbology(e.get("format"))
        e["symbology"] = sym
        modules = G.barcode_modules(sym, text)
        derived = e["w"] / modules
        # 低于可印下限不能照抄：实测 ^BY1（1 点 = 0.125mm）的 Code128 印成一团糊，
        # 扫描器测不准条宽就退化判成 Code 39（用户实际遇到的现象）。见 zpl_geometry.MIN_MODULE_DOTS。
        module_w = max(G.MIN_MODULE_DOTS, G.round_half(min(derived, 10.0) * 2) / 2.0)
        bar_h = G.round_half(e["h"])
        phys = W - G.GUARD - e["dots"][0]          # 物理可打印宽度
        notes = []
        if derived < G.MIN_MODULE_DOTS:
            notes.append(f"原图模块宽仅 {derived:.1f} 点，向上取到下限 {module_w}")
        if modules * module_w > usable_w:
            if modules * module_w <= phys:
                # 只差几个点：宁可靠到打印边界，也不缩到下限以下（扫不出来的条码等于没打）
                notes.append(f"按下限需 {modules * module_w} 点 > 留白 {usable_w:.0f} 点，"
                             f"已占用到距标签边 {G.GUARD} 点")
            else:
                module_w = max(1.0, phys / modules)
                notes.append(f"按下限需 {G.MIN_MODULE_DOTS * modules} 点 > 可打印宽 {phys:.0f} 点，"
                             f"已压到 {module_w:.1f} 点（可能扫不出）")
        e["module_width_notes"] = notes
        e.update(module_width=module_w, bar_height=bar_h, modules=modules,
                 est_width=modules * module_w)
    else:
        modules = G.qr_modules_for_text(text)
        mag = int(np.clip(G.round_half(e["w"] / modules), G.QR_MIN_MAG, G.QR_MAX_MAG))
        while mag > G.QR_MIN_MAG and modules * mag > usable_w:
            mag -= 1
        e.update(magnification=mag, modules=modules, black_h=modules * mag)
    return e


# ---------------------------------------------------------------------------
# 4. ZPL emission
# ---------------------------------------------------------------------------
def _cjk(s):
    return any(ord(c) > 0x2E80 for c in s)


def emit_zpl(elements, template, warn=None):
    W, H = G.dims(template)
    lines = ["^XA", "^CI28", f"^PW{W}", f"^LL{H}"]
    dropped = []
    for e in sorted(elements, key=lambda el: (el["dots"][1], el["dots"][0])):
        x = int(G.round_half(e["dots"][0]))
        top = int(G.round_half(e["dots"][1]))
        if e["kind"] == "barcode1d":
            p = choose_barcode_params(e, template, 1.0)
            sym = p.get("symbology") or G.symbology(e.get("format"))
            anchor = top + G.anchor_dy("barcode1d", bar_height=p["bar_height"])
            field = G.barcode_1d_field(sym, x, anchor, p["module_width"], p["bar_height"],
                                       interp=bool(e.get("hri")) or G.hri_forced(sym))
            field += f"^FD{e['text']}^FS"
            bottom = anchor
        elif e["kind"] == "box":
            # 线框/分隔线：^GB 的坐标原点是**左上角**（与文字/条码的下沿锚点不同），
            # 所以固定用 ^FO。细线用"厚度=高度"画成实心条，一行一条，位置与照片逐点对应。
            x0 = int(G.round_half(e["dots"][0]))
            y0 = int(G.round_half(e["dots"][1]))
            x1 = int(G.round_half(e["dots"][2]))
            y1 = int(G.round_half(e["dots"][3]))
            bw = max(1, x1 - x0)
            bh = max(1, y1 - y0)
            # 厚度钳到 3~5 点（0.38~0.63mm）：细线（1~2 点）打出来发虚、下次拍照更难检出；
            # 上限 5，再粗 ^GB 会把 3 点笔画糊成色块。
            th = 5.0   # 统一 5 点（0.63mm）：实测 3 点仍发虚、下次拍照难检出
            field = f"^FO{x0},{y0}^GB{bw},{bh},{int(round(th))}^FS"
            bottom = y1
        elif e["kind"] == "barcode2d":
            p = choose_barcode_params(e, template, 1.0)
            anchor = top + G.anchor_dy("barcode2d", black_h=p["black_h"], magnification=p["magnification"])
            field = f"^FT{x},{anchor}^BQN,2,{p['magnification']}^FDQA,{e['text']}^FS"
            bottom = anchor
        else:
            maxw = max(40, int(W - G.MARGIN - x))
            # 字号要按**墨迹**算，不能按 OCR 框算：DB 的文本框比字形本身胖 10~20%，
            # 直接拿框高推字号，打出来就是"字比原标签大一号"（用户那张 V1.9 输出）。
            # 墨迹宽再除以 0.92：印出来的墨迹宽只有推进宽的 92%（zpl-core 实测）。
            ink_w = e.get("ink_w_dots") or e["w"]
            ink_h = e.get("ink_h_dots") or e["h"]
            budget_w = max(20.0, min(float(maxw), ink_w / G.WIDTH_SLACK))
            fs = G.font_size_for(budget_w, ink_h, e["text"])
            # 硬兜底：字号超字段宽时打印机不是截断，而是把字符挤着叠印（纸上"文字重合"）
            while fs > 8 and G.text_width(e["text"], fs) > maxw:
                fs -= 1
            anchor = top + G.anchor_dy("text", font_size=fs, lines=1)
            font = f"^A@N,{fs},{fs},E:SIMSUN.TTF" if _cjk(e["text"]) else f"^A0N,{fs},{fs}"
            field = f"^FT{x},{anchor}{font}^FB{maxw},1,0,L,0^FD{e['text']}^FS"
            bottom = anchor
            if G.text_width(e["text"], fs) > maxw:
                dropped.append((e["text"], f"even at size 8 wider than {maxw} dots - ^FB truncates"))
        # the anchor is the LOWEST row an element occupies (all three field kinds are
        # bottom-anchored) -> clamping the anchor is what keeps ink inside the label
        if e["kind"] != "box" and bottom > H - G.GUARD:
            shift = bottom - (H - G.GUARD)
            field = _shift_y(field, shift)
            dropped.append((e["text"], f"clamped up {shift} dots"))
        lines.append(field)
    lines += ["^XZ"]
    return "\r\n".join(lines) + "\r\n", dropped


def _f(v):
    return str(int(v)) if float(v) == int(v) else str(v)


def _shift_y(field, dy):
    """move the field's ^FT y up by dy (used by the bottom-of-label clamp)."""
    import re
    m = re.search(r"\^FT(-?\d+),(-?\d+)", field)
    if not m:
        return field
    y = max(0, int(m.group(2)) - int(dy))
    return field[:m.start()] + f"^FT{m.group(1)},{y}" + field[m.end():]


def build(image_path, template=G.DEFAULT_TEMPLATE, det=None, rec=None, frame="auto"):
    src = cv2.imread(image_path)
    if src is None:
        raise SystemExit(f"cannot read {image_path}")
    import label_frame
    bgr, basis, finfo = label_frame.prepare(src, frame)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    barcodes = decode_barcodes(bgr)
    for b in barcodes:
        if b["kind"] != "barcode1d":
            continue
        rows = measure_bar_rows(gray, int(b["px"][0]), int(b["px"][2]),
                                int((b["px"][1] + b["px"][3]) / 2))
        if rows:
            b["px"][1], b["px"][3] = float(rows[0]), float(rows[1])
            b["bar_rows_measured"] = True
    texts = ocr_lines(bgr, det, rec) if det is not None else []
    texts, dropped_ocr = cleanup_text(texts, barcodes)
    for t in texts:
        t["kind"] = "text"
        ink = measure_ink(gray, t["px"])
        if ink:
            t["ink_w"], t["ink_h"] = ink
    # 框线/分隔线：照片里量出来的长直线（V1.9 缺的就是这一层）
    # 文字行外框也排除：人眼可读串里每个数字的横笔画都会被当成短横线检出
    # 1D 条码的排除区要向下延伸到它的 HRI 带（人眼可读串就贴在下沿），
    # 那排数字的横笔画会被当成一堆短横线（实测一次 20+ 段 3~12 点碎线）。
    excl = []
    for x in barcodes:
        px = list(x["px"])
        if x["kind"] == "barcode1d":
            px[3] = px[3] + 1.2 * (px[3] - px[1])
        excl.append(px)
    text_px = [list(x["px"]) for x in texts]
    rules = [{"kind": "box", "text": "", "px": b, "hri": False}
             for b in detect_rules(gray, excl, text_px=text_px)
             # 纸边禁区：印刷框离纸边至少 9%% 留白，贴着边界的线是纸边不是框线
             if not (basis == "label" and (
                 (_pos(b, W, H) < 0.06) or (_pos(b, W, H) > 0.94)))]
    elements = barcodes + texts + rules
    for e in elements:
        e.setdefault("hri", False)
    h, w = bgr.shape[:2]
    # 没检出标签四边形时整帧 1:1 映射会把标签缩成半张（相机照片贴着浅色背景时常见），
    # 要素只占画面一小块就改成按内容框铺满
    use_content = frame == "content"
    if not use_content and elements:
        # 只用文字+条码算内容框：框线里的碎线会把 bbox 撑满整幅
        solid = [e for e in elements if e["kind"] != "box"] or elements
        cb = content_bbox(solid)
        if (cb[2] - cb[0]) * (cb[3] - cb[1]) < 0.40 * w * h:
            use_content = True
    basis_rect = content_bbox(elements) if use_content else [0.0, 0.0, float(w), float(h)]
    scale, bb = map_to_label(elements, template, basis_rect)
    decollided = decollide(elements, template)
    zpl, overflow = emit_zpl(elements, template)
    return {"image": os.path.basename(image_path), "size_px": [w, h], "frame": frame, "basis": basis,
            "frame_info": finfo, "template": template, "label_dots": list(G.dims(template)),
            "scale": scale, "content_bbox_px": [round(float(v), 1) for v in bb],
            "elements": elements, "dropped_ocr": [(l["text"], why) for l, why in dropped_ocr],
            "decollided": decollided, "overflow": overflow, "zpl": zpl}


def _printed_bottom(b, template):
    """条码**实印下缘**（点）。与 emit_zpl 的锚点算法同源：一维码 = 顶 + 条高；
    二维码 = 顶 + 黑模块高 + 4 个模块 quiet zone（框底 dots[3] 因此会明显偏小）。"""
    p = choose_barcode_params(dict(b), template, 1.0)
    top = G.round_half(b["dots"][1])
    if b["kind"] == "barcode2d":
        return top + G.anchor_dy("barcode2d", black_h=p["black_h"], magnification=p["magnification"])
    return top + G.anchor_dy("barcode1d", bar_height=p["bar_height"])


def decollide(elements, template=G.DEFAULT_TEMPLATE):
    """文字与条码的净空（与 Kotlin LabelPipeline.deCollide 1:1）。

    源图里"条码下方的人眼可读串"本来就只离条码 3~4 点（0.4mm），映射到点阵后再加上 ^FT
    的"下边缘锚定"，文字就贴到条上（用户报"重叠"）。只向下推文字，不动条码、不改横向位置。
    """
    W, H = G.dims(template)
    bars = [e for e in elements if e["kind"] in ("barcode1d", "barcode2d")]
    if not bars:
        return []
    notes = []
    for e in elements:
        if e["kind"] != "text":
            continue
        shift = 0
        for b in bars:
            hov = min(b["dots"][2], e["dots"][2]) - max(b["dots"][0], e["dots"][0])
            if hov <= 0.2 * (e["dots"][2] - e["dots"][0]):
                continue
            # 条码实印下缘（不是框底）：二维码的 ^FT 锚点含 4 个模块 quiet zone
            bb = _printed_bottom(b, template)
            # 整行都在条码上方 -> 不管；压在条码里或在其下方 -> 都要让出净空
            if e["dots"][3] <= b["dots"][1] + 0.5:
                continue
            # 二维码多留 1mm（QR 的 quiet zone 只有 4 个模块，被文字蹭掉就锁不住定位图）
            gap = G.QR_TEXT_GAP if b["kind"] == "barcode2d" else G.MIN_TEXT_BARCODE_GAP
            need = bb + gap
            if e["dots"][1] < need:
                shift = max(shift, int(need - e["dots"][1]) + 1)
        if shift <= 0:
            continue
        room = int(H - G.GUARD - e["dots"][3])
        use = min(shift, max(0, room), G.DECOLLIDE_STEP_LIMIT)
        if use <= 0:
            notes.append((e["text"], "tight to barcode, no room below"))
            continue
        e["dots"] = [e["dots"][0], e["dots"][1] + use, e["dots"][2], e["dots"][3] + use]
        notes.append((e["text"], f"moved down {use} dots for clearance"))
    return notes


def _jsonable(o):
    if isinstance(o, np.ndarray):
        return [[round(float(v), 2) for v in row] for row in o]
    if isinstance(o, (np.floating, np.integer)):
        return float(o)
    return str(o)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--template", default=str(G.DEFAULT_TEMPLATE),
                    help="模板号，或 auto = 按标签长宽比自动选（与安卓端 templateForAspect 同规则）")
    ap.add_argument("--frame", default="auto", choices=["auto", "label", "full", "content"],
                    help="pixel->dot basis: label quad / whole photo / element bbox")
    ap.add_argument("--out", default=None)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--no-ocr", action="store_true", help="只跑条码（用于和安卓端 Stage A 逐字节比对）")
    ap.add_argument("--dpi", type=int, default=G.DPI, choices=[203, 300, 600],
                    help="打印分辨率（203=8点/mm、300、600）。换机型时整张标签的点阵尺寸/字号/模块宽都变")
    args = ap.parse_args()

    G.set_dpi(args.dpi)

    if args.template == "auto":
        # 长宽比必须来自**标签**（子图），不是照片：照片比例里混进了背景
        import cv2
        import label_frame
        src = cv2.imread(args.image)
        if src is None:
            raise SystemExit(f"cannot read {args.image}")
        bgr, _, _ = label_frame.prepare(src, args.frame)
        tpl = G.template_for_aspect(bgr.shape[1], bgr.shape[0])
        print(f"== auto 选模板{tpl}（标签 {bgr.shape[1]}x{bgr.shape[0]}，"
              f"长宽比 {bgr.shape[1] / bgr.shape[0]:.2f}）")
    else:
        tpl = int(args.template)

    det = rec = None
    if not args.no_ocr:
        sys.path.insert(0, "E:/workspace/paddleocr-android-app/tools")
        import ppocr_ref
        det, rec = ppocr_ref.load()
    res = build(args.image, tpl, det, rec, args.frame)

    outdir = args.out or os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "out")
    os.makedirs(outdir, exist_ok=True)
    stem = os.path.splitext(os.path.basename(args.image))[0]
    with open(os.path.join(outdir, stem + ".zpl"), "w", encoding="utf-8", newline="") as f:
        f.write(res["zpl"])
    with open(os.path.join(outdir, stem + ".elements.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=2, default=_jsonable)

    print(f"== {res['image']}  {res['size_px'][0]}x{res['size_px'][1]}px basis={res['basis']} -> 模板{res['template']} "
          f"{res['label_dots'][0]}x{res['label_dots'][1]}dots  scale={res['scale']:.3f}")
    for e in res["elements"]:
        print(f"   {e['kind']:10s} {str(e.get('format','')):12s} {e['text']!r:14s} "
              f"px={[round(v) for v in e['px']]} dots={[round(v, 1) for v in e['dots']]}")
    if res["dropped_ocr"]:
        print("   dropped OCR:", res["dropped_ocr"])
    if res["overflow"]:
        print("   !! OVERFLOW (would exceed label height):", res["overflow"])
    print("-- ZPL --")
    print(res["zpl"].replace("\r\n", "\n").strip())
    if args.json:
        print(json.dumps(res, ensure_ascii=False, default=_jsonable))


if __name__ == "__main__":
    main()
