#!/usr/bin/env python3
"""V1.0 封版回归：一键跑完全部可离线验证的项，输出机器可读的汇总。

为什么要有它：out/ 里会同时存在模板1/2/3 的 ZPL，validate_zpl.py 默认按模板1 校，
把模板3 的 `^PW559` 报成 "!= template 480" —— 11 条 ERROR 全是校错模板造成的假警报。
封版数字必须来自一次真跑，所以模板要从文件自身的 ^PW/^LL 反查，逐文件校验。

用法：python tools/regress.py
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable
sys.path.insert(0, str(ROOT / "tools"))
import zpl_geometry as G  # noqa: E402


def run(cmd: list[str]) -> str:
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (p.stdout or "") + (p.stderr or "")


def template_of(zpl: str) -> int | None:
    """从 ^PW/^LL 反查模板号；对不上任何模板返回 None。"""
    pw = re.search(r"\^PW(\d+)", zpl)
    ll = re.search(r"\^LL(\d+)", zpl)
    if not pw or not ll:
        return None
    size = (int(pw.group(1)), int(ll.group(1)))
    for idx in sorted(G.TEMPLATES):
        w, h = G.dims(idx)
        if (w, h) == size:
            return idx
    return None


def main() -> int:
    print("=" * 62)
    print("V1.0 封版回归")
    print("=" * 62)

    # --- 1. PC 参考实现全链（demo.py：解码 -> OCR -> 映射 -> ZPL） ---
    demo = run([PY, "tools/demo.py"])
    cases = [ln.strip() for ln in demo.splitlines() if "elements=" in ln]
    print(f"\n[1] tools/demo.py  {len(cases)} 个用例")
    for ln in cases:
        print("    " + ln)
    bad_demo = [ln for ln in cases if "zpl_errors=0" not in ln or "overflow=0" not in ln]

    # --- 2. 已生成 ZPL 的逐文件校验（模板按文件自身尺寸反查） ---
    zpls = sorted((ROOT / "out").glob("*.zpl"))
    print(f"\n[2] tools/validate_zpl.py  {len(zpls)} 个成品 ZPL")
    bad_zpl, skipped = [], []
    for f in zpls:
        text = f.read_text(encoding="utf-8", errors="replace")
        tpl = template_of(text)
        if tpl is None:
            skipped.append(f.name)
            continue
        out = run([PY, "tools/validate_zpl.py", f"out/{f.name}", "--template", str(tpl)])
        errs = re.search(r"errors:\s*(\d+)", out)
        n = int(errs.group(1)) if errs else -1
        print(f"    {'OK  ' if n == 0 else 'FAIL'} 模板{tpl:<2} errors={n:<3} {f.name}")
        if n != 0:
            bad_zpl.append((f.name, n, out))

    # --- 3. 关键常量自检（跨语言必须一致的那几个） ---
    print("\n[3] zpl_geometry 常量自检")
    checks = [
        ("模板表 3 项且尺寸符合 203dpi",
         len(G.TEMPLATES) == 3 and {i: G.dims(i) for i in sorted(G.TEMPLATES)}
         == {1: (480, 320), 2: (559, 559), 3: (559, 320)}),
        ("模板1 60x40 -> 480x320", G.dims(1) == (480, 320)),
        ("模板2 70x70 -> 559x559", G.dims(2) == (559, 559)),
        ("模板3 70x40 -> 559x320", G.dims(3) == (559, 320)),
        ("20 位纯数字走 Code128C = 145 模块", G.code128_modules(20, True) == 145),
        ("20 位非纯数字走 Code128B = 255 模块", G.code128_modules(20, False) == 255),
        ("汉字占 1.0 em", abs(G.text_width("药", 20) - 20.0) < 1e-6),
        # 用 "2345"（不含 '1'，它是窄字符 0.26）——ASCII 数字 ≈ 半格
        ("ASCII 数字占 ~0.5 em", abs(G.text_width("2345", 20) / 4 / 20 - 0.5) < 0.05),
        ("汉字行字号受宽度约束（31 字符 / 416 点 -> 17 号）",
         G.font_size_for(410, 16.6, "药品标识码：8207188序列号：0078020027464") == 17),
        ("模块宽下限 2 点", G.MIN_MODULE_DOTS == 2.0),
        ("文字/条码净空下限 6 点", G.MIN_TEXT_BARCODE_GAP == 6.0),
    ]
    bad_const = []
    for name, ok in checks:
        print(f"    {'OK  ' if ok else 'FAIL'} {name}")
        if not ok:
            bad_const.append(name)

    # --- 汇总 ---
    print("\n" + "=" * 62)
    fail = len(bad_demo) + len(bad_zpl) + len(bad_const)
    print(f"demo 用例 {len(cases)}｜ZPL 校验 {len(zpls) - len(skipped)}"
          f"（跳过无模板 {len(skipped)}）｜常量自检 {len(checks)}")
    print(f"失败项：{fail}")
    if skipped:
        print("跳过：" + ", ".join(skipped))
    for name, n, out in bad_zpl:
        print(f"\n--- {name} ({n} errors) ---\n{out.strip()[-600:]}")
    print("=" * 62)
    return 1 if fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
