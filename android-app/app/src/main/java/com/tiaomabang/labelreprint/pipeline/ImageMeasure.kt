package com.tiaomabang.labelreprint.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * 从位图上直接量几何（镜像 `tools/label_to_zpl.py` 里的同名函数）。
 *
 * 为什么必须自己量：zxing-cpp 的 Android 封装对**一维码**返回的是扫描线而不是符号包围盒
 * （实测 options 各种组合：minLineCount=1 -> 1 点高、=2 -> 2 点高、=10 -> 直接不出结果；
 * 2x 放大仍是 2 点高；同一库同一版本在 Python 端返回的是完整框）。照它给的框生成 ZPL，
 * 条高就是 2 点 —— 打出来只剩一条细线。
 */
object ImageMeasure {

    private const val TAG = "LABELREPRINT"

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
    /**
     * 框线识别入口：Hough 找线 → 端点 → 垂直剖面校验 → 聚成表格（详见 houghRules 的注释）。
     * 一条线都找不到时回退旧实现，保证干净扫描件上的老行为不变。
     */
    fun detectRules(gray: IntArray, w: Int, h: Int, excludePx: List<List<Float>> = emptyList()): List<List<Float>> {
        val t0 = System.currentTimeMillis()
        val ink = inkMask(gray, w, h)
        // 纸面亮度门槛只算一次：放进 verifyRule 里逐条线重算会把整幅图排序几十次，
        // 真机上直接卡到 ANR（实测 991x1600 一百多条候选）。
        val lvl = paperLevel(gray)
        val hs = dropExcluded(houghRules(ink, gray, w, h, true, lvl), excludePx, w)
        val vs = dropExcluded(houghRules(ink, gray, w, h, false, lvl), excludePx, w)
        val grid = buildGrid(hs, vs, w, h)
        Log.i(TAG, "RULES >>> H=${hs.size} V=${vs.size} box=${grid.size} " +
            "${System.currentTimeMillis() - t0}ms h=" + hs.joinToString(",") { "${it.pos.toInt()}" } +
            " v=" + vs.joinToString(",") { "${it.pos.toInt()}" } +
            " grid=" + grid.joinToString(" ") { "${it[0].toInt()},${it[1].toInt()}-${it[2].toInt()},${it[3].toInt()}" })
        // 找不到表格就返回空：旧启发式在真机照片上会把纸箱/托盘的暗行全当框线，
        // 实测一张图吐 71 个要素，比"少画几根线"糟得多。干净扫描件的回归由内置样图覆盖。
        return grid
    }

    /** 纸面亮度门槛：抽样取 60 分位再打 0.86 折（曝光偏暗的现场照片纸面只有 150 上下） */
    private fun paperLevel(gray: IntArray): Int {
        val stride = 8
        val n = gray.size / stride + 1
        val vals = IntArray(n)
        var i = 0
        var k = 0
        while (i < gray.size) { vals[k++] = gray[i]; i += stride }
        java.util.Arrays.sort(vals)
        return (0.86f * vals[(k * 0.6f).toInt().coerceIn(0, k - 1)]).toInt()
    }

    /**
     * 剔掉落在排除区（条码框）里的线：条码的上/下沿是一条又长又黑的可疑横线，
     * 实测 224209 那张会被当成表格线打出来。
     */
    private fun dropExcluded(rules: List<Rule>, boxes: List<List<Float>>, w: Int): List<Rule> {
        if (boxes.isEmpty()) return rules
        return rules.filter { rl ->
            val len = rl.b - rl.a
            val covered = boxes.sumOf { bx ->
                if (bx.size < 4) 0.0 else {
                    val x0 = minOf(bx[0], bx[2]); val x1 = maxOf(bx[0], bx[2])
                    val y0 = minOf(bx[1], bx[3]); val y1 = maxOf(bx[1], bx[3])
                    if (rl.horiz) {
                        if (rl.pos < y0 - 4 || rl.pos > y1 + 4) 0.0
                        else (minOf(rl.b, x1) - maxOf(rl.a, x0)).coerceAtLeast(0f).toDouble()
                    } else {
                        if (rl.pos < x0 - 4 || rl.pos > x1 + 4) 0.0
                        else (minOf(rl.b, y1) - maxOf(rl.a, y0)).coerceAtLeast(0f).toDouble()
                    }
                }
            }
            covered < 0.5 * len
        }
    }

    /** 检测到的一条线（横线用 pos=y 中线，竖线用 pos=x 中线） */
    private class Rule(
        val horiz: Boolean,
        val theta: Double,
        val rho: Double,
        var a: Float,
        var b: Float,
    ) {
        var pos = 0f
    }

    /**
     * 自适应阈值墨迹图：局部均值 − c。比全局阈值抗反光/抗阴影。
     * 用积分图算局部均值，O(w·h)。
     */
    private fun inkMask(gray: IntArray, w: Int, h: Int, block: Int = 41, c: Int = 10): ByteArray {
        val r = block / 2
        val bw = w + 1
        val integ = LongArray(bw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            val base = y * w
            val ib = (y + 1) * bw
            val pb = y * bw
            for (x in 0 until w) {
                rowSum += gray[base + x].toLong()
                integ[ib + x + 1] = integ[pb + x + 1] + rowSum
            }
        }
        val ink = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r); val y1 = minOf(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r); val x1 = minOf(w - 1, x + r)
                val area = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
                val s = integ[(y1 + 1) * bw + x1 + 1] - integ[y0 * bw + x1 + 1] -
                    integ[(y1 + 1) * bw + x0] + integ[y0 * bw + x0]
                val mean = (s / area).toInt()
                if (gray[y * w + x] < mean - c) ink[y * w + x] = 1
            }
        }
        return ink
    }

    /**
     * Hough 找近水平（或近垂直）线。
     *
     * 为什么必须 Hough：现场照片里印刷表格线斜 2~6°，一条横线在**单独一行**里只剩下
     * 15~25% 的墨迹（其余部分跑到上下相邻行去了），所以"某行最长墨迹段 ≥22% 图宽"这种
     * 判据会 4 条线全漏（旧实现实测如此），反倒是深色背景的连续暗行满足条件，纸上打出一堆乱框。
     * Hough 在 (theta, rho) 空间累加，同一根线无论斜多少、中间断多少，都会汇到一个峰上。
     */
    private fun houghRules(ink: ByteArray, gray: IntArray, w: Int, h: Int, horiz: Boolean, lvl: Int): List<Rule> {
        val scale = 2
        val sw = w / scale
        val sh = h / scale
        if (sw < 32 || sh < 32) return emptyList()
        // 半分辨率墨迹点（2x2 块里有一个墨点就算）。用原始 int 数组：
        // 一开始用 ArrayList<Int>，40 万点装箱后在真机上 GC 抖动，光这一步就要十几秒。
        var px = IntArray(sw * sh / 4 + 16)
        var py = IntArray(sw * sh / 4 + 16)
        var n = 0
        for (y in 0 until sh) {
            val sy = y * scale
            for (x in 0 until sw) {
                val sx = x * scale
                var hit = false
                outer@ for (dy in 0 until scale) {
                    val row = (sy + dy) * w + sx
                    for (dx in 0 until scale) if (ink[row + dx].toInt() != 0) { hit = true; break@outer }
                }
                if (hit) {
                    if (n == px.size) { px = px.copyOf(n * 2); py = py.copyOf(n * 2) }
                    px[n] = x; py[n] = y; n++
                }
            }
        }
        if (n == 0) return emptyList()
        val rng = 10.0
        val step = 0.5
        val maxBase = (if (horiz) sh - 1 else sw - 1).toDouble()
        val maxOther = (if (horiz) sw - 1 else sh - 1).toDouble()
        val rhoMax = (maxBase + Math.tan(Math.toRadians(rng)) * maxOther).toInt() + 4
        val thr = maxOf(8, (0.16 * (if (horiz) sw else sh)).toInt())
        val pTheta = ArrayList<Double>()
        val pRho = ArrayList<Double>()
        val pCnt = ArrayList<Int>()
        var ang = -rng
        val counts = IntArray(rhoMax + 1)
        while (ang <= rng + 1e-9) {
            java.util.Arrays.fill(counts, 0)
            val tn = Math.tan(Math.toRadians(ang))
            for (i in 0 until n) {
                val base = if (horiz) py[i] else px[i]
                val other = if (horiz) px[i] else py[i]
                val rho = (base - tn * other).toInt()
                if (rho in 0..rhoMax) counts[rho]++
            }
            for (r in 0..rhoMax) {
                val cv = counts[r]
                if (cv < thr) continue
                var locMax = true
                for (d in -2..2) {
                    val rr = r + d
                    if (rr in 0..rhoMax && counts[rr] > cv) { locMax = false; break }
                }
                if (locMax) { pTheta.add(ang); pRho.add(r.toDouble() * scale); pCnt.add(cv) }
            }
            ang += step
        }
        if (pTheta.isEmpty()) return emptyList()
        // 只留最强的 40 个峰：文字行、条码竖条都会成峰，全量校验纯属浪费
        val order = pCnt.indices.sortedByDescending { pCnt[it] }.take(40)

        // 抽样墨迹点（全分辨率、隔点取）用于量端点
        var ax = IntArray(w * h / 16 + 16)
        var ay = IntArray(w * h / 16 + 16)
        var m = 0
        var yy = 0
        while (yy < h) {
            var xx = 0
            while (xx < w) {
                if (ink[yy * w + xx].toInt() != 0) {
                    if (m == ax.size) { ax = ax.copyOf(m * 2); ay = ay.copyOf(m * 2) }
                    ax[m] = xx; ay[m] = yy; m++
                }
                xx += scale
            }
            yy += scale
        }
        if (m == 0) return emptyList()
        val scratch = IntArray(m)
        val out = ArrayList<Rule>()
        val need = 0.2 * (if (horiz) w else h)
        for (pi in order) {
            val tn = Math.tan(Math.toRadians(pTheta[pi]))
            val rho = pRho[pi]
            var k = 0
            for (i in 0 until m) {
                val base = if (horiz) ay[i] else ax[i]
                val other = if (horiz) ax[i] else ay[i]
                if (Math.abs(base - tn * other - rho) <= 2.0) { scratch[k++] = if (horiz) ax[i] else ay[i] }
            }
            if (k < need) continue
            val s = scratch.copyOf(k)
            java.util.Arrays.sort(s)
            // 最长连续段：孤立墨点（条码、反光、纸边）会把跨度拉宽两三百像素
            val gapMax = maxOf(6, (0.02 * (if (horiz) w else h)).toInt())
            var bestLo = 0; var bestHi = -1; var start = 0
            for (i in 1..k) {
                if (i == k || s[i] - s[i - 1] > gapMax) {
                    if (s[i - 1] - s[start] > bestHi - bestLo) { bestLo = s[start]; bestHi = s[i - 1] }
                    start = i
                }
            }
            if (bestHi - bestLo < need) continue
            val rule = Rule(horiz, pTheta[pi], rho, bestLo.toFloat(), bestHi.toFloat())
            if (!verifyRule(gray, w, h, rule, lvl)) continue
            out.add(rule)
        }
        // 共线合并：按**中线位置**判同一根线。一开始比的是 (theta, rho)，同一条线被不同
        // 角度检出时 rho 会差二十几像素（rho 是截距，随角度变），合并不掉，实测一条线变三条。
        out.sortBy { it.pos }
        val mergeTol = maxOf(12f, 0.02f * h)
        val merged = ArrayList<Rule>()
        for (it in out) {
            val last = merged.lastOrNull()
            if (last != null && Math.abs(last.pos - it.pos) <= mergeTol &&
                (minOf(last.b, it.b) - maxOf(last.a, it.a)) > -0.15f * (it.b - it.a)
            ) {
                last.a = minOf(last.a, it.a); last.b = maxOf(last.b, it.b)
            } else merged.add(it)
        }
        return merged
    }

    /**
     * 校验：沿线段取若干样本，要求"线芯比两侧暗"。两侧取的是**垂直方向 ±7px** 的中位数，
     * 所以纸面明暗渐变不影响判断；而纸边/折痕的线芯并不比两侧暗，正好被挡掉。
     */
    private fun verifyRule(gray: IntArray, w: Int, h: Int, rl: Rule, lvl: Int, samples: Int = 41): Boolean {
        val tn = Math.tan(Math.toRadians(rl.theta))
        val cores = IntArray(samples)
        val sides = IntArray(samples)
        var k = 0
        for (i in 0 until samples) {
            val f = i / (samples - 1.0)
            val a = rl.a + f * (rl.b - rl.a)
            if (rl.horiz) {
                val y = (rl.rho + tn * a)
                if (y < 8 || y >= h - 9) continue
                val x = a.toInt()
                if (x < 0 || x >= w) continue
                val yi = Math.round(y).toInt()
                cores[k] = gray[yi * w + x]
                sides[k] = minOf(gray[(yi - 7) * w + x], gray[(yi + 7) * w + x])
            } else {
                val x = (rl.rho + tn * a)
                if (x < 8 || x >= w - 9) continue
                val y = a.toInt()
                if (y < 0 || y >= h) continue
                val xi = Math.round(x).toInt()
                cores[k] = gray[y * w + xi]
                sides[k] = minOf(gray[y * w + xi - 7], gray[y * w + xi + 7])
            }
            k++
        }
        if (k < 8 || k < 0.5 * samples) return false
        val core = median(cores, k)
        val side = median(sides, k)
        if (side <= lvl || core >= side - 12) return false
        rl.pos = (rl.rho + tn * ((rl.a + rl.b) / 2f)).toFloat()
        return true
    }

    private fun median(arr: IntArray, n: Int): Int {
        val s = arr.copyOf(n)
        java.util.Arrays.sort(s)
        return s[n / 2]
    }

    /**
     * 聚成表格：横线按跨度重叠聚簇 → 取中位跨度当左右边框 → 补齐缺失的竖线。
     *
     * 现实是现场照片有残余透视（左右竖边框倾斜甚至不一致），逐条还原畸变没有意义；
     * 原标签的表格本来就是矩形，所以按中位值重建成规整矩形，出来的 ZPL 才像标签。
     */
    private fun buildGrid(hs: List<Rule>, vs: List<Rule>, w: Int, h: Int): List<List<Float>> {
        if (hs.size < 2) return emptyList()
        val tol = maxOf(12f, 0.02f * h)
        val sorted = hs.sortedBy { it.pos }
        val base = sorted.maxByOrNull { it.b - it.a } ?: return emptyList()
        var cluster = sorted.filter {
            val ov = minOf(base.b, it.b) - maxOf(base.a, it.a)
            ov >= 0.5f * minOf(base.b - base.a, it.b - it.a)
        }.ifEmpty { sorted }.toMutableList()
        if (cluster.size < 3 && hs.size > cluster.size) cluster = sorted.toMutableList()
        cluster.sortBy { it.pos }
        val uniq = ArrayList<Rule>()
        for (it in cluster) {
            val last = uniq.lastOrNull()
            if (last != null && Math.abs(it.pos - last.pos) < tol) {
                if (it.b - it.a > last.b - last.a) uniq[uniq.size - 1] = it
            } else uniq.add(it)
        }
        val spanMed = uniq.map { it.b - it.a }.sorted()[uniq.size / 2]
        val keep = uniq.filter { it.b - it.a <= 1.15f * spanMed && it.pos > 0.02f * h && it.pos < 0.97f * h }
        if (keep.size < 2) return emptyList()
        val xl = keep.map { minOf(it.a, it.b) }.sorted()[keep.size / 2]
        val xr = keep.map { maxOf(it.a, it.b) }.sorted()[keep.size / 2]
        if (xr - xl < 0.15f * w) return emptyList()
        val yt = keep.minOf { it.pos }
        val yb = keep.maxOf { it.pos }
        val out = ArrayList<List<Float>>()
        for (it in keep) {
            out.add(listOf(xl, it.pos - 1.5f, xr, it.pos + 1.5f))
        }
        for (want in floatArrayOf(xl, xr)) {
            val cand = vs.filter {
                Math.abs(it.pos - want) <= 0.04f * w && it.b - it.a > 0.3f * h
            }.maxByOrNull { it.b - it.a }
            val x = cand?.pos ?: want
            out.add(listOf(x - 1.5f, yt, x + 1.5f, yb))
        }
        return out
    }

    fun detectRulesLegacy(gray: IntArray, w: Int, h: Int, excludePx: List<List<Float>> = emptyList()): List<List<Float>> {
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
