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
        val quad = detectQuad(bitmap)
        if (quad == null) {
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

    /** 最大的亮/暗四边形（面积 >= 帧的 20%），找不到返回 null。镜像 label_frame.py: detect_label_quad。 */
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
