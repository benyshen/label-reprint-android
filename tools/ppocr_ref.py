"""Faithful port of PaddleOCR PP-OCRv4 det/rec postprocess (RapidOCR ONNX weights).
Reference implementation used to validate the Android/Kotlin port.
Usage: python ppocr_ref.py <image> [--dump-prefix out]
"""
import sys, math
import numpy as np, cv2, onnxruntime as ort

D = "E:/workspace/paddleocr-android-app/assets/models"
_KEYS = open(f"{D}/ppocr_keys_v1.txt", encoding="utf-8").read().split("\n")
KEYS = [k for k in _KEYS if k != ""]
# index 0 = blank, 1..N -> KEYS[i-1], last index -> space
CHARS = ["blank"] + KEYS + [" "]
assert len(CHARS) == 6625, len(CHARS)

DET_MEAN = np.array([0.485, 0.456, 0.406], np.float32)
DET_STD = np.array([0.229, 0.224, 0.225], np.float32)


def load():
    so = ort.SessionOptions(); so.intra_op_num_threads = 4
    det = ort.InferenceSession(f"{D}/ch_PP-OCRv4_det_infer.onnx", sess_options=so)
    rec = ort.InferenceSession(f"{D}/ch_PP-OCRv4_rec_infer.onnx", sess_options=so)
    return det, rec


def resize_to_limit(img, limit=736, stride=32, limit_type="min"):
    """PaddleOCR DetResizeForTest resize_image_type0/1 (limit_type min|max, pad to /32)."""
    h, w = img.shape[:2]
    ratio = 1.0
    if limit_type == "max":
        if max(h, w) > limit:
            ratio = limit / max(h, w)
    else:
        if min(h, w) < limit:
            ratio = limit / min(h, w)
    rh = max(int(round(h * ratio / stride) * stride), stride)
    rw = max(int(round(w * ratio / stride) * stride), stride)
    resized = cv2.resize(img, (rw, rh)).astype(np.float32)
    pad = np.zeros((rh, rw, 3), np.float32)
    pad[: rh, : rw] = resized
    return pad, ratio


def det_preprocess(img, limit=736, limit_type="min"):
    padded, ratio = resize_to_limit(np.asarray(img, np.float32), limit, limit_type=limit_type)
    n = ((padded / 255.0) - DET_MEAN) / DET_STD
    return n.transpose(2, 0, 1)[None].astype(np.float32), ratio


def get_mini_boxes(contour):
    """PaddleOCR DBPostProcess.get_mini_boxes"""
    bb = cv2.minAreaRect(contour)
    pts = sorted(list(cv2.boxPoints(bb)), key=lambda p: p[0])
    if pts[1][1] > pts[0][1]:
        i1, i4 = 0, 1
    else:
        i1, i4 = 1, 0
    if pts[3][1] > pts[2][1]:
        i2, i3 = 2, 3
    else:
        i2, i3 = 3, 2
    return np.array([pts[i1], pts[i2], pts[i3], pts[i4]], np.float32), min(bb[1])


def box_score(prob, box_points):
    """PaddleOCR box_score_fast: mean prob inside the polygon."""
    h, w = prob.shape[:2]
    xmin = int(np.clip(np.floor(box_points[:, 0].min()), 0, w - 1))
    xmax = int(np.clip(np.ceil(box_points[:, 0].max()), 0, w - 1))
    ymin = int(np.clip(np.floor(box_points[:, 1].min()), 0, h - 1))
    ymax = int(np.clip(np.ceil(box_points[:, 1].max()), 0, h - 1))
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), np.uint8)
    local = box_points - np.array([xmin, ymin])
    cv2.fillPoly(mask, [local.astype(np.int32)], 1)
    return float(cv2.mean(prob[ymin:ymax + 1, xmin:xmax + 1], mask=mask)[0])


def unclip(box, ratio=1.5):
    """PaddleOCR unclip via pyclipper offset (approximated by polygon offset)."""
    import pyclipper
    poly = [(float(p[0]), float(p[1])) for p in box]
    area = abs(pyclipper.Area(poly))
    p = np.asarray(poly, np.float64)
    length = float(np.linalg.norm(np.roll(p, -1, axis=0) - p, axis=1).sum())
    if length < 1e-6:
        return box
    distance = area * ratio / length
    off = pyclipper.PyclipperOffset()
    off.AddPath(poly, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
    return np.array(off.Execute(distance)[0], np.float32)


def detect(det, img, limit=736, limit_type="min"):
    inp, ratio = det_preprocess(img, limit, limit_type)
    print("  det input", inp.shape, "scale", round(1 / ratio, 3))
    prob = det.run(None, {"x": inp})[0][0, 0]
    mask = (prob > 0.3).astype(np.uint8)
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for c in cnts:
        if len(c) < 4:
            continue
        box, sside = get_mini_boxes(c)
        if sside < 3:
            continue
        if box_score(prob, box) < 0.5:
            continue
        box = unclip(box)
        box, sside = get_mini_boxes(box.reshape(-1, 1, 2))
        if sside < 5:
            continue
        boxes.append(box / ratio)
    return boxes


def get_rotate_crop_image(img, points):
    points = np.asarray(points, np.float32).reshape(-1, 2)
    w = int(max(np.linalg.norm(points[0] - points[1]), np.linalg.norm(points[2] - points[3])))
    h = int(max(np.linalg.norm(points[0] - points[3]), np.linalg.norm(points[1] - points[2])))
    w, h = max(w, 1), max(h, 1)
    dst = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    M = cv2.getPerspectiveTransform(points, dst)
    crop = cv2.warpPerspective(img, M, (w, h), borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC)
    if crop.shape[0] * 1.0 / crop.shape[1] >= 1.5:
        crop = np.rot90(crop)
    return crop


def resize_norm_img(img, max_wh_ratio=8.0, imgH=48, imgW=320):
    imgC = 3
    imgW = int(imgH * max_wh_ratio)
    h, w = img.shape[:2]
    ratio = w / float(h)
    resized_w = min(imgW, int(math.ceil(imgH * ratio)))
    resized = cv2.resize(img, (resized_w, imgH)).astype(np.float32)
    resized = ((resized / 255.0) - 0.5) / 0.5
    padded = np.zeros((imgH, imgW, imgC), np.float32)
    padded[:, :resized_w, :] = resized
    return padded.transpose(2, 0, 1)[None].astype(np.float32), imgW


def ctc_decode(probs):
    """probs: (T, num_classes) -> (text, mean_conf)"""
    idx = probs.argmax(1); conf = probs.max(1)
    out = ""; scores = []; prev = -1
    for i, c in zip(idx, conf):
        if i != 0 and i != prev:
            out += CHARS[i]
            scores.append(float(c))
        prev = i
    return out, (float(np.mean(scores)) if scores else 0.0)


def recognize(rec, img, boxes):
    crops = [get_rotate_crop_image(img, b) for b in boxes]
    ratios = [c.shape[1] / float(c.shape[0]) for c in crops] or [1.0]
    mwr = max(ratios)
    results = []
    for box, crop in zip(boxes, crops):
        inp, _ = resize_norm_img(crop, mwr)
        out = rec.run(None, {"x": inp})[0][0]
        text, conf = ctc_decode(out)
        results.append((box, text, conf))
    return results


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "test_text.png"
    img = cv2.imread(path)
    print("image", img.shape)
    det, rec = load()
    boxes = detect(det, img)
    print("det boxes:", len(boxes))
    res = recognize(rec, img, boxes)
    print("--- results ---")
    for box, text, conf in res:
        print(f"{text}\t{conf:.4f}\tbox={np.round(box, 1).tolist()}")
    # sort reading order: top-to-bottom, then left-to-right
    res.sort(key=lambda r: (round(r[0][:, 1].min() / 10), r[0][:, 0].min()))
    print("--- reading order ---")
    for _, text, conf in res:
        print(f"{text}\t{conf:.4f}")
    if len(sys.argv) > 3 and sys.argv[2] == "--dump":
        vis = img.copy()
        for box, text, conf in res:
            cv2.polylines(vis, [box.astype(np.int32)], True, (0, 200, 0), 2)
        cv2.imwrite(sys.argv[3], vis)
        print("wrote", sys.argv[3])


if __name__ == "__main__":
    main()
