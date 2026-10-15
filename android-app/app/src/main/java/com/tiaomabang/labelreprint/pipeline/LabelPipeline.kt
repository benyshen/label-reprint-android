package com.tiaomabang.labelreprint.pipeline

import android.graphics.Bitmap
import com.tiaomabang.labelreprint.barcode.BarcodeEngine
import com.tiaomabang.labelreprint.model.DetectedBarcode
import com.tiaomabang.labelreprint.model.ElementKind
import com.tiaomabang.labelreprint.model.LabelElement
import com.tiaomabang.labelreprint.model.ReprintPlan
import com.tiaomabang.labelreprint.zpl.ZplBuilder
import com.tiaomabang.labelreprint.zpl.ZplGeometry
import kotlin.math.abs
import kotlin.math.min

/** 一个纯文本识别结果（OCR 行 / 手输行），与条码统一到同一坐标系 */
data class TextLine(val text: String, val px: List<Float>, val conf: Float = 1f, val source: String = "ocr")

/**
 * 照片 -> ZPL 的主链。对应参考实现 `tools/label_to_zpl.py: build()`。
 * OCR 引擎以函数注入（stage B 接 ONNX PP-OCRv4），所以先能用条码单独验证几何部分。
 */
object LabelPipeline {

    /** OCR 行置信度门槛，与 PC 端 `label_to_zpl.py: ocr_lines(min_conf=0.5)` 同值 */
    const val MIN_OCR_CONF = 0.5f

    fun run(
        bitmap: Bitmap,
        engine: BarcodeEngine,
        template: Int = ZplGeometry.DEFAULT_TEMPLATE,
        basis: String = "full",
        ocr: ((Bitmap) -> List<TextLine>)? = null,
    ): ReprintPlan {
        val imgW = bitmap.width
        val imgH = bitmap.height
        val gray = ImageMeasure.toGray(bitmap)

        val barcodes = engine.decode(bitmap).map { b ->
            if (b.isTwoD) b else {
                // 一维码的条高必须自己量（见 ImageMeasure 的说明）
                val rows = ImageMeasure.barRows(gray, imgW, imgH,
                    b.px[0].toInt(), b.px[2].toInt(), ((b.px[1] + b.px[3]) / 2).toInt())
                if (rows == null) b
                else b.copy(px = listOf(b.px[0], rows.first.toFloat(), b.px[2], rows.second.toFloat()))
            }
        }
        val elements = ArrayList<LabelElement>()
        val dropped = ArrayList<String>()

        for (b in barcodes) {
            elements += LabelElement(
                kind = if (b.isTwoD) ElementKind.BARCODE_2D else ElementKind.BARCODE_1D,
                text = b.text, format = b.format, px = b.px, source = b.source,
            )
        }

        val lines = ocr?.invoke(bitmap) ?: emptyList()
        val kept = ArrayList<TextLine>()
        for (ln in lines) {
            // 与 PC 端参考实现同一条门槛（label_to_zpl.py: min_conf=0.5）：DB 检测会把条码区域
            // 本身当成文本框，吐出来的常是空串或极低置信的乱码，先按置信度丢掉。
            if (ln.text.isBlank() || ln.conf < MIN_OCR_CONF) {
                dropped += "「${ln.text}」置信度 ${"%.2f".format(ln.conf)} < $MIN_OCR_CONF，丢弃"
                continue
            }
            val hit = elements.firstOrNull { overlapFraction(ln.px, it.px) >= 0.5f }
            if (hit != null) {
                dropped += "「${ln.text}」落在条码框内（是条码本身，不是文字）"
                continue
            }
            kept += ln
        }
        val rest = ArrayList<TextLine>()
        for (ln in kept) {
            val owner = elements.firstOrNull { b ->
                b.kind == ElementKind.BARCODE_1D && !b.hri &&
                    norm(ln.text) == norm(b.text) && belowAndOverlapping(ln.px, b.px)
            }
            if (owner != null) {
                val i = elements.indexOf(owner)
                elements[i] = owner.copy(hri = true, hriPx = ln.px)
                dropped += "「${ln.text}」是条码 ${owner.text} 的人眼可读串，归并进 ^BC 的明文行"
            } else {
                rest += ln
            }
        }
        for (ln in rest) {
            elements += LabelElement(
                kind = ElementKind.TEXT, text = ln.text, px = ln.px,
                conf = ln.conf, source = ln.source,
            )
        }

        // 像素 -> 点阵。基准：label/full 时照片本身就是标签，边距必须为 0，
        // 否则会整体偏移（实测 16~22 点 = 2mm+）
        val laid = layout(elements, basis, imgW, imgH, template)

        return ReprintPlan(
            imageName = "", imageSize = imgW to imgH, basis = basis, template = template,
            labelDots = laid.labelDots, scale = laid.scale, elements = laid.elements,
            dropped = dropped, warnings = laid.warnings, zpl = laid.zpl, zplErrors = laid.errors,
        )
    }

    /**
     * 只重排版、重出 ZPL，不再碰 OCR/解码 —— 用户改过要素内容/码制/删了要素、或换了打印模板
     * 之后走这条路。坐标从 [LabelElement.px]（照片像素，永远保留）重新映射，所以对同一份
     * 要素表反复调用是幂等的（elements 传进来时 dots 是多少都不影响结果）。
     */
    fun remap(
        plan: ReprintPlan,
        elements: List<LabelElement> = plan.elements,
        template: Int = plan.template,
    ): ReprintPlan {
        val (imgW, imgH) = plan.imageSize
        val laid = layout(elements, plan.basis, imgW, imgH, template)
        return plan.copy(
            template = template, labelDots = laid.labelDots, scale = laid.scale,
            elements = laid.elements, warnings = laid.warnings, zpl = laid.zpl, zplErrors = laid.errors,
        )
    }

    private class Laid(
        val elements: List<LabelElement>,
        val scale: Float,
        val labelDots: Pair<Int, Int>,
        val zpl: String,
        val warnings: List<String>,
        val errors: List<String>,
    )

    /** 像素框 -> 点阵框 + ZPL。两端（run 和 remap）共用，避免两套映射逻辑慢慢走偏。 */
    private fun layout(
        elements: List<LabelElement>,
        basis: String,
        imgW: Int,
        imgH: Int,
        template: Int,
    ): Laid {
        val margin = if (basis == "content") ZplGeometry.MARGIN else 0
        val topOffset = if (basis == "content") ZplGeometry.TOP_OFFSET else 0
        val basisRect = if (basis == "content") contentBBox(elements)
        else listOf(0f, 0f, imgW.toFloat(), imgH.toFloat())

        val (labelW, labelH) = ZplGeometry.dims(template)
        val cw = maxOf(1e-6f, basisRect[2] - basisRect[0])
        val ch = maxOf(1e-6f, basisRect[3] - basisRect[1])
        val usableW = labelW - 2 * margin
        val usableH = labelH - 2 * margin - topOffset
        val scale = min(usableW / cw, usableH / ch)
        val dx = margin + (usableW - cw * scale) / 2f
        val dy = topOffset + margin + (usableH - ch * scale) / 2f

        val mapped = elements.map { e ->
            e.withDots(
                dx + (e.px[0] - basisRect[0]) * scale,
                dy + (e.px[1] - basisRect[1]) * scale,
                dx + (e.px[2] - basisRect[0]) * scale,
                dy + (e.px[3] - basisRect[1]) * scale,
            )
        }

        // 先让开条码再出 ZPL：字符紧贴条码的净空必须在版面阶段就补出来
        val (fixed, shiftWarnings) = deCollide(mapped, labelW, labelH)
        val (zpl, buildWarnings) = ZplBuilder.build(fixed, template)
        val errors = ZplValidator.check(zpl, template, fixed)
        return Laid(fixed, scale, labelW to labelH, zpl, buildWarnings + shiftWarnings, errors)
    }

    /**
     * 文字与条码的净空。
     *
     * 为什么要单开一步：源图里"条码下方的人眼可读串"本来只离开条码 3~4 点（0.4mm）——
     * 真实药品追溯码标签实测（2449×3265 照片，条码框底 180.3 点、文字框顶 183.8 点），映射到点阵
     * 后再加上 ^FT"下边缘锚定"，文字就直接贴到条上了（用户报"条码下面的文字和条码重叠"）。
     * 只向下推文字，**不动条码、不改横向位置** —— 挪条码会让整枚标签的基准整体跑掉。
     */
    private fun deCollide(
        elements: List<LabelElement>,
        labelW: Int,
        labelH: Int,
    ): Pair<List<LabelElement>, List<String>> {
        val bars = elements.filter { it.kind != ElementKind.TEXT }
        if (bars.isEmpty()) return elements to emptyList()
        val warns = ArrayList<String>()
        val out = elements.map { e ->
            if (e.kind != ElementKind.TEXT) {
                e
            } else {
                var shift = 0
                for (b in bars) {
                    // 横向得有重叠才谈得上"上下相邻"
                    val hov = min(b.dots[2], e.dots[2]) - maxOf(b.dots[0], e.dots[0])
                    if (hov <= 0.2f * (e.dots[2] - e.dots[0])) continue
                    // 只处理文字在条码下方的情形（推上方文字会撞到"下边缘锚定"的语义）
                    if (e.dots[1] < b.dots[3]) continue
                    val need = b.dots[3] + ZplGeometry.MIN_TEXT_BARCODE_GAP
                    if (e.dots[1] < need) shift = maxOf(shift, (need - e.dots[1]).toInt() + 1)
                }
                if (shift == 0) {
                    e
                } else {
                    // 不能推出标签底部（Builder 里那条底部保护带会把锚点再拉回来，等于没推）
                    val room = (labelH - ZplGeometry.GUARD - e.dots[3]).toInt()
                    val use = shift.coerceAtMost(maxOf(0, room))
                        .coerceAtMost(ZplGeometry.DECOLLIDE_STEP_LIMIT)
                    if (use <= 0) {
                        warns += "文字「${e.text}」紧贴条码但下方已无空间，未能让开（可能重叠）"
                        e
                    } else {
                        warns += "文字「${e.text}」与上方条码净空不足，下移 $use 点"
                        e.shiftDots(0f, use.toFloat())
                    }
                }
            }
        }
        return out to warns
    }

    // ---- helpers (mirror of the Python versions) ----
    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** a 有多大部分落在 b 里 */
    private fun overlapFraction(a: List<Float>, b: List<Float>): Float {
        val ix = maxOf(0f, min(a[2], b[2]) - maxOf(a[0], b[0]))
        val iy = maxOf(0f, min(a[3], b[3]) - maxOf(a[1], b[1]))
        val areaA = maxOf(1e-6f, (a[2] - a[0]) * (a[3] - a[1]))
        return ix * iy / areaA
    }

    private fun belowAndOverlapping(line: List<Float>, bc: List<Float>): Boolean {
        val bh = bc[3] - bc[1]
        val gap = line[1] - bc[3]
        if (gap < 0f || gap > 1.2f * bh) return false
        val hov = min(line[2], bc[2]) - maxOf(line[0], bc[0])
        return hov > 0.3f * (line[2] - line[0])
    }

    private fun contentBBox(elements: List<LabelElement>): List<Float> {
        if (elements.isEmpty()) return listOf(0f, 0f, 1f, 1f)
        return listOf(
            elements.minOf { it.px[0] }, elements.minOf { it.px[1] },
            elements.maxOf { it.px[2] }, elements.maxOf { it.px[3] },
        )
    }
}

/** 机器校验：纸面才发现的问题（越界静默不打印、^FD/^FS 不配对…）在这里拦下 */
object ZplValidator {

    fun check(zpl: String, template: Int, elements: List<LabelElement>): List<String> {
        val errs = ArrayList<String>()
        val (w, h) = ZplGeometry.dims(template)
        if (zpl.split("^XA").size - 1 != 1 || zpl.split("^XZ").size - 1 != 1) errs += "^XA/^XZ 不配对"
        if (!zpl.contains("^CI28")) errs += "缺少 ^CI28（非 ASCII 会乱码）"
        if (zpl.split("^FD").size != zpl.split("^FS").size) errs += "^FD/^FS 数量不等"
        listOf("^MMT", "^MMB", "^MNY", "^PR", "^MD").forEach {
            if (zpl.contains(it)) errs += "$it 不该由 App 下发（介质/速度/黑度是打印机设置）"
        }
        Regex("""\^FT(\d+),(\d+)""").findAll(zpl).forEach { m ->
            val x = m.groupValues[1].toInt()
            val y = m.groupValues[2].toInt()
            if (x < 0 || x >= w) errs += "^FT x=$x 越界 0..${w - 1}"
            if (y < 0 || y >= h) errs += "^FT y=$y 越界 0..${h - 1}"
        }
        for (e in elements) {
            if (e.kind == ElementKind.BARCODE_1D) {
                val modules = if (e.format.contains("128"))
                    ZplGeometry.code128Modules(e.text.length, e.text.all { it.isDigit() })
                else ZplGeometry.code39Modules(e.text.length)
                val mw = (e.w / modules).coerceIn(1f, 10f)
                if (ZplGeometry.roundHalf(e.dots[0].toDouble()) + modules * mw > w) {
                    errs += "条码「${e.text}」估算宽度超出标签（会打到纸外）"
                }
                // Code 39 只支持大写字母、数字和 - . $ / + % 与空格；小写/中文会被打印机**静默忽略**，
                // 打出来是缺字符的条码（扫出来内容不对，比报错更危险）
                if (!e.format.contains("128") &&
                    !Regex("^[0-9A-Z\\-.\\$/+% ]*$").matches(e.text)
                ) {
                    errs += "Code 39 内容含非法字符（只允许 0-9 A-Z 与 -.\$/ +%），会被打印机静默丢弃"
                }
            }
        }
        return errs
    }
}
