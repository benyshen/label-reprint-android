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
import kotlin.math.roundToInt

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
        /**
         * 取景框矩形 [x0,y0,x1,y1]（工作图像素）。非 null 时**框外的识别结果一律丢弃** ——
         * 用户把标签放进取景框就是在声明"要的是这一块"，框外的背景文字/条码（比如打印机上
         * 自带的标签）不该进要素表。只在没检出标签四边形时会传（检出时工作图就是标签本身）。
         */
        contentRect: FloatArray? = null,
        /** basis="quad" 时印刷边框在校正图里的位置（比例），四条边框直接由它给出 */
        frameInset: FloatArray? = null,
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

        // 取景框过滤：判据是要素**中心点**是否在框内 —— 压着框边的算框内，
        // 免得贴边摆放的标签被擦边误杀。
        fun insideFrame(px: List<Float>): Boolean {
            val r = contentRect ?: return true
            val cx = (px[0] + px[2]) / 2f
            val cy = (px[1] + px[3]) / 2f
            return cx >= r[0] && cx <= r[2] && cy >= r[1] && cy <= r[3]
        }

        for (b in barcodes) {
            if (!insideFrame(b.px)) {
                dropped += "条码「${b.text}」在取景框外，丢弃"
                continue
            }
            elements += LabelElement(
                kind = if (b.isTwoD) ElementKind.BARCODE_2D else ElementKind.BARCODE_1D,
                text = b.text, format = b.format, px = b.px, source = b.source,
                // 码制决定发 ^BE/^B8/^B9/^B0/^B2/^BC/^B3 —— V1.9 只按 "128" 判，
                // EAN-13 被打成 Code128（HRI 分组和起止符全不对）
                symbology = if (b.isTwoD) "" else ZplGeometry.symbology(b.format),
            )
        }

        // OCR 挂了不能把整条链带崩：条码和框线不依赖 OCR，用户至少能拿到那张条码标签。
        // 实测踩过：ONNX 输入缓冲尺寸不匹配时 OcrEngine 抛 OrtException，界面只显示"识别失败"，
        // 其实条码早就解出来了（EAN-13 6934024512123）。
        val lines = runCatching { ocr?.invoke(bitmap) ?: emptyList() }.getOrElse { t ->
            dropped += "OCR 失败（${t.javaClass.simpleName}: ${t.message}），本次只输出条码与框线"
            emptyList()
        }
        val kept = ArrayList<TextLine>()
        for (ln in lines) {
            // 与 PC 端参考实现同一条门槛（label_to_zpl.py: min_conf=0.5）：DB 检测会把条码区域
            // 本身当成文本框，吐出来的常是空串或极低置信的乱码，先按置信度丢掉。
            if (ln.text.isBlank() || ln.conf < MIN_OCR_CONF) {
                dropped += "「${ln.text}」置信度 ${"%.2f".format(ln.conf)} < $MIN_OCR_CONF，丢弃"
                continue
            }
            if (!insideFrame(ln.px)) {
                dropped += "「${ln.text}」在取景框外，丢弃"
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
                    isHriOf(ln.text, b.text) && belowAndOverlapping(ln.px, b.px)
            }
            if (owner != null) {
                val i = elements.indexOf(owner)
                elements[i] = owner.copy(hri = true, hriPx = ln.px)
                dropped += "「${ln.text}」是条码 ${owner.text} 的人眼可读串，归并进条码的明文行"
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

        // 墨迹尺寸：字号必须按**墨迹**算。OCR 的框是行框，上下留行距、左右留字距，
        // 比字形本身胖 10~20%，直接拿框高推字号就会"整版字大一号"（用户对比原标签）。
        val withInk = elements.map { e ->
            if (e.kind != ElementKind.TEXT) e
            else {
                val ink = ImageMeasure.inkSize(gray, imgW, imgH, e.px)
                if (ink == null) e else e.copy(inkPxW = ink.first, inkPxH = ink.second)
            }
        }.toMutableList()

        // 框线（外框 + 分隔线）：V1.9 的要素表里根本没有"线"这一档，纸面上框体全丢。
        // 条码区内的竖线要排除 —— 条码的条本身就是一列列实心墨迹。
        val barPx = withInk.filter { it.kind == ElementKind.BARCODE_1D || it.kind == ElementKind.BARCODE_2D }
            .map { e ->
                // 1D 条码的排除区向下延伸到 HRI 带。系数必须小（0.55）：给 1.2 倍条高会把
                // 条码下方那格的下边框也一起吞掉（实测下边框整条消失，「少一格」就是它）。
                //
                // 那排数字的横笔画会被当成一堆短横线（实测一次 20+ 段碎线）。
                if (e.kind == ElementKind.BARCODE_1D) {
                    listOf(e.px[0], e.px[1], e.px[2], e.px[3] + 0.55f * (e.px[3] - e.px[1]))
                } else e.px
            }
        // 文字行的外框也排除：人眼可读串「6 934024 512123」里每个数字的横笔画，
        // 都会被当成"短横线"检出来（相机照片实测一次蹦出 20 多段 3~12 点的碎线，
        // 全都落在同一条 y 上）。真印出来的框线/分隔线不会穿过文字行自己的墨迹框。
        // 文字笔画排除只对**短线**生效：整根边框和某个文本框相交是常态（左边框就压在
        // 文字框左边），按相交判会把边框整条丢掉（实测 4 条边框全没了）。
        val textPx = withInk.filter { it.kind == ElementKind.TEXT }.map { it.px }
        for (b in ImageMeasure.detectRules(gray, imgW, imgH, barPx)) {
            val short = (b[2] - b[0]) < 0.25f * imgW && (b[3] - b[1]) < 0.25f * imgH
            val hitText = short && textPx.any { t ->
                b[0] < t[2] && b[2] > t[0] && b[1] < t[3] && b[3] > t[1]
            }
            if (hitText) continue
            // 纸边禁区：印刷框离纸边至少 9%% 留白（真实设计 x 9.1~92.3%%、y 19.9~86.4%%），
            // 所以紧贴 basis 边界的那几条线是纸边/投影，不是框线。
            // 用户实测：纸张下边缘被当成下框线（拍到底边反光时尤其明显）。只在 basis 就是
            // 整张纸（label）时生效 —— basis=content 时边界是文字/条码框，分隔线贴着它属正常。
            if (basis == "label") {
                val horiz = (b[2] - b[0]) >= (b[3] - b[1])
                val pos = if (horiz) (b[1] + b[3]) / 2f / imgH else (b[0] + b[2]) / 2f / imgW
                // 0.06 = 安全值（印刷框真实留白 ≥9%%）
                if (pos < 0.06f || pos > 0.94f) continue
            }
            if (!insideFrame(b)) continue
            // basis="quad"：边框位置已经由四点定位精确给出，再让检测器去"猜"只会把背景
            // 边缘、塑料托盘边也算成线（真机实测多出 4 根贯穿竖线）。框外的候选一概不要。
            if (frameInset != null && !insideInset(b, frameInset, imgW, imgH)) continue
            withInk += LabelElement(kind = ElementKind.BOX, text = "", px = b, source = "rule")
        }

        // 四条边框：直接由四点定位结果生成，不再依赖检测。厚度按画布的 0.5% 给（≈3 点），
        // 位置取内缩矩形的中线。
        if (frameInset != null) {
            val x0 = frameInset[0] * imgW; val y0 = frameInset[1] * imgH
            val x1 = frameInset[2] * imgW; val y1 = frameInset[3] * imgH
            val t = maxOf(2.0f, imgW * 0.005f)
            withInk += LabelElement(kind = ElementKind.BOX, text = "", px = listOf(x0, y0, x1, y0 + t), source = "quad")
            withInk += LabelElement(kind = ElementKind.BOX, text = "", px = listOf(x0, y1 - t, x1, y1), source = "quad")
            withInk += LabelElement(kind = ElementKind.BOX, text = "", px = listOf(x0, y0, x0 + t, y1), source = "quad")
            withInk += LabelElement(kind = ElementKind.BOX, text = "", px = listOf(x1 - t, y0, x1, y1), source = "quad")
        }

        // 没检出标签四边形（basis=full，相机照片贴着浅色背景时很常见）时，整帧 1:1 映射会
        // 把标签缩成半张、字号掉到 17 点、条码模块宽掉到 1.7 点。要素只占取景框一小块就
        // 说明标签没铺满画面，改成按内容框铺满（basis="content"）。
        var basisUse = basis
        if (basis == "full" && withInk.isNotEmpty()) {
            // 只用文字+条码算内容框：框线要素里有碎线，会把 bbox 撑满整幅，面积判据就失效了
            val solid = withInk.filter { it.kind != ElementKind.BOX }
            val cb = contentBBox(if (solid.isEmpty()) withInk else solid)
            val frac = (cb[2] - cb[0]) * (cb[3] - cb[1]) / (imgW.toFloat() * imgH)
            if (frac < 0.40f) basisUse = "content"
        }

        // 像素 -> 点阵。基准：label/full 时照片本身就是标签，边距必须为 0，
        // 否则会整体偏移（实测 16~22 点 = 2mm+）
        val laid = layout(withInk, basisUse, imgW, imgH, template)

        return ReprintPlan(
            imageName = "", imageSize = imgW to imgH, basis = basisUse, template = template,
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

        // 纸张长宽比是**已知常量**（模板2 = 76x70mm -> 1.086）。检出的四边形比例对不上时
        // （实测 1.234：贴合的是印刷内容而非纸边，纵向少抓了空白），整版会被推下去十几毫米、
        // 下边框掉出画面。这里按比例把纵向拉开并保持中心 —— 文字/条码位置由 OCR/解码器给，
        // 不受影响；只有框线几何被拉回正确比例。
        var sy = scale
        var dy2 = dy
        if (basis == "label") {
            val imgAr = cw / ch
            val tplAr = ZplGeometry.aspectRatio(template).toFloat()
            val rr = imgAr / tplAr
            if (Math.abs(Math.log(rr.toDouble())) > 0.03) {
                sy = scale * rr
                dy2 = dy + (ch * scale - ch * sy) / 2f
            }
        }

        val mapped = elements.map { e ->
            val d = e.withDots(
                dx + (e.px[0] - basisRect[0]) * scale,
                dy2 + (e.px[1] - basisRect[1]) * sy,
                dx + (e.px[2] - basisRect[0]) * scale,
                dy2 + (e.px[3] - basisRect[1]) * sy,
            )
            // 墨迹像素 -> 点：ZplBuilder 只用 inkW/inkH，忘了换算等于把像素当点用（字号大 2 倍）
            if (e.inkPxW > 0f) d.copy(inkW = e.inkPxW * scale, inkH = e.inkPxH * scale) else d
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
        val bars = elements.filter { it.kind == ElementKind.BARCODE_1D || it.kind == ElementKind.BARCODE_2D }
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
                    // 条码**实印下缘**（不是元素框底）：^FT 对条码锚的是最下一行，
                    // 而二维码的锚点还含 4 个模块 quiet zone —— 框底通常比实印下缘高 20~60 点。
                    // 按框底加净空会算少了，纸上就是文字压在二维码的 quiet zone 上。
                    val barBottom = printedBottom(b, labelW)
                    // 整行都在条码上方 -> 不管（把它推到条码下面会打乱版式）；
                    // 压在条码里或在其下方 -> 都得让出净空
                    if (e.dots[3] <= b.dots[1] + 0.5f) continue
                    // 二维码多留 1mm（QR 的 quiet zone 只有 4 个模块，被文字蹭掉就锁不住定位图）
                    val gap = if (b.kind == ElementKind.BARCODE_2D) {
                        ZplGeometry.QR_TEXT_GAP
                    } else {
                        ZplGeometry.MIN_TEXT_BARCODE_GAP
                    }
                    val need = barBottom + gap
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

    /**
     * 条码的**实印下缘**（点）。与 ZplBuilder 的锚点算法同源：
     * 一维码锚点 = 顶 + 条高；二维码锚点 = 顶 + 黑模块高 + 4 个模块 quiet zone。
     * 元素框底（dots[3]）是照片里量的墨迹范围，二维码因为它自身的 quiet zone 会明显偏小。
     */
    private fun printedBottom(b: LabelElement, labelW: Int): Float {
        val x = ZplGeometry.roundHalf(b.dots[0].toDouble())
        val top = ZplGeometry.roundHalf(b.dots[1].toDouble())
        val dy = if (b.kind == ElementKind.BARCODE_2D) {
            ZplGeometry.qrLayout(x, b.w, b.text, labelW).anchorDy
        } else {
            ZplGeometry.anchorDyBarcode1d(b.h.roundToInt())
        }
        return (top + dy).toFloat()
    }

    // ---- helpers (mirror of the Python versions) ----
    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /**
     * 这一行 OCR 是不是条码下方的人眼可读串（HRI）。
     *
     * 只认"去掉非字母数字后完全相等"会漏两种真实情况（PC 端同步改过）：
     *  - 原标签 EAN-13 的 HRI 是 `6 934024 512123`，OCR 把分隔符读成引号 -> `934024"512123`
     *    （少一位），精确比对失败 -> 被当成普通文字又发了一遍（V1.9 纸上那行多出来的数字）；
     *  - 条码勾了校验位时 HRI 会多/少一位。
     * 所以：两边都是数字时，较短的位数 ≥ 6 且被较长的包含，就算同一条 HRI。
     */
    private fun isHriOf(lineText: String, barcodeText: String): Boolean {
        if (norm(lineText) == norm(barcodeText)) return true
        val d1 = lineText.filter { it.isDigit() }
        val d2 = barcodeText.filter { it.isDigit() }
        if (d1.length < 6 || d2.length < 6) return false
        val short = if (d1.length <= d2.length) d1 else d2
        val long = if (d1.length <= d2.length) d2 else d1
        return long.contains(short)
    }

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

    /** 是否落在印刷边框内（留 2% 容差，边框本身算在内） */
    private fun insideInset(px: List<Float>, inset: FloatArray, w: Int, h: Int): Boolean {
        val x0 = inset[0] * w - 0.02f * w; val y0 = inset[1] * h - 0.02f * h
        val x1 = inset[2] * w + 0.02f * w; val y1 = inset[3] * h + 0.02f * h
        val cx = (px[0] + px[2]) / 2f; val cy = (px[1] + px[3]) / 2f
        return cx in x0..x1 && cy in y0..y1
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
        // 每个字段都以 ^FS 结束：文字/条码是 ^FD…^FS，框线是 ^GB…^FS（没有 ^FD）。
        // 原来只比 ^FD 与 ^FS 的个数，加了框线之后必然报"不配对"，成了假警报。
        val fsCount = zpl.split("^FS").size - 1
        val fdCount = zpl.split("^FD").size - 1
        val gbCount = zpl.split("^GB").size - 1
        if (fdCount + gbCount != fsCount) errs += "^FD/^FS 数量不等（${fdCount} 个 ^FD + $gbCount 个 ^GB vs $fsCount 个 ^FS）"
        listOf("^MMT", "^MMB", "^MNY", "^PR", "^MD").forEach {
            if (zpl.contains(it)) errs += "$it 不该由 App 下发（介质/速度/黑度是打印机设置）"
        }
        Regex("""\^FT(\d+),(\d+)""").findAll(zpl).forEach { m ->
            val x = m.groupValues[1].toInt()
            val y = m.groupValues[2].toInt()
            if (x < 0 || x >= w) errs += "^FT x=$x 越界 0..${w - 1}"
            if (y < 0 || y >= h) errs += "^FT y=$y 越界 0..${h - 1}"
        }
        // 框线走 ^FO（左上角锚定，不带 ^FT 的"下边缘锚定"语义）
        Regex("""\^FO(\d+),(\d+)\^GB(\d+),(\d+),(\d+)""").findAll(zpl).forEach { m ->
            val x = m.groupValues[1].toInt()
            val y = m.groupValues[2].toInt()
            val bw = m.groupValues[3].toInt()
            val bh = m.groupValues[4].toInt()
            if (x < 0 || y < 0 || x + bw > w || y + bh > h) {
                errs += "^GB ${bw}x$bh @($x,$y) 越界（会画到纸外）"
            }
        }
        for (e in elements) {
            if (e.kind == ElementKind.BARCODE_1D) {
                val sym = e.symbology.ifEmpty { ZplGeometry.symbology(e.format) }
                val modules = ZplGeometry.barcodeModules(sym, e.text)
                val mw = (e.w / modules).coerceIn(1f, 10f)
                if (ZplGeometry.roundHalf(e.dots[0].toDouble()) + modules * mw > w) {
                    errs += "条码「${e.text}」估算宽度超出标签（会打到纸外）"
                }
                // Code 39 只支持大写字母、数字和 - . $ / + % 与空格；小写/中文会被打印机**静默忽略**，
                // 打出来是缺字符的条码（扫出来内容不对，比报错更危险）
                if (sym == "code39" &&
                    !Regex("^[0-9A-Z\\-.\\$/+% ]*$").matches(e.text)
                ) {
                    errs += "Code 39 内容含非法字符（只允许 0-9 A-Z 与 -.\$/ +%），会被打印机静默丢弃"
                }
                // 零售码的位数是硬约束：少一位打印机直接不出符号（^BE 要求 12 或 13 位）
                val need = when (sym) {
                    "ean13" -> intArrayOf(12, 13)
                    "ean8" -> intArrayOf(7, 8)
                    "upca" -> intArrayOf(11, 12)
                    else -> null
                }
                if (need != null && e.text.length !in need) {
                    errs += "$sym 要求 ${need.joinToString("/")} 位数字，实到 ${e.text.length} 位（打印机可能不出符号）"
                }
            }
        }
        return errs
    }
}
