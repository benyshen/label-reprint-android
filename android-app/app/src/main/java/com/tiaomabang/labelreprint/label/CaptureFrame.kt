package com.tiaomabang.labelreprint.label

/**
 * 取景框的几何。
 *
 * UI（`CaptureGuide` 画框）与流水线（`LabelFrame` 在没检出标签边框时按框裁剪）**必须用同一份公式**：
 * 两边各写一份，早晚会漂移，用户就会看到"框里框外跟实际处理的范围对不上"。
 *
 * 归一化坐标（0..1）与像素坐标都支持 —— 调用方传自己的尺寸，同一比例在小图和大图上一致。
 */
object CaptureFrame {

    /** 框最大占画面的比例。与 UI 上看到的边距是同一个数。 */
    const val FRAC = 0.86f

    /**
     * 给出取景框矩形。
     *
     * @param aspect 框的长宽比（宽度/高度），通常来自当前模板
     * @param w,h    画面尺寸（像素或任意单位）
     * @return [x0, y0, x1, y1]，与入参同单位
     */
    fun rect(aspect: Float, w: Float, h: Float): FloatArray {
        var fw = w * FRAC
        var fh = if (aspect > 0f) fw / aspect else h * FRAC
        if (fh > h * FRAC) {          // 竖幅框：按高度反算，保证完整落在画面内
            fh = h * FRAC
            fw = fh * aspect
        }
        val x0 = (w - fw) / 2f
        val y0 = (h - fh) / 2f
        return floatArrayOf(x0, y0, x0 + fw, y0 + fh)
    }

    /** 归一化版本（0..1），给不关心像素尺寸的调用方 */
    fun rectNorm(aspect: Float, w: Float, h: Float): FloatArray {
        val r = rect(aspect, w, h)
        return floatArrayOf(r[0] / w, r[1] / h, r[2] / w, r[3] / h)
    }
}
