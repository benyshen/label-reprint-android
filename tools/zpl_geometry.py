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

# --- templates: the only place label sizes live -----------------------------
TEMPLATES = {
    1: {"name": "模板1", "w_mm": 60.0, "h_mm": 40.0, "dpi": 203},
    2: {"name": "模板2", "w_mm": 70.0, "h_mm": 70.0, "dpi": 203},
    3: {"name": "模板3", "w_mm": 70.0, "h_mm": 100.0, "dpi": 203},
}
DEFAULT_TEMPLATE = 1

#: 一维码模块宽（点）下限 = 2。实测 ^BY1（1 点 = 0.125mm @203dpi）的 Code128 会印糊，
#: 扫描器测不准条宽就退化判成 Code 39 —— 我们的 ZPL 一直是 ^BC，所以这是印不出来导致的。
MIN_MODULE_DOTS = 2.0

#: 文字与条码的最小净空（点）。实测：条码下方的人眼可读串离条码只有 3~4 点（0.4mm），
#: 映射到点阵后加上"下边缘锚定"就会贴到条上（用户报"重叠"）。
MIN_TEXT_BARCODE_GAP = 6
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


def mm2dots(mm, dpi=203):
    return round_half(mm * dpi / MM_PER_INCH)


def dims(template=DEFAULT_TEMPLATE):
    t = TEMPLATES[template]
    return mm2dots(t["w_mm"], t["dpi"]), mm2dots(t["h_mm"], t["dpi"])


MARGIN = 12          # dots kept clear on every side
TOP_OFFSET = 6       # dots pushed down so content never sits on the label edge
GUARD = 6            # dots of vertical guard for the overflow check

# --- text metrics (measured against the real renderer, see tools/calib_text.py) ---
# ^A0N,size,size -> printed cap height = 0.775 * size   (20/40/80/120 dots all fit this)
# ^A@N,size,size -> same convention
FONT_CAP_RATIO = 0.775
# ^A@ + SimSun: 汉字字高 ≈ 字号本身（0.775 是 ^A0N 内置字体的比值）
CAP_RATIO_CJK = 1.0
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
    return max(8, int(round(min(fs_h, fs_w))))


# --- anchor offsets ---------------------------------------------------------
def anchor_dy(kind, **kw):
    """offset from the element's TOP pixel row to the ZPL field anchor row."""
    if kind == "text":                     # always emitted with ^FB
        return kw.get("lines", 1) * kw["font_size"]
    if kind == "barcode1d":
        return kw["bar_height"]
    if kind == "barcode2d":
        return kw["black_h"] + 4 * kw["magnification"]
    raise ValueError(kind)


# --- symbology capacities ---------------------------------------------------
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
