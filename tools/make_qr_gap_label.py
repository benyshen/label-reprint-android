#!/usr/bin/env python3
"""造一张"QR 紧贴文字"的测试标签，验证二维码下方额外 1mm 净空。

版式（模拟真实标签，画在白底上）：
    ┌──────────────────────────┐
    │  [QR]  HELLO QR          │   ← QR 与下方文字只差 4 px（≈0.1mm）
    │  HELLO QR                │
    │  ||||||||||||||||        │   ← 一维码
    │  1234567890              │   ← HRI 紧贴条（一维码不该被加间距）
    └──────────────────────────┘

用法：python tools/make_qr_gap_label.py  → 输出到 android-app/.../assets/samples/qr_gap_test.png
"""
import os
import qrcode
from PIL import Image, ImageDraw, ImageFont

W, H = 1400, 1000
OUT = os.path.join(os.path.dirname(__file__), "..", "android-app", "app", "src", "main",
                   "assets", "samples", "qr_gap_test.png")


def font(px):
    for p in ("C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, px)
    return ImageFont.load_default()


def main():
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)

    # --- QR：内容短、mag 大，印出来清晰 ---
    q = qrcode.QRCode(border=4, box_size=10, error_correction=qrcode.constants.ERROR_CORRECT_M)
    q.add_data("QRGAP-TEST-0001")
    q.make(fit=True)
    qr = q.make_image(fill_color="black", back_color="white").convert("RGB")
    qr = qr.resize((420, 420), Image.NEAREST)
    qx, qy = 90, 60
    img.paste(qr, (qx, qy))
    qr_bottom = qy + 420

    # --- QR 正下方 4px 处的文字（故意贴死） ---
    f = font(64)
    d.text((qx, qr_bottom + 4), "HELLO QR", font=f, fill="black")

    # --- 下方一维码 + 紧贴的 HRI（对照：一维码不该被动） ---
    bx, by = 90, 620
    bars = "1101001000010100111010010110100100001011011010011101001010001101"
    x = bx
    for i, bit in enumerate(bars * 6):
        w = 4 if bit == "1" else 4
        if bit == "1":
            d.rectangle([x, by, x + w - 1, by + 140], fill="black")
        x += w
    d.text((bx, by + 146), "1234567890", font=font(48), fill="black")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT)
    print(f"wrote {os.path.abspath(OUT)}  QR bottom={qr_bottom}  text top={qr_bottom + 4}")


if __name__ == "__main__":
    main()
