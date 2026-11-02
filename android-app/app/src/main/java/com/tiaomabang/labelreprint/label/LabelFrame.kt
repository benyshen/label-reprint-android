package com.tiaomabang.labelreprint.label

import android.graphics.Bitmap
import android.graphics.Matrix
import com.tiaomabang.labelreprint.zpl.ZplGeometry
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import com.tiaomabang.labelreprint.pipeline.ImageMeasure
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 照片里"标签在哪儿" + 像素 -> 点阵的基准。镜像 `tools/label_frame.py`。
 *
 * 为什么必须有这一步：`basis=full`（把整张照片铺到标签上）只在**照片已经裁到标签边缘**时才对。
 * 实测（用户拍的药品追溯码标签，2449×3265 竖幅）：标签只占画面中间一块，按 full 映射后
 * 标签内容只占 2449×0.098≈239 点的宽（应该 480 点），**整体缩了一半**，条码和文字都偏小 ——
 * 而且条码模块宽被算成 1 点，203dpi 下印成一团糊，扫描器退化判成 Code 39。
 * 检测出标签四边形、把它校正成正面图，再按"标签本身"映射，才是尺度正确的做法。
 */
object LabelFrame {

    /** 检测用的工作分辨率；大图先缩小，否则 findContours 在 2400 万像素上很慢 */
    private const val DETECT_LONG_SIDE = 1200.0

    /**
     * OpenCV 是按需装载的（原生库 20MB+）。OcrEngine 里已经有一处 `initLocal()`，
     * 但 OCR 关掉时那条路不走，这里再兜一次 —— `initLocal()` 重复调用是安全的（已装载直接返回 true）。
     */
    private val cvReady: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrElse { false } }

    /** 校正后标签图的长边像素（够 OCR 用，又不至于让后续解码太慢） */
    private const val WARP_LONG_SIDE = 1600.0

    /** 四边形至少占画面 20% 才认；太小多半是标签上的某个方框（二维码/色块） */
    private const val MIN_AREA_FRAC = 0.20

    /**
     * 面积 ≥ 92% 画面的候选**不是标签，是背景本身**。
     *
     * 为什么必须排除：暗底拍白标签时，"深色桌面"在反向二值化里是一个比标签更大的连通域，
     * 面积最大 -> 被当成标签，整张画面被"校正"成标签（真机实测 quad=(0,0)…(1888,2518)）。
     * 标签占了 92% 以上画面意味着照片本来就已裁到标签边，退回 `full` 结果等价，所以直接丢不亏。
     * （PC 端 `label_frame.py` 因为 `bitwise_not` 把 INV 又反了回去，从没走到这一路，这个坑只在
     * 安卓端正确实现极性后才暴露。）
     */
    private const val MAX_AREA_FRAC = 0.92

    data class Prep(
        /** 校正（并可能转了 90°）后的标签图；失败时就是原图 */
        val working: Bitmap,
        /** "label" = 已裁到标签边；"full" = 原图直接用 */
        val basis: String,
        /** 按标签长宽比选出的模板 */
        val template: Int,
        /** 检出四边形的原图坐标 [tl,tr,br,bl]，未检出为 null */
        val quad: List<Pair<Double, Double>>?,
        /** 是否为了对上模板方向转了 90° */
        val rotated90: Boolean,
        /**
         * 取景框在原图中的矩形 [x0,y0,x1,y1]（像素）。**只在没检出标签四边形时有值**：
         * 检出时 [working] 已被校正成标签本身，取景框不再代表内容范围。
         */
        val frameRect: FloatArray? = null,
        /**
         * 印刷边框在**校正图**里的位置（占画布比例 [左,上,右,下]）。只有 basis="quad" 时有值：
         * 四点定位已经把边框拉到这个位置上，流水线直接用，不必再"猜"边线。
         */
        val frameInset: FloatArray? = null,
        val note: String,
    )

    /**
     * @param pinnedTemplate 用户在界面上手动选过的模板；给了就完全以他为准，不自动选、不转方向
     */
    fun prepare(bitmap: Bitmap, pinnedTemplate: Int? = null): Prep {
        if (!cvReady) {
            return Prep(
                working = bitmap, basis = "full",
                template = pinnedTemplate ?: ZplGeometry.templateForAspect(bitmap.width, bitmap.height),
                quad = null, rotated90 = false,
                note = "OpenCV 未装载，跳过标签边框检测（按整幅图映射）",
            )
        }
        // 纸边优先（实测这张 9 个要素、只差纵向压缩），四点定位退为兜底：
        // 预先把四点定位跑在纸边检测之前会挑到框内/托盘的正确率更差（实测长宽比 0.39、
        // 要素 107 个、模块宽掉到 0.9 点）。
        val quad = detectQuad(bitmap)
        if (quad == null) {
            // 纸边检不出（白纸贴浅色背景，对比度太低；实测相机照片必挂）：改用**印刷黑框**
            // 的四点定位。印刷框是闭合深色矩形，findContours 一抓一个准，而且它才是复刻的
            // 真实坐标参照 —— 纸边多出来的撕裂边/白边本来就不该参与映射。
            val fq = detectFrameQuadChecked(bitmap)
            if (fq != null) {
                val warped = warpFrameQuad(bitmap, fq)
                return Prep(
                    working = warped, basis = "quad",
                    template = pinnedTemplate ?: ZplGeometry.templateForAspect(warped.width, warped.height),
                    quad = fq, rotated90 = false,
                    frameInset = FRAME_INSET.copyOf().let { d -> FloatArray(d.size) { d[it].toFloat() } },
                    note = "纸边未检出，改用印刷边框四点定位（已按模板内缩拉正）",
                )
            }
            // 没检出标签边框：把**取景框**矩形交给流水线，让它丢掉框外的识别结果。
            // 为什么不直接裁剪像素：标签长宽比和模板不一致时（比如 2.4:1 的条带放进 3:2 的框），
            // 按框裁会把标签两端切掉 —— 少识别几个字可以重拍，缺了条码右半截就只能重来。
            // 丢结果的判据是"要素中心是否在框内"，框外还留着像素但不参与要素表。
            val tpl = pinnedTemplate ?: ZplGeometry.templateForAspect(bitmap.width, bitmap.height)
            val r = CaptureFrame.rect(ZplGeometry.aspectOf(tpl), bitmap.width.toFloat(), bitmap.height.toFloat())
            return Prep(
                working = bitmap, basis = "full", template = tpl,
                quad = null, rotated90 = false,
                frameRect = r,
                note = "没检出标签边框：按取景框 ${r[2].toInt() - r[0].toInt()}x${r[3].toInt() - r[1].toInt()} 取内容（框外不参与识别）",
            )
        }

        var warped = warpQuad(bitmap, quad)
        val ar = warped.width.toDouble() / warped.height

        // 用**标签**的长宽比选模板 —— 这才是长宽比的正确来源（照片比例里混进了背景）
        val tpl = pinnedTemplate ?: ZplGeometry.templateForAspectRatio(ar)

        // 标签长宽比更接近模板的"转置"，说明拍的时候手机转了 90°：把内容转正，
        // 否则标签的长边会被塞进模板的短边，又是整体缩小。
        var rotated = false
        val tplAr = ZplGeometry.aspectRatio(tpl)
        if (pinnedTemplate == null && abs(ln(ar / tplAr)) > abs(ln(ar / (1.0 / tplAr)))) {
            warped = rotate90(warped)
            rotated = true
        }
        return Prep(
            working = warped, basis = "label", template = tpl, quad = quad, rotated90 = rotated,
            note = "检出标签 ${"%.0f".format(ar * 100)}% 长宽比，校正为 ${warped.width}×${warped.height}" +
                (if (rotated) "（转了 90°）" else ""),
        )
    }

    /**
     * 手动四点标定（移植自 doc-scan-demo 已真机验证的手动平面矫正）：
     * 用户拖的四个角点（点按顺序任意）→ 透视校正成正视图 → 条码/OCR/框体识别都在这张图上跑。
     *
     * 与自动路径的差别只有"四边形从哪来"：这里不再猜纸边/印刷框，用户标的就是标签本身 ——
     * 白纸贴浅色背景、透明托盘、反光这些自动检测必挂的场景，手动四点一次就对。
     * 校正后 basis 记 "label"（working 已是标签正面图，与自动检出纸边后等价），
     * 下游 LabelPipeline 的映射、模板选择、ZPL 生成完全复用，不新增分支。
     */
    fun prepareManual(bitmap: Bitmap, quadPts: List<Point>, pinnedTemplate: Int? = null): Prep {
        // OpenCV 没装载 / 点没摆齐：退回自动检测，至少不比原来差
        if (!cvReady || quadPts.size != 4) return prepare(bitmap, pinnedTemplate)
        val ordered = orderQuad(quadPts)
        val quad = ordered.map { it.x to it.y }
        // 退化保护：短边 < 长边 3% 说明用户把两个点拖到一起了（或几乎共线），
        // 透视变换会把噪声放大成整幅色块 —— 这时退回自动检测，比出一张废图强。
        val diagLong = max(
            Math.hypot(quad[0].first - quad[2].first, quad[0].second - quad[2].second),
            Math.hypot(quad[1].first - quad[3].first, quad[1].second - quad[3].second),
        )
        val sideShort = min(
            Math.hypot(quad[0].first - quad[1].first, quad[0].second - quad[1].second),
            Math.hypot(quad[2].first - quad[3].first, quad[2].second - quad[3].second),
        )
        if (diagLong <= 0.0 || sideShort < diagLong * 0.03) return prepare(bitmap, pinnedTemplate)

        var warped = warpQuad(bitmap, quad)
        val ar = warped.width.toDouble() / warped.height
        val tpl = pinnedTemplate ?: ZplGeometry.templateForAspectRatio(ar)
        var rotated = false
        val tplAr = ZplGeometry.aspectRatio(tpl)
        if (pinnedTemplate == null && abs(ln(ar / tplAr)) > abs(ln(ar / (1.0 / tplAr)))) {
            warped = rotate90(warped)
            rotated = true
        }
        return Prep(
            working = warped, basis = "label", template = tpl, quad = quad, rotated90 = rotated,
            note = "手动四点标定，校正为 ${warped.width}×${warped.height}" +
                (if (rotated) "（转了 90°）" else ""),
        )
    }

    /**
     * 四点自检：正常返回 null，有问题返回给用户看的原因。
     * 自交/凹四边形会让透视变换输出畸形图（用户看到"出来的不是我选的区域"就是这个），
     * 面积过小则是四个点挤在一起。
     */
    fun quadProblem(pts: List<Point>): String? {
        if (pts.size != 4) return "需要正好 4 个点"
        val q = orderQuad(pts)
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cr = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cr) < 1e-6) continue
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return "四点交叉/凹陷（不是凸四边形）"
        }
        var area = 0.0
        for (i in 0 until 4) {
            val p = q[i]; val n = q[(i + 1) % 4]
            area += p.x * n.y - n.x * p.y
        }
        if (abs(area) / 2.0 < 1000.0) return "四个点挤在一起（面积太小）"
        return null
    }

    /**
     * 任意顺序的四个点 → [tl,tr,br,bl]：左上 = x+y 最小、右下 = x+y 最大、
     * 右上 = x−y 最大、左下 = x−y 最小。与 doc-scan-demo / PC 参考实现同一判据。
     */
    fun orderQuad(pts: List<Point>): List<Point> {
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.maxBy { it.x - it.y }
        val bl = pts.minBy { it.x - it.y }
        return listOf(tl, tr, br, bl)
    }

    /** 最大的亮/暗四边形（面积 >= 帧的 20%），找不到返回 null。镜像 label_frame.py: detect_label_quad。 */
    /** 印刷边框在校正图里的位置（占画布比例 左,上,右,下）。取自已验证的 PC 输出：
     *  ^FO51..516 / ^FT111..483（559 点画布）-> [.0912, .1986, .9231, .8640] */
    private val FRAME_INSET = doubleArrayOf(0.0912, 0.1986, 0.9231, 0.8640)
    /**
     * detectFrameQuad 的自检包装：四边形必须像标签才认。
     * 真机踩过：候选组合偶尔会给出长宽比 0.39 / 0.89 的畸形梯形（四边长 782/503/329/696），
     * 拉正后要素从 9 个涨到 116 个、条码模块宽掉到 0.9 点 —— 不如不认。
     */
    fun detectFrameQuadChecked(src: Bitmap): List<Pair<Double, Double>>? {
        val q = detectFrameQuad(src) ?: return null
        val sides = listOf(
            dist(q[0], q[1]), dist(q[1], q[2]), dist(q[2], q[3]), dist(q[3], q[0]),
        )
        if (sides.min() < 1.0) return null
        if (sides.max() / sides.min() > 1.35) return null        // 四边长短差太多 = 畸形
        val wAvg = (sides[0] + sides[2]) / 2.0
        val hAvg = (sides[1] + sides[3]) / 2.0
        val ar = max(wAvg, hAvg) / max(1.0, min(wAvg, hAvg))
        if (ar < 1.20 || ar > 1.52) return null                  // 印刷框真实 1.25
        return q
    }

    /** 印刷框真实长宽比：PC 已验证输出里 ^FO51..516 / ^FT111..483 -> 465/372 */
    private const val FRAME_AR = 1.357f   // 63.2mm x 46.6mm（旧值 1.25 是横向被压缩 8.6% 的假值）

    /**
     * 印刷黑框的四点定位：多阈值 + RETR_LIST + 长宽比筛选 + 打分。
     *
     * 为什么不用现有 [detectQuad]：它用 Otsu 单阈值 + RETR_EXTERNAL 取最大轮廓，
     * 白纸贴浅蓝布的相机照片上 Otsu 把纸和背景分到一起，标签轮廓直接消失（真机实测
     * "没检出标签边框"）。固定多档阈值 + LIST 模式能稳定抓到那个闭合黑框。
     */
    /**
     * 印刷黑框的四点定位（v4）：长直线两两组合候选矩形，按面积**从小到大**取第一个
     * 长宽比符合模板的，再对四条边做最小二乘拟合求交点。
     *
     * 为什么不用闭合轮廓：真机照片上它抓到的是透明塑料盒（盒边 ar 1.248 恰好落在筛选区间），
     * 盒边和印刷框不平行，拉正后文字/条码全是歪的。为什么不用 Otsu 单阈值：白纸贴浅蓝布
     * 时纸和背景被分到一起，标签轮廓直接消失。
     * 为什么面积升序：托盘/背景都在印刷框外面、面积更大；框内各单元格长宽比都极端
     * （品名格 6.0、条码格 3.3），不会落进 1.15~1.35。
     */
    fun detectFrameQuad(src: Bitmap): List<Pair<Double, Double>>? {
        if (src.width < 64 || src.height < 64) return null
        val bmp = if (src.config != Bitmap.Config.ARGB_8888) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val k = min(1.0, DETECT_LONG_SIDE / max(bmp.width, bmp.height).toDouble())
        val small = if (k < 1.0) {
            Bitmap.createScaledBitmap(
                bmp, max(64, (bmp.width * k).toInt()), max(64, (bmp.height * k).toInt()), true,
            )
        } else bmp
        val sw = small.width
        val sh = small.height
        val gray = ImageMeasure.toGray(small)
        val rules = ImageMeasure.detectRules(gray, sw, sh)
        val hs = rules.filter { it[2] - it[0] >= it[3] - it[1] && it[2] - it[0] >= 0.35f * sw }
        val vs = rules.filter { it[2] - it[0] < it[3] - it[1] && it[3] - it[1] >= 0.30f * sh }
        if (hs.size < 2 || vs.size < 2) return null

        class Cand(
            val area: Float, val dar: Float,
            val top: List<Float>, val bot: List<Float>,
            val left: List<Float>, val right: List<Float>,
        )
        val cands = ArrayList<Cand>()
        for (i in hs.indices) for (j in i + 1 until hs.size) {
            val a = hs[i]; val b = hs[j]
            val top = if (a[1] + a[3] < b[1] + b[3]) a else b
            val bot = if (a[1] + a[3] < b[1] + b[3]) b else a
            for (p in vs.indices) for (q in p + 1 until vs.size) {
                val l = vs[p]; val r = vs[q]
                val left = if (l[0] + l[2] < r[0] + r[2]) l else r
                val right = if (l[0] + l[2] < r[0] + r[2]) r else l
                val bw = right[2] - left[0]
                val bh = bot[3] - top[1]
                if (bw <= 0f || bh <= 0f) continue
                val area = bw * bh / (sw.toFloat() * sh)
                if (area < 0.20f || area > 0.85f) continue
                val ar = bw / bh
                if (ar < 1.20f || ar > 1.52f) continue
                cands.add(Cand(area, Math.abs(ar - FRAME_AR), top, bot, left, right))
            }
        }
        cands.sortWith(compareBy({ it.area }, { it.dar }))
        for (c in cands) {
            val quad = cornersFromLines(gray, sw, sh, c.top, c.bot, c.left, c.right) ?: continue
            // 缩略图坐标 -> 原图坐标
            return quad.map { (it.first / k) to (it.second / k) }
        }
        return null
    }

    /** 四条边各自最小二乘拟合后求交点；透视下边是斜的，不能用 bbox 中线代替 */
    private fun cornersFromLines(
        gray: IntArray, w: Int, h: Int,
        top: List<Float>, bot: List<Float>, left: List<Float>, right: List<Float>,
    ): List<Pair<Double, Double>>? {
        val ft = fitLine(gray, w, h, top, true) ?: return null
        val fb = fitLine(gray, w, h, bot, true) ?: return null
        val fl = fitLine(gray, w, h, left, false) ?: return null
        val fr = fitLine(gray, w, h, right, false) ?: return null
        fun inter(lh: Pair<Double, Double>, lv: Pair<Double, Double>): Pair<Double, Double>? {
            val den = 1.0 - lv.first * lh.first
            if (Math.abs(den) < 1e-6) return null
            val x = (lv.first * lh.second + lv.second) / den
            return x to (lh.first * x + lh.second)
        }
        return listOfNotNull(
            inter(ft, fl), inter(ft, fr), inter(fb, fr), inter(fb, fl),
        ).takeIf { it.size == 4 }
    }

    /** horiz=true: 按列取墨迹中位行, 拟合 y=a*x+b；false: 按行取中位列, 拟合 x=a*y+b */
    private fun fitLine(
        gray: IntArray, w: Int, h: Int, b: List<Float>, horiz: Boolean,
    ): Pair<Double, Double>? {
        val a0 = max(0, b[0].toInt()); val a1 = min(if (horiz) w - 1 else h - 1, (if (horiz) b[2] else b[3]).toInt())
        val c0 = max(0, b[1].toInt()); val c1 = min(if (horiz) h - 1 else w - 1, (if (horiz) b[3] else b[2]).toInt())
        val pad = max(2, c1 - c0)
        val lo = max(0, c0 - pad); val hi = min(if (horiz) h - 1 else w - 1, c1 + pad)
        var n = 0.0; var su = 0.0; var sv = 0.0; var suu = 0.0; var suv = 0.0
        var u = a0
        while (u <= a1) {
            val hits = ArrayList<Int>()
            var v = lo
            while (v <= hi) {
                val px = if (horiz) gray[v * w + u] else gray[u * w + v]
                if (px < 180) hits.add(v)
                v++
            }
            if (hits.isNotEmpty()) {
                val mv = hits[hits.size / 2].toDouble()
                n++; su += u; sv += mv; suu += u.toDouble() * u; suv += u * mv
            }
            u += 2
        }
        if (n < 8) return null
        val den = n * suu - su * su
        if (Math.abs(den) < 1e-9) return null
        val slope = (n * suv - su * sv) / den
        return slope to ((sv - slope * su) / n)
    }

    /** 把印刷边框拉到 [FRAME_INSET] 指定的位置上（1000x1000 画布），还原成纸面坐标 */
    fun warpFrameQuad(src: Bitmap, quad: List<Pair<Double, Double>>): Bitmap {
        // 画布必须是纸张真实点阵 608x560（76x70mm @8点/mm）；用 1000x1000 正方形
        // 会把 1.086 的纸压成 1.0，横向再丢 8.6%。
        val nW = 608.0
        val nH = 560.0
        val s = MatOfPoint2f(
            Point(quad[0].first, quad[0].second), Point(quad[1].first, quad[1].second),
            Point(quad[2].first, quad[2].second), Point(quad[3].first, quad[3].second),
        )
        val d = MatOfPoint2f(
            Point(FRAME_INSET[0] * nW, FRAME_INSET[1] * nH), Point(FRAME_INSET[2] * nW, FRAME_INSET[1] * nH),
            Point(FRAME_INSET[2] * nW, FRAME_INSET[3] * nH), Point(FRAME_INSET[0] * nW, FRAME_INSET[3] * nH),
        )
        val bmp = if (src.config != Bitmap.Config.ARGB_8888) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val m = Imgproc.getPerspectiveTransform(s, d)
        val out = Mat()
        Imgproc.warpPerspective(rgba, out, m, Size(nW, nH), Imgproc.INTER_CUBIC)
        val bm = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bm)
        rgba.release(); out.release()
        return bm
    }

    fun detectQuad(src: Bitmap): List<Pair<Double, Double>>? {
        if (src.width < 32 || src.height < 32) return null
        val bmp = if (src.config != Bitmap.Config.ARGB_8888) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else src
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)

        val k = min(1.0, DETECT_LONG_SIDE / max(rgba.width(), rgba.height()).toDouble())
        val small = Mat()
        if (k < 1.0) Imgproc.resize(rgba, small, Size(rgba.width() * k, rgba.height() * k), 0.0, 0.0, Imgproc.INTER_AREA)
        else rgba.copyTo(small)

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val frameArea = gray.width().toDouble() * gray.height()
        val elem = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        var best: Pair<Double, Array<Point>>? = null

        // 两个极性都试：标签是白底就给 THRESH_BINARY，暗底白签就给 INV
        for (invert in listOf(false, true)) {
            val th = Mat()
            Imgproc.threshold(
                gray, th, 0.0, 255.0,
                if (invert) Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU
                else Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU,
            )
            Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, elem)

            val contours = ArrayList<MatOfPoint>()
            val hier = Mat()
            Imgproc.findContours(th, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            for (c in contours) {
                val a = Imgproc.contourArea(c)
                if (a < MIN_AREA_FRAC * frameArea) continue
                // 见 MAX_AREA_FRAC：面积接近整幅画面的候选是背景，不是标签
                if (a >= MAX_AREA_FRAC * frameArea) continue
                val p2 = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(p2, true)
                for (eps in listOf(0.02, 0.03, 0.05)) {
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(p2, approx, eps * peri, true)
                    val pts = approx.toArray()
                    if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) {
                        if (best == null || a > best!!.first) best = a to pts
                        break
                    }
                }
            }
            th.release(); hier.release()
            contours.forEach { it.release() }
        }
        rgba.release(); small.release(); gray.release()

        val cand = best ?: return null
        val ordered = orderQuad(cand.second)
        // 退化成一条线的四边形（贴着画面边缘）不算
        val wpx = (dist(ordered[0], ordered[1]) + dist(ordered[3], ordered[2])) / 2.0
        val hpx = (dist(ordered[0], ordered[3]) + dist(ordered[1], ordered[2])) / 2.0
        if (min(wpx, hpx) < 0.15 * max(wpx, hpx)) return null
        // 缩回原图坐标
        val inv = 1.0 / k
        return ordered.map { p -> (p.x * inv) to (p.y * inv) }
    }

    /** 四边形 -> 正面图（透视校正，长边固定 WARP_LONG_SIDE）。镜像 label_frame.py: warp_quad。 */
    fun warpQuad(src: Bitmap, quad: List<Pair<Double, Double>>): Bitmap {
        val bmp = if (src.config != Bitmap.Config.ARGB_8888) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else src
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)

        val tl = quad[0]; val tr = quad[1]; val br = quad[2]; val bl = quad[3]
        val wpx = max(dist(tl, tr), dist(bl, br))
        val hpx = max(dist(tl, bl), dist(tr, br))
        val scale = WARP_LONG_SIDE / max(wpx, hpx)
        val w = max(16, (wpx * scale).roundToInt())
        val h = max(16, (hpx * scale).roundToInt())

        val s = MatOfPoint2f(
            Point(tl.first, tl.second), Point(tr.first, tr.second),
            Point(br.first, br.second), Point(bl.first, bl.second),
        )
        val d = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(s, d)
        val out = Mat()
        Imgproc.warpPerspective(rgba, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC)
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, res)
        rgba.release(); out.release()
        return res
    }

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // ---- helpers ----

    /** [tl, tr, br, bl]：x+y 最小=左上，x-y 最大=右上（与 OpenCV/Python 两端同一套判据） */
    private fun orderQuad(q: Array<Point>): Array<Point> {
        val s = q.map { it.x + it.y }
        val d = q.map { it.x - it.y }
        return arrayOf(
            q[s.indices.minBy { s[it] }],
            q[d.indices.maxBy { d[it] }],
            q[s.indices.maxBy { s[it] }],
            q[d.indices.minBy { d[it] }],
        )
    }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        sqrt((a.first - b.first) * (a.first - b.first) + (a.second - b.second) * (a.second - b.second))

    private fun dist(a: Point, b: Point): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
}
