"""One command for the whole PC chain: samples/*.png -> out/*.zpl -> validate -> render -> fidelity.

Usage: python demo.py [--dir samples] [--template 1] [--frame full]
"""
import argparse, glob, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zpl_geometry as G

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join(ROOT, "samples"))
    ap.add_argument("--template", type=int, default=G.DEFAULT_TEMPLATE)
    ap.add_argument("--frame", default="full", choices=["auto", "label", "full", "content"])
    ap.add_argument("--dpi", type=int, default=G.DPI, choices=[203, 300, 600])
    args = ap.parse_args()
    G.set_dpi(args.dpi)

    sys.path.insert(0, "E:/workspace/paddleocr-android-app/tools")
    import ppocr_ref
    import label_to_zpl, validate_zpl, fidelity  # noqa
    det, rec = ppocr_ref.load()

    outdir = os.path.join(ROOT, "out")
    shots = os.path.join(ROOT, "shots")
    os.makedirs(outdir, exist_ok=True)

    rows = []
    for img in sorted(glob.glob(os.path.join(args.dir, "*.png"))):
        res = label_to_zpl.build(img, args.template, det, rec, args.frame)
        stem = os.path.splitext(os.path.basename(img))[0]
        zpl_path = os.path.join(outdir, stem + ".zpl")
        with open(zpl_path, "w", encoding="utf-8", newline="") as f:
            f.write(res["zpl"])
        import json
        with open(os.path.join(outdir, stem + ".elements.json"), "w", encoding="utf-8") as f:
            json.dump(res, f, ensure_ascii=False, indent=2, default=label_to_zpl._jsonable)
        errs, warns = validate_zpl.check(zpl_path, args.template)
        # render + compare + element drift
        from render_check import render, side_by_side
        t = G.TEMPLATES[args.template]
        rendered = render(res["zpl"], (t["w_mm"], t["h_mm"]), 8)
        cv2 = __import__("cv2")
        cv2.imwrite(os.path.join(shots, stem + ".compare.png"),
                    side_by_side(cv2.imread(img), rendered))
        rows.append((stem, len(res["elements"]), res["dropped_ocr"], errs, warns, res["overflow"]))

    print("\n=== summary ===")
    for stem, n, dropped, errs, warns, over in rows:
        print(f"  {stem:18s} elements={n} dropped_ocr={len(dropped)} "
              f"zpl_errors={len(errs)} warnings={len(warns)} overflow={len(over)}")
    print(f"artifacts: {outdir}\\*.zpl , shots\\*.compare.png")


if __name__ == "__main__":
    main()
