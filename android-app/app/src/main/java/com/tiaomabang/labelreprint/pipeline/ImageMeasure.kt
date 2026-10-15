package com.tiaomabang.labelreprint.pipeline

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 从位图上直接量几何（镜像 `tools/label_to_zpl.py` 里的同名函数）。
 *
 * 为什么必须自己量：zxing-cpp 的 Android 封装对**一维码**返回的是扫描线而不是符号包围盒
 * （实测 options 各种组合：minLineCount=1 -> 1 点高、=2 -> 2 点高、=10 -> 直接不出结果；
 * 2x 放大仍是 2 点高；同一库同一版本在 Python 端返回的是完整框）。照它给的框生成 ZPL，
 * 条高就是 2 点 —— 打出来只剩一条细线。
 */
object ImageMeasure {

    /** ARGB_8888 -> 亮度数组（Rec.601，与 PC 端 cv2 灰度基本一致） */
    fun toGray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            out[i] = ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000)
        }
        return out
    }

    /**
     * 一维码的条带行范围。在 [x0,x1] 区间内逐行统计墨迹占比，取包含 yHint 的那段连续墨迹带。
     * 实测分离度：条码行 ≈0.45、下方人眼可读串 ≤0.21、空白 0.00 -> 阈值 0.30 干净切开。
     */
    fun barRows(gray: IntArray, w: Int, h: Int, x0: Int, x1: Int, yHint: Int,
                thr: Double = 0.30, minRun: Int = 5): Pair<Int, Int>? {
        val xa = maxOf(0, minOf(x0, x1))
        val xb = minOf(w, maxOf(x0, x1) + 1)
        if (xb - xa < 4) return null
        val width = xb - xa
        val dark = BooleanArray(h)
        for (y in 0 until h) {
            var cnt = 0
            val base = y * w
            for (x in xa until xb) if (gray[base + x] < 128) cnt++
            dark[y] = cnt.toDouble() / width >= thr
        }
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE
        var start = -1
        for (y in 0..h) {
            if (y < h && dark[y]) {
                if (start < 0) start = y
            } else if (start >= 0) {
                val end = y - 1
                if (end - start + 1 >= minRun) {
                    val d = Math.abs((start + end) / 2 - yHint)
                    if (d < bestDist) {
                        bestDist = d
                        best = start to end
                    }
                }
                start = -1
            }
        }
        return best
    }
}
