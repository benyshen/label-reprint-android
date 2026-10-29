package com.tiaomabang.labelreprint.zpl

import com.tiaomabang.labelreprint.model.ElementKind
import com.tiaomabang.labelreprint.model.LabelElement
import kotlin.math.roundToInt

/**
 * 元素表 -> ZPL。对应参考实现 `tools/label_to_zpl.py: emit_zpl()`，
 * 逐字符串对齐（含 ^BY/^BQ/^FT/^FB 的写法与空格），两端生成的字节必须一致。
 */
object ZplBuilder {

    private val FT_RE = Regex("""\^FT(-?\d+),(-?\d+)""")

    fun build(elements: List<LabelElement>, template: Int = ZplGeometry.DEFAULT_TEMPLATE): Pair<String, List<String>> {
        val (w, h) = ZplGeometry.dims(template)
        val lines = mutableListOf("^XA", "^CI28", "^PW$w", "^LL$h")
        val warnings = mutableListOf<String>()

        val sorted = mergeParallelLines(elements).sortedWith(compareBy({ it.dots[1] }, { it.dots[0] }))
        for (e in sorted) {
            val x = ZplGeometry.roundHalf(e.dots[0].toDouble())
            val top = ZplGeometry.roundHalf(e.dots[1].toDouble())
            var field: String
            var bottom: Int

            when (e.kind) {
                ElementKind.BARCODE_1D -> {
                    val sym = e.symbology.ifEmpty { ZplGeometry.symbology(e.format) }
                    val modules = ZplGeometry.barcodeModules(sym, e.text)
                    val usable = w - ZplGeometry.MARGIN - x                 // 美观留白内的可用宽度
                    val phys = w - ZplGeometry.GUARD - x                     // 物理可打印宽度
                    // 原图量出来的模块宽，只作参考 —— 它低于可印下限时**不能照抄**
                    val derived = e.w / modules
                    // 见 ZplGeometry.MIN_MODULE_DOTS：1 点模块宽的 Code128 会印糊，
                    // 扫描器测不准条宽就退化判成 Code 39（用户实际遇到的现象）。
                    var mw = maxOf(ZplGeometry.MIN_MODULE_DOTS,
                        Math.round(minOf(derived, 10f) * 2) / 2.0)
                    val barH = e.h.roundToInt()
                    if (derived < ZplGeometry.MIN_MODULE_DOTS) {
                        warnings += "条码「${e.text}」原图模块宽仅 ${"%.1f".format(derived)} 点，" +
                            "向上取到可印下限 ${fmtNum(mw)} 点（低于下限会被扫成别的码制）"
                    }
                    if (modules * mw > usable) {
                        if (modules * mw <= phys) {
                            warnings += "条码「${e.text}」按可印下限 ${fmtNum(mw)} 点需 ${modules * mw} 点，" +
                                "超过右侧留白（$usable 点），已占用到距标签边 ${ZplGeometry.GUARD} 点"
                        } else {
                            mw = maxOf(1.0, phys.toDouble() / modules)
                            warnings += "条码「${e.text}」$modules 模块按可印下限需 " +
                                "${ZplGeometry.MIN_MODULE_DOTS * modules} 点 > 可打印宽度 $phys 点，" +
                                "已压到 ${"%.1f".format(mw)} 点（可能扫不出：考虑换更大的标签或缩短内容）"
                        }
                    }
                    val anchor = top + ZplGeometry.anchorDyBarcode1d(barH)
                    // 码制按识别结果发：^BE/^B8/^B9/^B0/^B2/^BC/^B3/^BA/^BK
                    field = ZplGeometry.barcode1dField(sym, x, anchor, mw, barH, e.hri)
                    field += "^FD${e.text}^FS"
                    bottom = anchor
                }
                ElementKind.BARCODE_2D -> {
                    // mag/实印高/锚点偏移都从 ZplGeometry 取（净空计算也用同一份，避免漂移）
                    val qr = ZplGeometry.qrLayout(x, e.w, e.text, w)
                    val anchor = top + qr.anchorDy
                    field = "^FT$x,$anchor^BQN,2,${qr.mag}^FDQA,${e.text}^FS"
                    bottom = anchor
                }
                ElementKind.BOX -> {
                    // 框线：^FO 左上角锚定 + ^GB。厚度取宽高的较小值 = 实心细线，
                    // 大厚度会变成色块（实测 3 点笔画被写成 ^GB3,380,380 直接糊成一条黑边）。
                    val bw = maxOf(1, (e.dots[2] - e.dots[0]).roundToInt())
                    val bh = maxOf(1, (e.dots[3] - e.dots[1]).roundToInt())
                    // 厚度钳到 3~5 点（0.38~0.63mm）：细线（1~2 点）打出来发虚，
                    // 下一次拍照更难检出 —— 现场标签的框线本来也是这个量级。
                    // 上限 5：再粗 ^GB 会把 3 点的笔画糊成色块。
                    val th = 5   // 统一 5 点（0.63mm）：实测 3 点打出来仍发虚、下次拍照难检出
                    field = "^FO$x,$top^GB$bw,$bh,${th}^FS"
                    bottom = top + bh
                }
                ElementKind.TEXT -> {
                    val maxW = maxOf(40, w - ZplGeometry.MARGIN - x)
                    // 字号按**墨迹**算（zpl-core 公式）：OCR 行框比字形胖 10~20%，
                    // 拿框高推字号打出来整版大一号；墨迹宽再除以 0.92 反推推进宽。
                    val inkW = if (e.inkW > 0f) e.inkW else e.w
                    val inkH = if (e.inkH > 0f) e.inkH else e.h
                    val budgetW = maxOf(20.0, minOf(maxW.toDouble(), inkW / ZplGeometry.WIDTH_SLACK))
                    var fs = ZplGeometry.fontSizeFor(budgetW, inkH.toDouble(), e.text)
                    // 硬兜底：字号超过字段宽时打印机**不是截断，而是把字符挤着叠印**（纸上就是
                    // 用户报的"文字重合"）。实测 31 字符中文行在 416 点字段里用 26 号字需要 858 点。
                    // 宁可小一号，也绝不超宽。
                    while (fs > 8 && ZplGeometry.textWidth(e.text, fs) > maxW) fs -= 1
                    val anchor = top + ZplGeometry.anchorDyText(fs)
                    val font = if (ZplGeometry.isCjk(e.text)) "^A@N,$fs,$fs,E:SIMSUN.TTF" else "^A0N,$fs,$fs"
                    field = "^FT$x,$anchor$font^FB$maxW,1,0,L,0^FD${e.text}^FS"
                    bottom = anchor
                    if (ZplGeometry.textWidth(e.text, fs) > maxW) {
                        warnings += "文字「${e.text}」按最小字号 8 仍比 $maxW 点宽，^FB 会截断"
                    }
                }
            }

            // 文字/条码是"下边缘锚定"，卡住锚点就把墨迹关在标签内。
            // 框线例外：它按 ^FO 左上角定位，被"上移"会让整条边框错位（底部边框整体抬高）。
            if (e.kind != ElementKind.BOX && bottom > h - ZplGeometry.GUARD) {
                val shift = bottom - (h - ZplGeometry.GUARD)
                field = shiftY(field, shift)
                warnings += "「${e.text}」超出可打印高度，上移 $shift 点"
            }
            lines += field
        }
        lines += "^XZ"
        return lines.joinToString("\r\n") + "\r\n" to warnings
    }

    internal fun fmtNum(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else v.toString()

    /**
     * 同一根框线被多档阈值/斜线测出好几份时，在**点阵**层面合并成一条。
     *
     * 图像层面的去重（重叠 >70%）解决不了"两条线差 3 点、互不重叠"的情况：实测底部
     * 边框被打成 y=475 和 y=495 两条、左边框被打成 x=14 和 x=21 两条 —— 纸上就是重影的边框。
     * 这里按"中线差 ≤ 6 点（或一个笔画厚）+ 长度重叠 ≥ 50%"判同一根线，留长的。
     */
    private fun mergeParallelLines(elements: List<LabelElement>): List<LabelElement> {
        val lines = elements.filter { it.kind == ElementKind.BOX }
        if (lines.size < 2) return elements
        val others = elements.filter { it.kind != ElementKind.BOX }
        val area = { e: LabelElement -> (e.dots[2] - e.dots[0]) * (e.dots[3] - e.dots[1]) }
        val kept = ArrayList<LabelElement>()
        for (b in lines.sortedByDescending(area)) {
            val bw = b.dots[2] - b.dots[0]
            val bh = b.dots[3] - b.dots[1]
            val horiz = bw >= bh
            val dup = kept.any { k ->
                val kw = k.dots[2] - k.dots[0]
                val kh = k.dots[3] - k.dots[1]
                if ((kw >= kh) != horiz) return@any false
                if (horiz) {
                    val cy = (b.dots[1] + b.dots[3]) / 2f
                    val ky = (k.dots[1] + k.dots[3]) / 2f
                    Math.abs(cy - ky) <= maxOf(6f, kh) &&
                        minOf(b.dots[2], k.dots[2]) - maxOf(b.dots[0], k.dots[0]) >= 0.5f * minOf(bw, kw)
                } else {
                    val cx = (b.dots[0] + b.dots[2]) / 2f
                    val kx = (k.dots[0] + k.dots[2]) / 2f
                    Math.abs(cx - kx) <= maxOf(6f, kw) &&
                        minOf(b.dots[3], k.dots[3]) - maxOf(b.dots[1], k.dots[1]) >= 0.5f * minOf(bh, kh)
                }
            }
            if (!dup) kept.add(b)
        }
        return others + kept
    }

    internal fun shiftY(field: String, dy: Int): String {
        val m = FT_RE.find(field) ?: return field
        val y = maxOf(0, m.groupValues[2].toInt() - dy)
        return field.substring(0, m.range.first) + "^FT${m.groupValues[1]},$y" + field.substring(m.range.last + 1)
    }
}
