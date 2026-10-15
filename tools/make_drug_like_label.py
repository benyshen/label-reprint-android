"""造一张"药品追溯码标签"式测试图，复现用户那张照片的几何。

复现的三个特征（都是实测踩过的坑）：
  1. 竖幅照片 + 横躺标签 + 背景  -> 逼出 basis=label（按整幅图映射会整体缩一半）
  2. 20 位纯数字 Code128，只占标签宽 47% -> 映射后模块宽 1.55 点 < 可印下限 2 点（会印糊被扫成 39 码）
  3. HRI 文字紧贴条码下方 12px（约 3.6 点）-> 逼出文字与条码的净空修正
"""
import sys

import cv2
import numpy as np
import zxingcpp
from PIL import Image, ImageDraw, ImageFont

DATA = "82071880078020027464"              # 用户那张标签的真实内容（20 位纯数字）
HRI = "约品标识码：8207188序列号：0078020027464"
OUT = "android-app/app/src/main/assets/samples/drug_like.png"

LW, LH = 1600, 1067                        # 标签画布（3:2，对应 60×40mm）
FONT = "C:/Windows/Fonts/msyh.ttc"


def font(px):
    try:
        return ImageFont.truetype(FONT, px)
    except OSError:
        return ImageFont.load_default()


def wide():
    """宽条带标签（2.49:1），复刻用户那张药品标签的实际几何 —— 用来验"文字挤着叠印"的修复。

    从用户那次运行的日志反推（模板3 = 559×320 点，缩放 0.259）：
      条码框 dots=[131,130,503,206]  -> 标签内 x=0.234 w=0.665 y=0.406 h=0.238
      HRI 框 dots=[131,213,499,233]  -> 标签内 x=0.234 w=0.658 y=0.666 h=0.064
    打出来是 26 号字（需要 858 点、字段只有 416 点）-> 字符被挤着叠印。
    """
    LW2, LH2 = 2400, 964                      # 2.49:1，与用户标签一致
    lab = Image.new("RGB", (LW2, LH2), (255, 255, 255))
    d = ImageDraw.Draw(lab)
    d.text((int(0.33 * LW2), int(0.09 * LH2)), "药品追溯码", fill=(0, 0, 0), font=font(72))
    d.text((int(0.53 * LW2), int(0.09 * LH2)), "支付宝扫一扫", fill=(0, 0, 0), font=font(64))

    bx, by = int(0.234 * LW2), int(0.406 * LH2)
    bw, bh = int(0.665 * LW2), int(0.238 * LH2)
    bc = zxingcpp.write_barcode(zxingcpp.BarcodeFormat.Code128, DATA, height=200)
    bc = np.array(bc) if not isinstance(bc, Image.Image) else bc
    bc = Image.fromarray(bc).convert("L").resize((bw, bh), Image.NEAREST).convert("RGB")
    lab.paste(bc, (bx, by))

    # HRI：字号 80px -> 映射后高约 18.6 点、宽约 373 点（与用户那张 20.4 / 368 基本一致）
    d.text((bx, int(0.666 * LH2)), HRI, fill=(0, 0, 0), font=font(80))

    canvas = np.array(lab)[:, :, ::-1]
    cv2.imwrite(OUT, canvas)
    modules = 11 * ((len(DATA) + 1) // 2) + 35
    print(f"写出 {OUT}（宽条带）{LW2}x{LH2} 长宽比 {LW2 / LH2:.2f}")
    print(f"  预期：模板3 559x320，缩放 {320 / LH2:.3f}，条码模块宽 "
          f"{559 * 0.665 / modules:.2f} 点；HRI 行 31 字符应排成 ~19 号（不能再是 26 号）")
    return 0


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "wide":
        return wide()
    lab = Image.new("RGB", (LW, LH), (255, 255, 255))
    d = ImageDraw.Draw(lab)

    d.text((int(0.24 * LW), int(0.05 * LH)), "药品追溯码", fill=(0, 0, 0), font=font(46))
    d.text((int(0.56 * LW), int(0.06 * LH)), "支付宝扫一扫", fill=(0, 0, 0), font=font(38))

    # 条码：占标签宽 47%（与用户那张一致）
    target_w = int(0.47 * LW)
    bc = zxingcpp.write_barcode(zxingcpp.BarcodeFormat.Code128, DATA, height=220)
    bc = np.array(bc) if not isinstance(bc, Image.Image) else bc
    bc = Image.fromarray(bc).convert("L")
    bc = bc.resize((target_w, 220), Image.NEAREST).convert("RGB")
    bx = int(0.26 * LW)
    by = int(0.42 * LH)
    lab.paste(bc, (bx, by))

    # HRI 紧贴条码下方 4px（映射成点只有 1.2 点 —— 重叠就是从这来的）
    d.text((bx + 6, by + 220 + 4), HRI, fill=(0, 0, 0), font=font(40))

    # 贴进竖幅照片：深色桌面 + 噪声，标签居中（逼出四边形检测）
    cw, ch = int(LW * 1.18), int(LW * 1.18 * 4 / 3)
    canvas = np.full((ch, cw, 3), 56, np.uint8)
    canvas = np.clip(canvas + np.random.default_rng(3).normal(0, 7, canvas.shape), 0, 255).astype(np.uint8)
    x0, y0 = (cw - LW) // 2, (ch - LH) // 2
    cv2.rectangle(canvas, (x0 - 10, y0 - 10), (x0 + LW + 10, y0 + LH + 10), (246, 246, 246), -1)
    canvas[y0:y0 + LH, x0:x0 + LW] = np.array(lab)[:, :, ::-1]

    cv2.imwrite(OUT, canvas)
    modules = 11 * ((len(DATA) + 1) // 2) + 35
    print(f"写出 {OUT}  {cw}x{ch}")
    print(f"  条码 {target_w}px = 标签宽的 {target_w / LW:.0%}，标签 {LW}x{LH}")
    print(f"  模块数（Code128C，{len(DATA)} 位纯数字）= {modules}")
    print(f"  预期：映射后条码宽 ≈ {480 * target_w / LW:.0f} 点 -> 模块宽 ≈ "
          f"{480 * target_w / LW / modules:.2f} 点（< 2 应被抬到 2，需 {2 * modules} 点）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
