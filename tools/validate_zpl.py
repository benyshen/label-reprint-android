"""Machine validation of a generated ZPL file — run this on every change.

Checks the failure modes that print silently wrong (or not at all) on paper:
  * unbalanced ^XA/^XZ, ^FD/^FS
  * ^PW/^LL not matching the template, or coordinates outside the label
    (an out-of-range ^FO/^FT y is SILENTLY DROPPED by the printer)
  * a 1D barcode whose module width x symbol width exceeds the usable width
  * ^MM/^PR/^MD present (media/speed/darkness must stay printer settings, not app settings)

Usage: python validate_zpl.py out/*.zpl --template 1
"""
import argparse, glob, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zpl_geometry as G


def check(path, template):
    txt = open(path, encoding="utf-8").read()
    W, H = G.dims(template)
    errs, warns = [], []

    if txt.count("^XA") != 1 or txt.count("^XZ") != 1:
        errs.append(f"^XA/^XZ: {txt.count('^XA')}/{txt.count('^XZ')}")
    if "^CI28" not in txt:
        warns.append("no ^CI28 (non-ASCII will be garbled)")
    for bad in ("^MMT", "^MMB", "^MNY", "^PR", "^MD"):
        if bad in txt:
            errs.append(f"{bad} present - media/speed/darkness must not be sent by the app")
    fd, fs = txt.count("^FD"), txt.count("^FS")
    if fd != fs:
        errs.append(f"^FD({fd}) != ^FS({fs})")

    pw = re.search(r"\^PW(\d+)", txt)
    ll = re.search(r"\^LL(\d+)", txt)
    if not pw or int(pw.group(1)) != W:
        errs.append(f"^PW {pw.group(1) if pw else '-'} != template {W}")
    if not ll or int(ll.group(1)) != H:
        errs.append(f"^LL {ll.group(1) if ll else '-'} != template {H}")

    for m in re.finditer(r"\^FT(\d+),(\d+)", txt):
        x, y = int(m.group(1)), int(m.group(2))
        if not (0 <= x < W):
            errs.append(f"^FT x={x} outside 0..{W - 1}")
        if not (0 <= y < H):
            errs.append(f"^FT y={y} outside 0..{H - 1}")

    # 1D barcode width: ^BY w,r,h ... ^BC^FD<text>
    for m in re.finditer(r"\^BY([\d.]+),[\d.]+,\d+\^FT(\d+),\d+\^BC(\w),(\d+),([YN]),[YN],[YN]\^FD([^^]*)", txt):
        mod, x, _, _, _, data = float(m.group(1)), int(m.group(2)), m.group(3), m.group(4), m.group(5), m.group(6)
        modules = (G.code128_modules(len(data), data.isdigit()) if m.group(3).upper() == "N"
                   else G.code39_modules(len(data)))
        est = modules * mod
        if x + est > W:
            errs.append(f"barcode {data!r} est. width {est:.0f} + x{x} > {W}")
        elif x + est > W - G.MARGIN:
            warns.append(f"barcode {data!r} ends within {G.MARGIN} dots of the right edge")
    return errs, warns


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--template", type=int, default=G.DEFAULT_TEMPLATE)
    ap.add_argument("--dpi", type=int, default=G.DPI, choices=[203, 300, 600])
    args = ap.parse_args()
    G.set_dpi(args.dpi)
    total = 0
    for pattern in args.files:
        for path in sorted(glob.glob(pattern)):
            errs, warns = check(path, args.template)
            status = "FAIL" if errs else ("warn" if warns else "ok")
            print(f"[{status:4s}] {path}")
            for e in errs:
                print(f"        ERROR {e}")
            for w in warns:
                print(f"        warn  {w}")
            total += len(errs)
    print("errors:", total)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
