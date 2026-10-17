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

        val sorted = elements.sortedWith(compareBy({ it.dots[1] }, { it.dots[0] }))
        for (e in sorted) {
            val x = ZplGeometry.roundHalf(e.dots[0].toDouble())
            val top = ZplGeometry.roundHalf(e.dots[1].toDouble())
            var field: String
            var bottom: Int

            when (e.kind) {
                ElementKind.BARCODE_1D -> {
                    val modules = if (e.format.contains("128"))
                        ZplGeometry.code128Modules(e.text.length, e.text.all { it.isDigit() })
                    else ZplGeometry.code39Modules(e.text.length)
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
                            // 只差几个点：**宁可靠到打印边界也不缩到下限以下** ——
                            // 扫不出来的条码等于没打（实测 20 位纯数字在 60mm 标签上正是差这几个点，
                            // 而这点差别决定扫描器判不判得出码制）
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
                    field = "^BY${fmtNum(mw)},2.5,$barH^FT$x,$anchor^BCN,$barH,${if (e.hri) "Y" else "N"},N,N"
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
                ElementKind.TEXT -> {
                    val maxW = maxOf(40, w - ZplGeometry.MARGIN - x)
                    val budgetW = maxOf(20.0, minOf(maxW.toDouble(), e.w * 1.10))
                    var fs = ZplGeometry.fontSizeFor(budgetW, e.h.toDouble(), e.text)
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

            // 三种要素都是"下边缘锚定"，所以卡住锚点就把墨迹关在标签内
            if (bottom > h - ZplGeometry.GUARD) {
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

    internal fun shiftY(field: String, dy: Int): String {
        val m = FT_RE.find(field) ?: return field
        val y = maxOf(0, m.groupValues[2].toInt() - dy)
        return field.substring(0, m.range.first) + "^FT${m.groupValues[1]},$y" + field.substring(m.range.last + 1)
    }
}
