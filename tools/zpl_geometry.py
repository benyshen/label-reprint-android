"""Label / ZPL geometry — SINGLE SOURCE OF TRUTH.

The Kotlin port (android-app/.../zpl/ZplGeometry.kt) must mirror these constants 1:1.
Every value here was either measured on paper or is a datasheet capacity table;
do not "improve" one of them alone or the two ends drift apart.

Anchor semantics (measured on a TC73 + ZD421 pair, see engineering skill
`android-voice-zpl-printing`): a field's anchor point is NOT its top-left corner.
`anchor_y = top_y + offset(kind)`, with the offsets below.
"""

import math

MM_PER_INCH = 25.4

# --- 打印分辨率：所有"点"都是它的函数 -------------------------------------
# 现场同时有 203 / 300 / 600dpi 的 Zebra 机（ZR668=203、ZT231/ZD421=203 或 300、
# ZT610/ZE500=600）。ZPL 坐标/字号/条码模块宽全以**点**为单位，换个 dpi 机器不改换算，
# 整张标签就会缩成 2/3 或 1/3 大 —— 所以物理量一律用 mm 声明，出点阵时才乘 dpi。
DPI = 203

#: 所有"点"常量背后的物理尺寸（mm）。改这里等于改纸面实际留白/线宽。
MARGIN_MM = 1.5            # 四边留白（203dpi → 12 点）
TOP_OFFSET_MM = 0.75       # 整体下移（→ 6 点）
GUARD_MM = 0.75            # 底部保护带（→ 6 点）
MIN_TEXT_BARCODE_GAP_MM = 0.75   # 文字与条码净空（→ 6 点）
MIN_MODULE_MM = 0.25       # 一维码模块宽下限（→ 2 点；再细就扫不出）
TEXT_BASELINE_FIX_MM = 1.5 # 文字基线校正（→ 12 点）
BOX_THICKNESS_MM = 0.63    # 框线厚度（→ 5 点）
MIN_FONT_MM = 1.0          # 最小字号（→ 8 点）
DECOLLIDE_LIMIT_MM = 5.0   # 文字避让条码的最大下移量（→ 40 点）
LINE_MERGE_MM = 0.75       # 同一根框线的合并容差（→ 6 点）
QR_MIN_MODULE_MM = 0.25    # QR 放大倍数下限（每模块，→ mag 2）
QR_MAX_MODULE_MM = 1.25    # QR 放大倍数上限（每模块，→ mag 10）

# --- templates: the only place label sizes live -----------------------------
TEMPLATES = {
    1: {"name": "模板1", "w_mm": 60.0, "h_mm": 40.0, "dpi": DPI},
    2: {"name": "模板2", "w_mm": 76.0, "h_mm": 70.0, "dpi": DPI},   # 整张纸 76x70mm（实测）
    3: {"name": "模板3", "w_mm": 70.0, "h_mm": 100.0, "dpi": DPI},
}
DEFAULT_TEMPLATE = 1


def set_dpi(dpi):
    """切换打印分辨率（203/300/600），并把所有 mm 常量重算成点。

    为什么用模块级变量而不是给每个函数加参数：调用点有二十几处（builder、
    pipeline、validator），逐个传 dpi 必漏；而漏一处就是那张标签的留白/线宽错。
    调用方一律写 `G.MARGIN`（属性查找发生在调用时），set_dpi 之后自动是新值。
    """
    global DPI, MIN_MODULE_DOTS, MIN_TEXT_BARCODE_GAP, QR_TEXT_GAP, MARGIN, TOP_OFFSET, GUARD
    global TEXT_BASELINE_FIX, DECOLLIDE_STEP_LIMIT, BOX_THICKNESS_DOTS, MIN_FONT_DOTS
    global LINE_MERGE_DOTS, QR_MIN_MAG, QR_MAX_MAG
    d = int(dpi)
    if d not in (203, 300, 600):
        d = 203
    DPI = d
    for t in TEMPLATES.values():
        t["dpi"] = d
    MIN_MODULE_DOTS = float(mm2dots(MIN_MODULE_MM, d))
    MIN_TEXT_BARCODE_GAP = mm2dots(MIN_TEXT_BARCODE_GAP_MM, d)
    QR_TEXT_GAP = MIN_TEXT_BARCODE_GAP + mm2dots(QR_EXTRA_GAP_MM, d)
    MARGIN = mm2dots(MARGIN_MM, d)
    TOP_OFFSET = mm2dots(TOP_OFFSET_MM, d)
    GUARD = mm2dots(GUARD_MM, d)
    TEXT_BASELINE_FIX = mm2dots(TEXT_BASELINE_FIX_MM, d)
    DECOLLIDE_STEP_LIMIT = mm2dots(DECOLLIDE_LIMIT_MM, d)
    BOX_THICKNESS_DOTS = mm2dots(BOX_THICKNESS_MM, d)
    MIN_FONT_DOTS = mm2dots(MIN_FONT_MM, d)
    LINE_MERGE_DOTS = float(mm2dots(LINE_MERGE_MM, d))
    QR_MIN_MAG = max(2, mm2dots(QR_MIN_MODULE_MM, d))
    QR_MAX_MAG = max(QR_MIN_MAG + 1, mm2dots(QR_MAX_MODULE_MM, d))
    return d


def current_dpi():
    return DPI


#: 一维码模块宽的**默认值**（点，203dpi）。真值由 set_dpi() 按 MIN_MODULE_MM 重算，
#: 这里只是 import 时就存在的占位。实测 ^BY1（1 点 = 0.125mm @203dpi）的 Code128 会印糊，
#: 扫描器测不准条宽就退化判成 Code 39 —— 我们的 ZPL 一直是 ^BC，所以这是印不出来导致的。
MIN_MODULE_DOTS = 2.0

#: 文字与条码的最小净空（默认 203dpi 值，真值由 set_dpi 重算）。实测：条码下方的人眼可读串
#: 离条码只有 3~4 点（0.4mm），映射到点阵后加上"下边缘锚定"就会贴到条上（用户报"重叠"）。
MIN_TEXT_BARCODE_GAP = 6
#: 二维码（QR）额外净空（mm）：QR 的 quiet zone 只有自身 4 个模块，印出来再被下方文字蹭一下
#: 就锁不住定位图。一维码的 HRI 反而该贴条，所以只给二维码加。
QR_EXTRA_GAP_MM = 1.0
DECOLLIDE_STEP_LIMIT = 40


def template_for_aspect(w_px, h_px):
    """按源图长宽比自动选模板（与 Kotlin ZplGeometry.templateForAspect 1:1）。

    为什么需要：照片里量不出标签的物理尺寸，但长宽比是尺度无关的。
    实测（用户拍的 70×70mm 校准标签，源图 529×464 → 1.14）：用默认模板1(60×40 → 1.5)
    会把整张标签纵向压到 57%，偏差从顶部 −16 点线性放大到底部 −200 点，
    越靠下的要素错得越狠（小二维码最明显）。换成长宽比最接近的模板2 后偏差收敛到 +25…−4 点。
    用 log 距离而不是差：长宽比是比例量。
    """
    if not w_px or not h_px:
        return DEFAULT_TEMPLATE
    img_ar = float(w_px) / float(h_px)
    return min(TEMPLATES, key=lambda k: abs(math.log(img_ar / (TEMPLATES[k]["w_mm"] / TEMPLATES[k]["h_mm"]))))


def round_half(v):
    """Half-up rounding. Python's built-in round() is banker's rounding, which differs from
    Kotlin's Math.round() exactly on .5 boundaries -> a 1-dot drift between the two ends."""
    return int(v + 0.5) if v >= 0 else -int(-v + 0.5)


def mm2dots(mm, dpi=None):
    """mm -> dots. dpi 省略时用当前 DPI（模块级，由 set_dpi 维护）。

    别在这里写 `dpi=203` 这种字面默认值：那样所有"按 mm 算点"的常量都会永远按 203 算，
    300dpi 的机器上留白/净空全错（实测：留白仍是 12 点而不是 18 点）。
    """
    return round_half(mm * (DPI if dpi is None else dpi) / MM_PER_INCH)


#: 二维码与下方要素的净空（点）= 通用净空 + 1mm（203dpi → 14 点 ≈ 1.75mm）
QR_TEXT_GAP = MIN_TEXT_BARCODE_GAP + mm2dots(QR_EXTRA_GAP_MM)


def dims(template=DEFAULT_TEMPLATE):
    t = TEMPLATES[template]
    return mm2dots(t["w_mm"], t["dpi"]), mm2dots(t["h_mm"], t["dpi"])


MARGIN = 12          # dots kept clear on every side   (= MARGIN_MM @当前 dpi)
TOP_OFFSET = 6       # dots pushed down so content never sits on the label edge
GUARD = 6            # dots of vertical guard for the overflow check
BOX_THICKNESS_DOTS = 5
MIN_FONT_DOTS = 8
LINE_MERGE_DOTS = 6.0
QR_MIN_MAG = 2
QR_MAX_MAG = 10

# 用 mm 常量把上面这些点值重算一遍（203 下与历史值逐字节相同），
# 之后只要调 set_dpi(300/600) 就全部跟着变。
set_dpi(DPI)

# --- text metrics (measured against the real renderer, see tools/calib_text.py) ---
# ^A0N,size,size -> printed cap height = 0.775 * size   (20/40/80/120 dots all fit this)
# ^A@N,size,size -> same convention
FONT_CAP_RATIO = 0.775
# ^A@ + SimSun: 汉字字高 ≈ 字号本身（0.775 是 ^A0N 内置字体的比值）
CAP_RATIO_CJK = 1.0
#: 印出来的墨迹宽 / 推进宽（zpl-core 实测）。照片里量到的是墨迹宽，反推字号要除回来。
WIDTH_SLACK = 0.92
FONT_GAIN = 1.15   # 字号整体补偿：实测整版偏小一号
# 汉字/全角标点的推进宽度 = 1 em；ASCII 约 0.5 em
CHAR_W_CJK = 1.0
# average advance width per character per size for ^A0N (proportional font).
CHAR_W_DEFAULT = 0.51
CHAR_W_NARROW = {"i": 0.24, "l": 0.24, "j": 0.24, "t": 0.31, "f": 0.28, "r": 0.35,
                 "1": 0.26, "I": 0.24, ".": 0.25, ",": 0.25, " ": 0.26}
CHAR_W_WIDE = {"W": 0.79, "M": 0.72, "m": 0.72, "w": 0.68}


def char_width(ch):
    """单个字符的推进宽度（em）。汉字/全角标点占满一格，ASCII 约半格。"""
    if ord(ch) > 0x2E80:
        return CHAR_W_CJK
    return CHAR_W_WIDE.get(ch, CHAR_W_NARROW.get(ch, CHAR_W_DEFAULT))


def text_width(text, font_size):
    """estimated printed width in dots of `text` at `font_size` (^A0N / ^A@N)."""
    total = 0.0
    for ch in text:
        total += char_width(ch)
    return total * font_size


def font_size_for(box_w, box_h, text):
    """largest ^A0N size that keeps `text` inside box_w x box_h; height wins, width caps it.

    汉字走 ^A@ + SimSun，字高≈字号（CAP_RATIO_CJK=1.0）。用 0.775 会把字号放大 35%，
    再叠加"汉字按 0.51 em 算宽"的低估，实测 31 字符中文行被排成 26 号（需要 858 点、
    字段只有 416 点）-> 打印机把字符挤着叠印，纸上就是"条码下方文字重合"。
    """
    cap = CAP_RATIO_CJK if any(ord(c) > 0x2E80 for c in text) else FONT_CAP_RATIO
    fs_h = box_h / cap
    fs_w = box_w / max(1e-6, text_width(text, 1.0))
    # 实测整版字号统一小一号（12~15%）：双约束取 min 后偏保守，统一放大 1.15
    return max(MIN_FONT_DOTS, int(round(min(fs_h, fs_w) * FONT_GAIN)))


# --- anchor offsets ---------------------------------------------------------
def anchor_dy(kind, **kw):
    """offset from the element's TOP pixel row to the ZPL field anchor row."""
    if kind == "text":                     # always emitted with ^FB
        # +12 点 = 1.5mm @203dpi：原式 lines×字号 会让整版文字偏高（真机实测），
        # 只影响文字，不动框线/条码。
        return kw.get("lines", 1) * kw["font_size"] + TEXT_BASELINE_FIX
    if kind == "barcode1d":
        return kw["bar_height"]
    if kind == "barcode2d":
        return kw["black_h"] + 4 * kw["magnification"]
    raise ValueError(kind)


# --- symbology capacities ---------------------------------------------------
TEXT_BASELINE_FIX = 12   # 文字基线校正：1.5mm，真机实测偏高（set_dpi 会按 TEXT_BASELINE_FIX_MM 重算）


def code128_modules(n_chars, digits_only=False):
    """11 × 符号数 + 35（起始 + 校验 + 终止）。

    纯数字要按 Code128C 算：两位数字压一个符号，符号数 = (n+1)//2。
    教训：20 位纯数字按"一位一符号"算成 255 模块 -> 模块宽估 0.44 点 -> 被放不下规则压到 1.8 点，
    打出来照样糊；按 C 码算只有 145 模块，2 点模块宽 = 290 点，标签装得下。
    """
    symbols = (n_chars + 1) // 2 if (digits_only and n_chars >= 4) else n_chars
    return 11 * symbols + 35


def code128_char_capacity(usable_width, module_width):
    return int((usable_width / module_width - 35) / 11)


def code128_payload_ok(text):
    # Code128 encodes all of printable ASCII (0x20-0x7E)
    return all(0x20 <= ord(c) <= 0x7E for c in text)


def code39_payload_ok(text):
    return all(c.isdigit() or (c.isalpha() and c.isupper()) or c in "-.$/+% " for c in text)


# QR version -> (modules, capacity at EC level Q) for alphanumeric / byte
QR_CAPACITY_Q = [
    (21, 29, 20),    # v1
    (25, 47, 32),    # v2
    (29, 67, 46),    # v3
    (33, 87, 60),    # v4
    (37, 107, 74),   # v5
    (41, 128, 86),   # v6
    (45, 154, 108),  # v7
    (49, 180, 130),  # v8
    (53, 205, 151),  # v9
    (57, 230, 177),  # v10
]


def qr_modules_for_text(text):
    """Smallest QR version that fits `text` at EC level Q (^BQ default), plus its module count."""
    for modules, alnum_cap, byte_cap in QR_CAPACITY_Q:
        cap = alnum_cap if text.isalnum() and text.isascii() else byte_cap
        if len(text) <= cap:
            return modules
    return QR_CAPACITY_Q[-1][0]


def code39_modules(n_chars):
    """Code 39: start/stop + chars, 13 modules per char incl. narrow inter-char gap, + 1 bar."""
    return 16 * (n_chars + 2) + 1


# ---------------------------------------------------------------------------
# 1D symbology: which ^B command to emit, and how wide the symbol really is
# ---------------------------------------------------------------------------
# zxing（Android 与 Python 两端）报告的 format 名 -> 内部码制键。
# 教训：V1.9 不看码制，界面上量到什么内容一律发 ^BC（Code128），
# 结果 EAN-13 的内容被印成 Code128 —— 纸面 HRI 分组变成 `934024"512123`，
# 扫描器回读报 Code128 而不是 EAN-13。
SYMBOLOGY = {
    "EAN-13": "ean13", "EAN13": "ean13", "EAN_13": "ean13",
    "EAN-8": "ean8", "EAN8": "ean8", "EAN_8": "ean8",
    "UPC-A": "upca", "UPCA": "upca", "UPC_A": "upca",
    "UPC-E": "upce", "UPCE": "upce", "UPC_E": "upce",
    "ITF": "i2of5", "I2OF5": "i2of5", "I2O5": "i2of5", "ITF-14": "i2of5", "ITF14": "i2of5",
    "Code 128": "code128", "Code128": "code128", "CODE128": "code128", "CODE_128": "code128",
    "Code 39": "code39", "Code39": "code39", "CODE39": "code39", "CODE_39": "code39",
    "CODE_39_EXTENDED": "code39", "Code 39 Extended": "code39",
    "Code 93": "code93", "Code93": "code93", "CODE93": "code93", "CODE_93": "code93",
    "Codabar": "codabar", "CODABAR": "codabar",
}

#: 固定模块数的零售码。zpl-core photo.py:_FIXED_MODULES —— 通用估算给 EAN-13 算成 178 模块，
#: 模块宽被砍一半，打出来比原码窄 10mm（用户拍的原标签 vs 复刻件最明显的差别之一）。
FIXED_MODULES = {"ean13": 95, "upca": 95, "ean8": 67, "upce": 51}

#: 码制 -> ZPL 命令（zpl-core builder.py:BARCODE_SPECS，按手册 Format 行核对过）
BARCODE_CMD = {"ean13": "^BE", "ean8": "^B8", "upca": "^B9", "upce": "^B0",
               "i2of5": "^B2", "code128": "^BC", "code39": "^B3", "code93": "^BA",
               "codabar": "^BK"}


def symbology(format_name):
    """zxing format 名 -> 码制键。认不出的 1D 码按 code128 发（V1.9 的老行为，至少能扫）。"""
    return SYMBOLOGY.get(str(format_name or "").strip(), "code128")


def barcode_modules(sym, text):
    """符号总模块数。固定宽度的零售码必须用真值，其余按结构公式估（只影响模块宽推算）。"""
    n = max(1, len(text or ""))
    if sym in FIXED_MODULES:
        return FIXED_MODULES[sym]
    if sym == "code128":
        return code128_modules(n, (text or "").isdigit())
    if sym == "code39":
        return code39_modules(n)
    if sym == "code93":
        return 9 * n + 28          # 9 模块/字符 + 起始/校验/终止 + 终止条
    if sym == "i2of5":
        return 8 * n + 9           # 每对数字 16 模块（ratio 2.5）+ 起始/终止
    if sym == "codabar":
        return 11 * n + 21         # 每字符 10 模块 + 1 模块间隙 + 起止符
    return code128_modules(n, (text or "").isdigit())


def hri_forced(sym):
    """这类码制的明文行（HRI）是符号的一部分：EAN/UPC/ITF 规范要求印出来。"""
    return sym in ("ean13", "ean8", "upca", "upce", "i2of5")


def _fmt_num(v):
    return str(int(v)) if float(v) == int(v) else str(v)


def barcode_1d_field(sym, x, anchor, module_width, bar_height, interp):
    """^BY + ^FT + 码制命令 + 明文行开关。参数顺序按各命令手册的 Format 行，两端逐字节一致。"""
    mw, bh = _fmt_num(module_width), int(bar_height)
    f = "Y" if interp else "N"
    head = f"^BY{mw},2.5,{bh}^FT{x},{anchor}"
    if sym == "ean13":
        return f"{head}^BEN,{bh},{f},N"          # ^BE o,h,f,g
    if sym == "ean8":
        return f"{head}^B8N,{bh},{f},N"          # ^B8 o,h,f,g
    if sym == "upca":
        return f"{head}^B9N,{bh},{f},N,N"        # ^B9 o,h,f,g,e
    if sym == "upce":
        return f"{head}^B0N,N,{bh},{f},N"        # ^B0 o,e,h,f,g
    if sym == "i2of5":
        return f"{head}^B2N,{bh},{f},N,N,N"      # ^B2 o,h,f,g,e,j
    if sym == "code39":
        return f"{head}^B3N,N,{bh},{f},N"        # ^B3 o,e,h,f,g
    if sym == "code93":
        return f"{head}^BAN,{bh},{f},N,N"        # ^BA o,h,f,g,e
    if sym == "codabar":
        return f"{head}^BKN,N,{bh},{f},N,A,A"    # ^BK o,e,h,f,g,k,l
    return f"{head}^BCN,{bh},{f},N,N"            # ^BC o,h,f,g,e

