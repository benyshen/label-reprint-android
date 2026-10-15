"""Calibrate ^A0 (CG Triumvirate) and ^A@ (SIMSUN TTF) text metrics against the real renderer,
instead of guessing a per-character width factor."""
import os, sys, urllib.request
import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

LABELARY = "http://api.labelary.com/v1/printers/{dpmm}dpmm/labels/{size}/0/"


def render(zpl, size="8x4", dpmm=8):
    req = urllib.request.Request(LABELARY.format(dpmm=dpmm, size=size), data=zpl.encode(),
                                 headers={"Accept": "image/png"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return cv2.imdecode(np.frombuffer(r.read(), np.uint8), cv2.IMREAD_GRAYSCALE)


def ink_box(img):
    m = img < 128
    ys, xs = np.where(m)
    if len(xs) == 0:
        return None
    return xs.min(), ys.min(), xs.max(), ys.max()


def probe(text, size, font="^A0N", extra=""):
    zpl = f"^XA^PW640^LL320^CI28^FT20,20{font},{size},{size}{extra}^FD{text}^FS^XZ"
    img = render(zpl)
    b = ink_box(img)
    if b is None:
        return None
    x0, y0, x1, y1 = b
    return {"text": text, "size": size, "w": x1 - x0 + 1, "h": y1 - y0 + 1,
            "w_per_char_per_size": (x1 - x0 + 1) / (len(text) * size),
            "h_per_size": (y1 - y0 + 1) / size}


if __name__ == "__main__":
    print("== ^A0N (built-in scalable, no font file needed) ==")
    for size in (24, 40, 56):
        for t in ("NANJING", "ABC123", "WWWWWWWW", "iiiiiiii", "ZEBRA"):
            r = probe(t, size)
            print(f"  size={size:3d} {t:10s} w={r['w']:4d} h={r['h']:3d} "
                  f"w/char/size={r['w_per_char_per_size']:.3f} h/size={r['h_per_size']:.3f}")
    print("== ^A@N + E:SIMSUN.TTF (CJK path; Labelary has no such font -> expect failure) ==")
    for t in ("南京", "ABC"):
        try:
            print("  ", probe(t, 40, font="^A@N", extra=",E:SIMSUN.TTF"))
        except Exception as e:
            print("  ", t, "ERR", e)
