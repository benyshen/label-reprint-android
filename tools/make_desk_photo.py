"""造一张"竖幅照片里拍一张横幅标签"的测试图，用来验证标签边框检测。

复现用户实际场景（日志里的 2449×3265 那张）：手机竖着拍，标签横躺，周围全是背景 ——
这是 `basis=full` 必然错、`basis=label` 才有救的情形。
"""
import sys

import cv2
import numpy as np

SRC = "samples_user/source_calib.png"      # 真实拍过的标签（带背景，先用它裁出标签面）
OUT = "android-app/app/src/main/assets/samples/label_on_desk.png"


def main() -> int:
    img = cv2.imread(SRC)
    if img is None:
        print(f"读不到 {SRC}")
        return 1
    # 取标签的"横版"内容：把源图旋转 90 度再缩到常见 60×40 比例
    lab = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
    h, w = lab.shape[:2]
    tw, th = 1400, int(round(1400 * 40 / 60))          # 60×40mm 的比例
    lab = cv2.resize(lab, (tw, th), interpolation=cv2.INTER_CUBIC)

    # 竖幅画布（3:4 竖），深色桌面背景 + 噪声，标签居中放置 —— 与用户那张照片同构
    cw = int(tw * 1.35)
    ch = int(cw * 4 / 3)          # 竖幅：画布比标签高得多，标签只占中间一条
    canvas = np.full((ch, cw, 3), 58, np.uint8)
    noise = np.random.default_rng(7).normal(0, 6, canvas.shape)
    canvas = np.clip(canvas + noise, 0, 255).astype(np.uint8)
    x0, y0 = (cw - tw) // 2, (ch - th) // 2
    # 标签外框（白边纸），让四边形检测有清晰边界
    cv2.rectangle(canvas, (x0 - 8, y0 - 8), (x0 + tw + 8, y0 + th + 8), (245, 245, 245), -1)
    canvas[y0:y0 + th, x0:x0 + tw] = lab

    cv2.imwrite(OUT, canvas)
    print(f"写出 {OUT}  {canvas.shape[1]}x{canvas.shape[0]}，"
          f"标签占 {tw}x{th}（{tw / cw:.0%} 宽）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
