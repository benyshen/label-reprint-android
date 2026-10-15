"""Run the Zebra DL barcode engine on device and show what each strategy found.

The DL model (barcode-decoder-5.0.4) has three static-image entry points and they do NOT
agree on a single photo, so the app runs all three and logs each outcome:

    PROCESS      process(ImageData.fromBitmap())  -> the camera pipeline, multi-result
    LOCALIZE     Localizer.detect() + decode(bitmap, boxes) -> per-box decode
    DECODE_ONE   decode(bitmap) -> native full pipeline, top-1 only

ZSTEP lines carry the per-strategy outcome; ZPLZ lines carry the ZPL built from whatever the
DL engine returned (kept separate from zxing's ZPL so device_zpl_check.py stays comparable
with the PC reference). Also prints the PC reference (zxing) side by side: the two engines
legitimately see slightly different boxes, so byte-identical ZPL is NOT the expectation here.

Usage: python device_zebra_check.py [--compare]
"""
import argparse, os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zpl_geometry as G
import label_to_zpl

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = "C:/Users/shenyf/AppData/Local/Android/Sdk/platform-tools/adb.exe"
PKG = "com.tiaomabang.labelreprint"
SAMPLES = ["label_sample_1.png", "label_sample_2.png", "label_sample_3.png"]
# DL 模型首次要把 147MB 资产解到应用私有目录，给足时间
RUN_WAIT = 75


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")


def run_and_pull_log(wait):
    """启动 App 跑 DL 引擎，轮询到 3 张样图都出 ZPLZ 就提前结束，不等满 wait 秒。"""
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", f"am start -n {PKG}/.MainActivity --es zebra 1")
    t0 = time.time()
    log = ""
    # 轮询到 3 张样图都出 ZPLZ 为止，避免死等固定时长
    while time.time() - t0 < wait:
        time.sleep(5)
        log = adb("logcat", "-d", "-s", "LABELREPRINT:V").stdout
        if len(re.findall(r"ZPLZ >>> \S+", log)) >= len(SAMPLES):
            break
    return log


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--compare", action="store_true", help="同时打印 PC 端 zxing 参考的识别结果")
    ap.add_argument("--wait", type=int, default=RUN_WAIT, help="最长等待秒数（模型首次解压要几十秒）")
    args = ap.parse_args()

    log = run_and_pull_log(args.wait)

    # 先把 log 按样图切成块。锚点用 RUN >>> （解码开始前就打），不能用 PLAN >>>：
    # 日志里 ZEBRA/ZXRAW 明细行跑在 PLAN 行之前，按 PLAN 切会把每张图的数据归到上一张。
    blocks, cur = {}, None
    for line in log.splitlines():
        m = re.search(r"(?:RUN|PLAN) >>> (\S+)", line)
        if m and (m.group(0).startswith("RUN") or m.group(1) not in blocks):
            cur = m.group(1)
            blocks.setdefault(cur, [])
            continue
        if cur:
            blocks[cur].append(line)

    rc = 0
    for name in SAMPLES:
        lines = blocks.get(name)
        if not lines:
            print(f"[FAIL] {name}: 设备端没有输出（DL 引擎没跑起来？看下面的原始日志）")
            rc = 1
            continue
        joined = "\n".join(lines)
        meta = re.search(r"ZSTEPS >>> \S+ (.*)", joined)
        print(f"\n=== {name} ===")
        if meta:
            print(f"  {meta.group(1)}")
        for m in re.finditer(r"ZSTEP >>> (\S+) (\w+) (\w+) n=(\d+)(?: (.*))?", joined):
            flag = "ok  " if m.group(3) == "SUCCESS" else ("none" if m.group(3) == "NONE" else "FAIL")
            print(f"  [{flag}] {m.group(2):<11} n={m.group(4)} {m.group(5) or ''}")
        for m in re.finditer(r"ELEM >>> (\w+) (\S*) '([^']*)' dots=\[(.*)\]", joined):
            print(f"    elem {m.group(1):<11} {m.group(2):<14} '{m.group(3)}' dots=[{m.group(4)}]")
        for m in re.finditer(r"DROP >>> (.*)", joined):
            print(f"    drop {m.group(1)}")
        for m in re.finditer(r"ZPLERR >>> (.*)", joined):
            print(f"    ZPLERR {m.group(1)}")
            rc = 1
        zpl = re.search(r"ZPLZ >>> \S+\n(.*?)(?=\n\d\d-\d\d|\Z)", joined, re.S)
        if zpl:
            print("  ZPLZ:")
            for l in zpl.group(1).splitlines():
                if l.startswith("^"):
                    print("    " + l)
        if args.compare:
            img = os.path.join(ROOT, "samples", name)
            ref = label_to_zpl.build(img, G.DEFAULT_TEMPLATE, None, None, "full")
            print("  PC 参考(zxing) 识别到的要素：")
            for e in ref["elements"]:
                print(f"    {e['kind']:<11} {e.get('format', ''):<14} '{e['text']}'")
    if not blocks:
        print("!! logcat 里没有任何 PLAN/ZSTEP 输出，原始日志尾部：")
        print("\n".join(log.splitlines()[-25:]))
        return 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
