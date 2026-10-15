"""Photo -> "which pixels are the label?" and the pixel->dot basis.

Three bases, because "photo pixel -> label dot" has no unique answer without one:
  label  : find the label's bright quad in the scene, rectify it, map THAT to the label area.
           Correct when the label is photographed lying on a dark surface/table.
  full   : the photo is already cropped to the label edge -> map the whole image.
  content: map the union bbox of all detected elements -> fills the label, loses white space.

`auto` tries `label`, falls back to `full`. Never silently uses `content` (it changes
absolute sizes, i.e. it is the one basis that is *guaranteed* to move your elements).
"""
import cv2
import numpy as np


def detect_label_quad(bgr, min_area_frac=0.20):
    """largest bright / dark 4-gon enclosing >= min_area_frac of the frame, or None."""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)
    h, w = gray.shape
    frame_area = float(h * w)
    cands = []
    for invert in (False, True):
        # 注意：这里**不要**再套一层 bitwise_not。老代码写成 THRESH_BINARY_INV + bitwise_not，
        # 两次取反正好抵消 = 从没真正处理过"暗底白标签"这一路，所以安卓端照着意图正确实现时
        # 才暴露出"深色桌面被当成标签"的坑。
        flags = cv2.THRESH_BINARY_INV if invert else cv2.THRESH_BINARY
        _, th = cv2.threshold(gray, 0, 255, flags | cv2.THRESH_OTSU)
        th = cv2.morphologyEx(th, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))
        cnts, _ = cv2.findContours(th, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for c in cnts:
            a = cv2.contourArea(c)
            # 面积 ≥92% 画面的候选是背景本身（暗底场景下它比标签还大），不是标签；
            # 标签占 92% 以上意味着照片本来就裁到标签边，退回 full 等价 -> 直接丢不亏。
            if a < min_area_frac * frame_area or a >= 0.92 * frame_area:
                continue
            peri = cv2.arcLength(c, True)
            for eps in (0.02, 0.03, 0.05):
                approx = cv2.approxPolyDP(c, eps * peri, True)
                if len(approx) == 4 and cv2.isContourConvex(approx):
                    cands.append((a, approx.reshape(4, 2).astype(np.float32)))
                    break
    if not cands:
        return None
    cands.sort(key=lambda t: -t[0])
    quad = _order_quad(cands[0][1])
    # reject near-degenerate quads (a rotated rect must have a sane aspect)
    wpx = np.linalg.norm(quad[1] - quad[0]), np.linalg.norm(quad[2] - quad[3])
    hpx = np.linalg.norm(quad[3] - quad[0]), np.linalg.norm(quad[2] - quad[1])
    if min(np.mean(wpx), np.mean(hpx)) < 0.15 * max(np.mean(wpx), np.mean(hpx)):
        return None
    return quad


def _order_quad(q):
    """[tl, tr, br, bl]"""
    s = q.sum(1)
    d = np.diff(q, axis=1).ravel()
    return np.array([q[np.argmin(s)], q[np.argmin(d)], q[np.argmax(s)], q[np.argmax(d)]], np.float32)


def warp_quad(bgr, quad, long_side=1600):
    """rectify the label quad into an upright image; returns (warped, H, inv_H)."""
    tl, tr, br, bl = quad
    wpx = max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl))
    hpx = max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr))
    scale = long_side / max(wpx, hpx)
    W, H = int(round(wpx * scale)), int(round(hpx * scale))
    dst = np.float32([[0, 0], [W - 1, 0], [W - 1, H - 1], [0, H - 1]])
    M = cv2.getPerspectiveTransform(quad, dst)
    return cv2.warpPerspective(bgr, M, (W, H), flags=cv2.INTER_CUBIC), M, W, H


def prepare(bgr, mode="auto"):
    """-> (working_bgr, basis, info). basis in {'label','full'}."""
    if mode in ("auto", "label"):
        quad = detect_label_quad(bgr)
        if quad is not None:
            warped, M, W, H = warp_quad(bgr, quad)
            return warped, "label", {"quad": quad.round(1).tolist(), "warp_px": [W, H]}
        if mode == "label":
            raise SystemExit("no label quad found")
    return bgr, "full", {}


def fit_to_label(working_bbox_px, working_size_px, label_dots, margin, top_offset):
    """uniform scale + centring of a pixel rect basis into the printable area."""
    W, H = label_dots
    usable_w, usable_h = W - 2 * margin, H - 2 * margin - top_offset
    cw = max(1.0, working_bbox_px[2] - working_bbox_px[0])
    ch = max(1.0, working_bbox_px[3] - working_bbox_px[1])
    scale = min(usable_w / cw, usable_h / ch)
    dx = margin + (usable_w - cw * scale) / 2.0
    dy = top_offset + margin + (usable_h - ch * scale) / 2.0
    return scale, dx, dy
