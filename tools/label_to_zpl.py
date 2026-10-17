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
            if _norm(ln["text"]) != _norm(b["text"]):
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
        n = max(1, len(text))
        modules = (G.code128_modules(n, text.isdigit())
                   if "128" in e["format"] else G.code39_modules(n))
        derived = e["w"] / modules
        # 低于可印下限不能照抄：实测 ^BY1（1 点 = 0.125mm）的 Code128 印成一团糊，
        # 扫描器测不准条宽就退化判成 Code 39（用户实际遇到的现象）。见 zpl_geometry.MIN_MODULE_DOTS。
        module_w = max(G.MIN_MODULE_DOTS, int(round(min(derived, 10.0) * 2)) / 2.0)
        bar_h = int(round(e["h"]))
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
        mag = int(np.clip(round(e["w"] / modules), 2, 10))
        while mag > 2 and modules * mag > usable_w:
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
            anchor = top + G.anchor_dy("barcode1d", bar_height=p["bar_height"])
            field = ("^BY{0},{1},{2}^FT{3},{4}^BCN,{2},{5},N,N".format(
                _f(p["module_width"]), _f(2.5), p["bar_height"], x, anchor, "Y" if e.get("hri") else "N"))
            field += f"^FD{e['text']}^FS"
            bottom = anchor
        elif e["kind"] == "barcode2d":
            p = choose_barcode_params(e, template, 1.0)
            anchor = top + G.anchor_dy("barcode2d", black_h=p["black_h"], magnification=p["magnification"])
            field = f"^FT{x},{anchor}^BQN,2,{p['magnification']}^FDQA,{e['text']}^FS"
            bottom = anchor
        else:
            maxw = max(40, int(W - G.MARGIN - x))
            budget_w = max(20.0, min(float(maxw), e["w"] * 1.10))
            fs = G.font_size_for(budget_w, e["h"], e["text"])
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
        if bottom > H - G.GUARD:
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
    elements = barcodes + texts
    for e in elements:
        e.setdefault("hri", False)
    h, w = bgr.shape[:2]
    basis_rect = ([0.0, 0.0, float(w), float(h)] if frame != "content"
                  else content_bbox(elements))
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
    bars = [e for e in elements if e["kind"] != "text"]
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
    args = ap.parse_args()

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
