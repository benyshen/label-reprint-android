"""Byte-for-byte regression: PC reference implementation vs the Android app.

Both ends must produce the SAME ZPL for the same input. "Looks the same" is not enough —
one character difference voids a whole batch of labels, so this diffs the exact bytes.

Stage A runs barcode-only (no OCR on either side): it isolates the geometry/geometry-encoding
half of the pipeline, which is the part most likely to drift between Python and Kotlin.

Usage: python device_zpl_check.py [--template 1] [--frame full]
"""
import argparse, os, re, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zpl_geometry as G
import label_to_zpl

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = "C:/Users/shenyf/AppData/Local/Android/Sdk/platform-tools/adb.exe"
PKG = "com.tiaomabang.labelreprint"
SAMPLES = ["label_sample_1.png", "label_sample_2.png", "label_sample_3.png"]


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")


def device_zpls(template, frame, wait=120, ocr=False):
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    # 值里有空格时整条 am start 要作为一个字符串交给设备 shell，否则 extra 只收到第一个词
    extra = "--es all 1 --es ocr 1" if ocr else "--es all 1"
    adb("shell", f"am start -n {PKG}/.MainActivity {extra}")
    # 必须轮询：混合引擎冷启要解压/初始化 DL 模型（实测 ready 9.4s），OCR 还要再建 3 个 ONNX
    # session（首次 ~40s）；固定 sleep 会在"还没有任何 ZPL"时就抓日志，报成"设备端没有输出"。
    import time
    t0 = time.time()
    log = ""
    while time.time() - t0 < wait:
        time.sleep(4)
        log = adb("logcat", "-d", "-s", "LABELREPRINT:V").stdout
        if len(re.findall(r"ZPL >>> \S+", log)) >= len(SAMPLES):
            break
    out, cur = {}, None
    for line in log.splitlines():
        m = re.search(r"ZPL >>> (\S+)", line)
        if m:
            cur = m.group(1)
            out[cur] = []
            continue
        if cur is not None:
            body = line.split("LABELREPRINT: ", 1)[-1]
            if body.startswith("^"):
                out[cur].append(body.rstrip("\r"))
            elif body.strip():
                cur = None
    return {k: "\r\n".join(v) + "\r\n" for k, v in out.items()}, log


RE_NUM = re.compile(r"-?\d+(?:\.\d+)?")


def compare_with_tolerance(py, dv, tol=2):
    """OCR 模式下的比对：字段种类与文本必须完全一致，数字允许 ±tol 点。

    为什么不能逐字节比：OCR 的检测框在 cv2（Python）与 Kotlin 移植版之间有约 1px 的浮点差
    （DB 后处理的 unclip/最小外接矩形实现细节），经映射后放大成 1~2 点（0.1~0.3mm），
    还会顺带让字号差 1 点。这属于数值噪声，不是逻辑错误；条码-only 那条链仍然逐字节比。
    """
    pl, dl = py.splitlines(), dv.splitlines()
    if len(pl) != len(dl):
        return [f"行数不一致：python {len(pl)} vs android {len(dl)}"]
    bad = []
    for i, (a, b) in enumerate(zip(pl, dl)):
        if a == b:
            continue
        if RE_NUM.sub("#", a) != RE_NUM.sub("#", b):
            bad.append(f"第{i + 1}行结构/文本不同：\n      python  {a}\n      android {b}")
            continue
        an = [float(x) for x in RE_NUM.findall(a)]
        bn = [float(x) for x in RE_NUM.findall(b)]
        worst = max((abs(x - y) for x, y in zip(an, bn)), default=0)
        if worst > tol:
            bad.append(f"第{i + 1}行数值差 {worst:g} 点 > {tol}：\n      python  {a}\n      android {b}")
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", type=int, default=G.DEFAULT_TEMPLATE)
    ap.add_argument("--frame", default="full")
    ap.add_argument("--ocr", action="store_true", help="两端都开 OCR（含文字要素），验证文字链路")
    ap.add_argument("--tol", type=int, default=2, help="--ocr 模式下允许的点数差")
    args = ap.parse_args()

    dev, log = device_zpls(args.template, args.frame, ocr=args.ocr)
    if not dev:
        print("!! no ZPL in logcat. raw log tail:")
        print("\n".join(log.splitlines()[-15:]))
        return 1

    fails = 0
    # PC 参考实现：OCR 通过「传 det/rec」打开（build 里 det is not None 才跑文字）
    det = rec = None
    if args.ocr:
        sys.path.insert(0, "E:/workspace/paddleocr-android-app/tools")
        import ppocr_ref
        det, rec = ppocr_ref.load()
    for name in SAMPLES:
        img = os.path.join(ROOT, "samples", name)
        py = label_to_zpl.build(img, args.template, det, rec, args.frame)["zpl"]
        dv = dev.get(name)
        if dv is None:
            print(f"[SKIP] {name}: 设备端没有输出")
            fails += 1
            continue
        if args.ocr:
            # OCR 链路：字段种类/文本必须一致，坐标允许 tol 点（见 compare_with_tolerance）
            bad = compare_with_tolerance(py, dv, args.tol)
            if not bad:
                same = py == dv
                print(f"[ok  ] {name}: {'逐字节一致' if same else f'要素一致（坐标差 ≤{args.tol} 点）'}"
                      f"（{len(py)} 字节）")
            else:
                fails += 1
                print(f"[FAIL] {name}")
                for l in bad:
                    print("      " + l)
        elif py == dv:
            print(f"[ok  ] {name}: 两端逐字节一致（{len(py)} 字节）")
        else:
            fails += 1
            print(f"[FAIL] {name}")
            import difflib
            for l in difflib.unified_diff(py.splitlines(), dv.splitlines(),
                                          "python", "android", lineterm=""):
                print("        " + l)
    print("mismatches:", fails)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
