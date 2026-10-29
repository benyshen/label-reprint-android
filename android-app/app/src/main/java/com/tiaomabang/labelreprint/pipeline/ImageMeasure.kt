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

    /**
     * 元素框里的**墨迹**范围 [宽, 高]（像素）。
     *
     * PP-OCR 的框是"行框"：上下留了行距、左右留了字距，比字形本身胖 10~20%。
     * V1.9 目录拿它当字高推字号，打出来整版字大一号（用户对比：原标签小得多）。
     * 逐行/逐列统计墨迹占比，取占比 ≥ minRatio 的行/列跨度 —— 留白不算墨迹。
     */
    fun inkSize(gray: IntArray, w: Int, h: Int, px: List<Float>,
                thr: Int = 128, minRatio: Float = 0.05f): Pair<Float, Float>? {
        val x0 = maxOf(0, px[0].toInt())
        val x1 = minOf(w, px[2].toInt()).coerceAtLeast(x0 + 1)
        val y0 = maxOf(0, px[1].toInt())
        val y1 = minOf(h, px[3].toInt()).coerceAtLeast(y0 + 1)
        val cols = (x1 - x0)
        val rows = (y1 - y0)
        if (cols < 2 || rows < 2) return null
        val rowInk = IntArray(rows)
        val colInk = IntArray(cols)
        for (y in y0 until y1) {
            val base = y * w
            for (x in x0 until x1) {
                if (gray[base + x] < thr) {
                    rowInk[y - y0]++
                    colInk[x - x0]++
                }
            }
        }
        val minRow = (cols * minRatio).toInt().coerceAtLeast(1)
        val minCol = (rows * minRatio).toInt().coerceAtLeast(1)
        var r0 = -1; var r1 = -1
        for (i in 0 until rows) if (rowInk[i] >= minRow) { if (r0 < 0) r0 = i; r1 = i }
        var c0 = -1; var c1 = -1
        for (i in 0 until cols) if (colInk[i] >= minCol) { if (c0 < 0) c0 = i; c1 = i }
        if (r0 < 0 || c0 < 0) return null
        return (c1 - c0 + 1).toFloat() to (r1 - r0 + 1).toFloat()
    }

    /**
     * 找标签上的**长直线**（外框、分隔线）—— V1.9 完全没有这个能力，纸面上框体全丢。
     *
     * 与 PC 端 `label_to_zpl.py: detect_rules` 同一套算法（端侧没有 OpenCV，纯数组实现）：
     *  1. 多档阈值取并集 —— 同一张照片外框左上段约 150 灰度、分隔线约 120、右下段更浅，
     *     单一阈值必然漏，Otsu/自适应阈值实测漏得更多；
     *  2. 每行取**最长墨迹段**（允许 bridge 像素内的断点，框线常被反光/折痕打断），
     *     段长 ≥ minFrac×宽的行记为命中行；
     *  3. 相邻命中行并成一条带（线是斜的时，同一根线在各行只覆盖不同的一小段，
     *     带的范围取所有命中段的最小起/最大止，才能把整根线兜住）；
     *  4. 多档阈值/斜线的偏差按"中线对齐 + 空档不大"合并，重叠 >70% 的去重；
     *  5. 只补角（竖线端点贴在横线附近才延伸），再按中线把 bbox 压回真实笔画厚度。
     *
     * 返回 [x0,y0,x1,y1]（像素）。
     */
    fun detectRules(gray: IntArray, w: Int, h: Int, excludePx: List<List<Float>> = emptyList()): List<List<Float>> {
        val minFrac = 0.22f
        val bridge = maxOf(4, w / 200)
        val pad = maxOf(3, w / 200)
        // 条码排除区的容差：条的最外一根常常压在解码框边缘外一点点，
        // 容差写 2px 就会漏掉它，纸上多一根竖线。按图宽 1%（最少 6px）放宽。
        val xTol = maxOf(8f, 0.02f * w)
        val yTol = maxOf(8f, 0.02f * h)
        val thrList = intArrayOf(110, 128, 150, 170)
        val hBoxes = ArrayList<List<Float>>()
        val vBoxes = ArrayList<List<Float>>()

        for (thr in thrList) {
            val minLenH = minFrac * w
            val minLenV = minFrac * h
            // 横向扫描：逐行找最长墨迹段
            val hBands = ArrayList<FloatArray>()   // [i0,i1,s,e]
            val vBands = ArrayList<FloatArray>()
            val rowDark = BooleanArray(w)
            val colDark = BooleanArray(h)
            for (y in pad until h - pad) {
                val base = y * w
                for (x in 0 until w) rowDark[x] = x >= pad && x < w - pad && gray[base + x] < thr
                val seg = longestRun(rowDark, bridge) ?: continue
                if (seg[2] >= minLenH) addBand(hBands, y, seg[0], seg[1], 1)
            }
            for (x in pad until w - pad) {
                for (y in 0 until h) colDark[y] = y >= pad && y < h - pad && gray[y * w + x] < thr
                val seg = longestRun(colDark, bridge) ?: continue
                if (seg[2] >= minLenV) addBand(vBands, x, seg[0], seg[1], 1)
            }
            for (b in hBands) hBoxes.add(listOf(b[2], b[0], b[3] + 1, b[1] + 1))
            for (b in vBands) vBoxes.add(listOf(b[0], b[2], b[1] + 1, b[3] + 1))
        }

        // 合并：中线对齐 + 空档 ≤ 15%
        val merged = ArrayList<MutableList<Float>>()
        for (b in hBoxes + vBoxes) {
            var hit = false
            for (m in merged) {
                if (coLinear(m, b, w, h)) {
                    m[0] = minOf(m[0], b[0]); m[1] = minOf(m[1], b[1])
                    m[2] = maxOf(m[2], b[2]); m[3] = maxOf(m[3], b[3])
                    hit = true
                    break
                }
            }
            if (!hit) merged.add(b.toMutableList())
        }

        val hs = merged.filter { it[2] - it[0] >= it[3] - it[1] }
        val vs = merged.filter { it[2] - it[0] < it[3] - it[1] }

        // 只补角：竖线端点贴在横线附近才延伸（否则任何一根短竖笔都被拉成整版竖线）
        val tolH = maxOf(10f, 0.025f * h)
        val tolW = maxOf(10f, 0.025f * w)
        for (hp in hs) {
            for (vp in vs) {
                val near = Math.abs(vp[1] - hp[1]) <= tolH || Math.abs(vp[1] - hp[3]) <= tolH ||
                    Math.abs(vp[3] - hp[1]) <= tolH || Math.abs(vp[3] - hp[3]) <= tolH
                if (!near) continue
                val cy = (hp[1] + hp[3]) / 2f
                if (vp[1] - tolH <= cy && cy <= vp[3] + tolH) {
                    hp[0] = minOf(hp[0], vp[0]); hp[2] = maxOf(hp[2], vp[2])
                }
            }
        }
        for (vp in vs) {
            for (hp in hs) {
                val near = Math.abs(hp[0] - vp[0]) <= tolW || Math.abs(hp[0] - vp[2]) <= tolW ||
                    Math.abs(hp[2] - vp[0]) <= tolW || Math.abs(hp[2] - vp[2]) <= tolW
                if (!near) continue
                val cx = (vp[0] + vp[2]) / 2f
                if (hp[0] - tolW <= cx && cx <= hp[2] + tolW) {
                    vp[1] = minOf(vp[1], hp[1]); vp[3] = maxOf(vp[3], hp[3])
                }
            }
        }

        // 用最外侧的竖线/横线定出标签范围：越界的裁掉；贴着上下（左右）边的直接拉满整条 ——
        // 原标签的上下边框本来就是从左框到右框，被反光/折痕截断的那段必须补回来。
        if (hs.isNotEmpty() && vs.isNotEmpty()) {
            val fx0 = vs.minOf { it[0] }; val fx1 = vs.maxOf { it[2] }
            val fy0 = hs.minOf { it[1] }; val fy1 = hs.maxOf { it[3] }
            for (hp in hs) {
                val cy = (hp[1] + hp[3]) / 2f
                hp[0] = maxOf(hp[0], fx0); hp[2] = minOf(hp[2], fx1)
                if (Math.abs(cy - fy0) <= 0.05f * h || Math.abs(cy - fy1) <= 0.05f * h) {
                    hp[0] = fx0; hp[2] = fx1
                } else {
                    if (hp[0] - fx0 <= 0.15f * w) hp[0] = fx0
                    if (fx1 - hp[2] <= 0.15f * w) hp[2] = fx1
                }
            }
            for (vp in vs) {
                val cx = (vp[0] + vp[2]) / 2f
                vp[1] = maxOf(vp[1], fy0); vp[3] = minOf(vp[3], fy1)
                if (Math.abs(cx - fx0) <= 0.05f * w || Math.abs(cx - fx1) <= 0.05f * w) {
                    vp[1] = fy0; vp[3] = fy1
                } else {
                    if (vp[1] - fy0 <= 0.15f * h) vp[1] = fy0
                    if (fy1 - vp[3] <= 0.15f * h) vp[3] = fy1
                }
            }
        }

        val out = ArrayList<MutableList<Float>>()
        for (b in merged) {
            // 与条码区**区间相交**就丢掉（不能用中心点：条码竖条会被并成跨越高度的假竖线，
            // 中心落在条码框外面，中心点判据漏掉后纸上就多了七八根竖线）
            // 长横线不参与条码/文字排除：条码的条是**竖直**的，一根横贯整版的横线不可能是
            // 它的一部分；而下边框恰好就压在条码 HRI 带的下方，被排除区吃掉就成了
            // "少一格"（实测最底下那根边框一直出不来）。
            val longH = (b[2] - b[0]) >= 0.25f * w
            if (!longH && excludePx.any {
                    b[0] < it[2] + xTol && b[2] > it[0] - xTol &&
                        b[1] < it[3] + yTol && b[3] > it[1] - yTol
                }) continue
            val thin = thinLine(b)
            // 实心判据：沿线每个位置（±4px）有没有墨。真印的线连续（>0.9），
            // 由"几笔文字竖画 + 桥接"拼出来的假线只有 0.2~0.5（实测 x=70 那条贯穿整张标签）。
            // 覆盖率判据暂关：这张照片上它把左右边框（反光断线，覆盖 ~0.55）一起杀了，
            // 反而留下暗背景的假竖线。函数保留，等有多张样本再定阈值。
            if (lineCoverage(gray, w, h, thin) < 0.0f) continue
            var dup = false
            for (i in out.indices) {
                val o = out[i]
                val ix = minOf(thin[2], o[2]) - maxOf(thin[0], o[0])
                val iy = minOf(thin[3], o[3]) - maxOf(thin[1], o[1])
                if (ix <= 0f || iy <= 0f) continue
                val areaB = (thin[2] - thin[0]) * (thin[3] - thin[1])
                val areaO = (o[2] - o[0]) * (o[3] - o[1])
                if (ix * iy >= 0.7f * minOf(areaB, areaO)) {
                    if (areaB > areaO) out[i] = thin
                    dup = true
                    break
                }
            }
            if (!dup) out.add(thin)
        }

        // 占跨度 ≥40% 的线补齐到整条：原标签的分隔线本来就是从左框到右框，
        // 照片里被反光/折痕截断的那半截必须补回来，否则打出来是"半截线"，比不画还难看。
        // 跨度校验：只有左右（上下）两条边都找到了才谈补齐。
        // 缺一条边时把另一条当基准，会把整版线压成几十点宽（实测右边框漏检 -> 所有横线只剩 71 点）。
        val hh = out.filter { it[2] - it[0] >= it[3] - it[1] }
        val vv = out.filter { it[2] - it[0] < it[3] - it[1] }
        if (vv.size >= 2) {
            val fx0 = vv.minOf { it[0] }
            val fx1 = vv.maxOf { it[2] }
            if (fx1 - fx0 >= 0.5f * w) {
                for (b in hh) if (b[2] - b[0] >= 0.4f * (fx1 - fx0)) { b[0] = fx0; b[2] = fx1 }
            }
        }
        if (hh.size >= 2) {
            val fy0 = hh.minOf { it[1] }
            val fy1 = hh.maxOf { it[3] }
            if (fy1 - fy0 >= 0.5f * h) {
                for (b in vv) if (b[3] - b[1] >= 0.4f * (fy1 - fy0)) { b[1] = fy0; b[3] = fy1 }
            }
        }

        // 最终长度门槛：短于跨度 8% 的"线"不可能是纸面印的框线/分隔线，都是文字笔画或
        // 裁切残渣（相机照片实测一次 20+ 段 3~12 点碎线，全落在 HRI 那排数字上）。
        // 放在最后做：前面的补边/裁边会改长度，只有这里才是最终长度。
        val res = ArrayList<MutableList<Float>>()
        for (b in out) {
            val long = maxOf(b[2] - b[0], b[3] - b[1])
            val span = if (b[2] - b[0] >= b[3] - b[1]) w.toFloat() else h.toFloat()
            if (long >= 0.08f * span) res.add(b)
        }
        return res
    }

    /**
     * 沿线**连续性**：沿线每个位置（垂直于线 ±band 像素内）有没有墨。
     *
     * 必须按"沿线覆盖 + 容差带"量，不能直接数框内像素：照片里的框线是斜的，2 像素宽的
     * 细带装不住它，直接数量会把真线判成 0.2 误杀（实测丢了品名上方的分隔线）。
     */
    private fun lineCoverage(gray: IntArray, w: Int, h: Int, b: List<Float>, thr: Int = 200, band: Int = 4): Float {
        val x0 = maxOf(0, b[0].toInt())
        val y0 = maxOf(0, b[1].toInt())
        val x1 = minOf(w, maxOf(x0 + 1, b[2].toInt()))
        val y1 = minOf(h, maxOf(y0 + 1, b[3].toInt()))
        if (x1 - x0 < 2 || y1 - y0 < 2) return 1f
        return if ((x1 - x0) >= (y1 - y0)) {
            val yc = (y0 + y1) / 2
            val ya = maxOf(0, yc - band)
            val yb = minOf(h, yc + band + 1)
            var hit = 0
            for (x in x0 until x1) {
                var dark = false
                for (y in ya until yb) if (gray[y * w + x] < thr) { dark = true; break }
                if (dark) hit++
            }
            hit.toFloat() / (x1 - x0)
        } else {
            val xc = (x0 + x1) / 2
            val xa = maxOf(0, xc - band)
            val xb = minOf(w, xc + band + 1)
            var hit = 0
            for (y in y0 until y1) {
                var dark = false
                for (x in xa until xb) if (gray[y * w + x] < thr) { dark = true; break }
                if (dark) hit++
            }
            hit.toFloat() / (y1 - y0)
        }
    }

    /** 最长墨迹段（允许 bridge 像素断点），返回 [起点, 终点, 长度] */
    private fun longestRun(dark: BooleanArray, bridge: Int): IntArray? {
        var best: IntArray? = null
        var i = 0
        val n = dark.size
        while (i < n) {
            if (!dark[i]) { i++; continue }
            var start = i
            var end = i
            var j = i + 1
            while (j < n) {
                if (dark[j]) { end = j; j++; continue }
                var k = j
                while (k < n && k - j <= bridge && !dark[k]) k++
                if (k < n && k - j <= bridge) { end = k; j = k + 1 } else break
            }
            val len = end - start + 1
            if (best == null || len > best[2]) best = intArrayOf(start, end, len)
            i = end + 1
        }
        return best
    }

    /** 命中行并按"相邻行（允许 gap 行间隔）"并成带：[i0,i1,s,e] */
    private fun addBand(bands: ArrayList<FloatArray>, i: Int, s: Int, e: Int, gap: Int) {
        for (b in bands) {
            if (i - b[1] <= gap) {
                b[1] = i.toFloat()
                b[2] = minOf(b[2], s.toFloat())
                b[3] = maxOf(b[3], e.toFloat())
                return
            }
        }
        bands.add(floatArrayOf(i.toFloat(), i.toFloat(), s.toFloat(), e.toFloat()))
    }

    private fun coLinear(a: List<Float>, b: List<Float>, w: Int, h: Int): Boolean {
        // 空档必须给得很小（2% 轴长）：给 0.15 会把几笔不相关的文字竖画链成一条贯穿整张
        // 标签的假竖线（实测 x=70 那条从品名一直拉到最底）。厚度差 2.5 倍以上也不是同一根线。
        val aHoriz = (a[2] - a[0]) >= (a[3] - a[1])
        if (aHoriz != ((b[2] - b[0]) >= (b[3] - b[1]))) return false
        return if (aHoriz) {
            val ta = a[3] - a[1]
            val tb = b[3] - b[1]
            if (maxOf(ta, tb) > 2.5f * maxOf(1f, minOf(ta, tb))) return false
            val ca = (a[1] + a[3]) / 2f
            val cb = (b[1] + b[3]) / 2f
            if (Math.abs(ca - cb) > maxOf(10f, maxOf(ta, tb))) return false
            (maxOf(a[0], b[0]) - minOf(a[2], b[2])) <= maxOf(6f, 0.02f * w)
        } else {
            val ta = a[2] - a[0]
            val tb = b[2] - b[0]
            if (maxOf(ta, tb) > 2.5f * maxOf(1f, minOf(ta, tb))) return false
            val ca = (a[0] + a[2]) / 2f
            val cb = (b[0] + b[2]) / 2f
            if (Math.abs(ca - cb) > maxOf(10f, maxOf(ta, tb))) return false
            (maxOf(a[1], b[1]) - minOf(a[3], b[3])) <= maxOf(6f, 0.02f * h)
        }
    }

    /** 斜线/反光会把 bbox 撑厚（横线 bbox 高 24px、真笔画 3px）：按中线压回细线 */
    private fun thinLine(b: List<Float>): MutableList<Float> {
        val x0 = b[0]; val y0 = b[1]; val x1 = b[2]; val y1 = b[3]
        val w = x1 - x0
        val h = y1 - y0
        return if (w >= h) {
            val t = maxOf(2f, minOf(h, 0.004f * w + 2f))
            val cy = (y0 + y1) / 2f
            mutableListOf(x0, cy - t / 2f, x1, cy + t / 2f)
        } else {
            val t = maxOf(2f, minOf(w, 0.004f * h + 2f))
            val cx = (x0 + x1) / 2f
            mutableListOf(cx - t / 2f, y0, cx + t / 2f, y1)
        }
    }
}
