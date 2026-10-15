package com.tiaomabang.labelreprint.zpl

/**
 * 标签/ZPL 几何 —— 唯一真源。
 *
 * 与 PC 端参考实现 `tools/zpl_geometry.py` 必须 1:1 一致（同名常量、同公式、同取整）。
 * 任何一边单独改都会让两端生成的 ZPL 对不上，而"差一个字符整批标签作废"。
 */
object ZplGeometry {

    const val MM_PER_INCH = 25.4

    data class Template(val id: Int, val label: String, val wMm: Double, val hMm: Double, val dpi: Int)

    val TEMPLATES: Map<Int, Template> = linkedMapOf(
        1 to Template(1, "模板1", 60.0, 40.0, 203),
        2 to Template(2, "模板2", 70.0, 70.0, 203),
        3 to Template(3, "模板3", 70.0, 100.0, 203),
    )
    const val DEFAULT_TEMPLATE = 1

    /**
     * 按源图长宽比自动选模板。
     *
     * 为什么需要：照片里量不出标签的物理尺寸，但**长宽比是尺度无关的**，可以用它挑模板。
     * 实测（用户拍的 70×70mm 校准标签，源图 529×464 → 1.14）：用默认模板1(60×40 → 1.5) 会把整张
     * 标签纵向压到 57%，偏差从顶部 −16 点线性放大到底部 −200 点 —— **越靠下的要素错得越狠**
     * （小二维码被顶到刻度 225→134 的位置，肉眼一眼看出"偏上"）。换成长宽比最接近的
     * 模板2(70×70 → 1.0) 后，逐要素偏差收敛到 +25…−4 点，大 QR 的放大倍数也从 mag4 回到 mag6。
     *
     * 用 log 距离而不是差：长宽比是比例量，1.14 vs 1.0 与 1.5 vs 1.75 的"差"不可比。
     */
    fun templateForAspect(wPx: Int, hPx: Int): Int {
        if (wPx <= 0 || hPx <= 0) return DEFAULT_TEMPLATE
        return templateForAspectRatio(wPx.toDouble() / hPx)
    }

    /** 模板的长宽比（w/h），用于判断内容是否需要对上方向 */
    fun aspectRatio(template: Int): Double {
        val t = TEMPLATES.getValue(template)
        return t.wMm / t.hMm
    }

    /** 只按长宽比选模板（标签四边形校正后用这个 —— 照片比例里混进了背景，不能用） */
    fun templateForAspectRatio(ar: Double): Int {
        if (ar <= 0.0 || !ar.isFinite()) return DEFAULT_TEMPLATE
        return TEMPLATES.values.minByOrNull { t -> kotlin.math.abs(kotlin.math.ln(ar / (t.wMm / t.hMm))) }?.id
            ?: DEFAULT_TEMPLATE
    }

    /**
     * 一维码模块宽（点）下限 = 2。
     *
     * 实测（203dpi，药品追溯码标签）：`^BY1`（1 点 = 0.125mm）时 Code128 的条被印成一团糊，
     * **扫描器把 Code128 判成了 Code 39** —— 条宽测不准时解码器会退化到别的码制。
     * 我们的 ZPL 一直是 `^BC`（日志里 `^B3` 出现 0 次），所以"变 39 码"是印不出来导致的，
     * 不是编码错了。宁可把条码打得比原图宽，也不能低于可印下限。
     */
    const val MIN_MODULE_DOTS = 2.0

    /**
     * 文字与条码之间的最小净空（点）。实测来源：条码下方的人眼可读串被 OCR 当独立文本要素时，
     * 原图上就只差 3~4 点（0.4mm），映射到点阵后 ^FT 一下就把文字贴到条上（用户报"重叠"）。
     */
    const val MIN_TEXT_BARCODE_GAP = 6

    /** 文字行贴着条码时向下推，直到留出净空（不改横向位置，避免破坏与其它要素的相对关系） */
    const val DECOLLIDE_STEP_LIMIT = 40


    const val MARGIN = 12        // 四边留白（点）
    const val TOP_OFFSET = 6     // 整体下移，避免贴纸边
    const val GUARD = 6          // 底部保护带

    /** 模板纸面的长宽比（宽/高）。取景框、以及"没检出标签时按取景框裁剪"都用它。 */
    fun aspectOf(template: Int = DEFAULT_TEMPLATE): Float {
        val t = TEMPLATES[template] ?: TEMPLATES.getValue(DEFAULT_TEMPLATE)
        return (t.wMm / t.hMm).toFloat()
    }

    /**
     * ^A0N/^A@N 实印字高与字号之比。
     *
     * 0.775 是 **^A0N（内置点阵字体）** 实测值（20/40/80/120 点全部吻合）。
     * 汉字走 `^A@N,...,E:SIMSUN.TTF`，字高≈字号本身，另有 [CAP_RATIO_CJK]。
     */
    const val FONT_CAP_RATIO = 0.775

    /** ^A@ + SimSun 的汉字字高 / 字号 ≈ 1.0（实测：20.4 点的字高对应字号 ~20，不是 26） */
    const val CAP_RATIO_CJK = 1.0

    /** 汉字/全角标点的推进宽度 = 1 em（SimSun 里占满一格）；ASCII 约 0.5 em */
    const val CHAR_W_CJK = 1.0

    const val CHAR_W_DEFAULT = 0.51
    val CHAR_W_NARROW = mapOf('i' to 0.24, 'l' to 0.24, 'j' to 0.24, 't' to 0.31, 'f' to 0.28,
        'r' to 0.35, '1' to 0.26, 'I' to 0.24, '.' to 0.25, ',' to 0.25, ' ' to 0.26)
    val CHAR_W_WIDE = mapOf('W' to 0.79, 'M' to 0.72, 'm' to 0.72, 'w' to 0.68)

    fun charWidth(ch: Char): Double = when {
        ch.code > 0x2E80 -> CHAR_W_CJK                       // 汉字、全角标点：占一格
        CHAR_W_WIDE[ch] != null -> CHAR_W_WIDE.getValue(ch)
        CHAR_W_NARROW[ch] != null -> CHAR_W_NARROW.getValue(ch)
        else -> CHAR_W_DEFAULT
    }

    /** 半进上取整。Kotlin 的 Math.round 只对 float/double→long，语义与 Python 的 round()(银行家舍入)
     *  在 .5 边界差 1 点，所以两端都用这一支。 */
    fun roundHalf(v: Double): Int = if (v >= 0) Math.floor(v + 0.5).toInt() else -Math.floor(-v + 0.5).toInt()

    fun mm2dots(mm: Double, dpi: Int = 203): Int = roundHalf(mm * dpi / MM_PER_INCH)

    /** 标签点阵尺寸，如 模板1 -> 480 x 320 */
    fun dims(template: Int = DEFAULT_TEMPLATE): Pair<Int, Int> {
        val t = TEMPLATES.getValue(template)
        return mm2dots(t.wMm, t.dpi) to mm2dots(t.hMm, t.dpi)
    }

    fun textWidth(text: String, fontSize: Int): Double {
        var total = 0.0
        for (ch in text) total += charWidth(ch)
        return total * fontSize
    }

    /**
     * 同时受宽高约束的字号：只按高度推会被 ^FB 静默截断（实测 "NANJING" -> "NANJIN"）；
     * 只按宽度推会把字压得太小。
     *
     * 汉字必须走另一套比值（见 [CAP_RATIO_CJK]）：用 0.775 推得 26 号，而宽度只够 20 号 ——
     * 实测（用户那张药品标签 HRI 行，31 字符）打出来比字段宽一倍，打印机把字符**挤着叠印**，
     * 纸上表现就是"条码下方的文字重合"。
     */
    fun fontSizeFor(boxW: Double, boxH: Double, text: String): Int {
        val cap = if (isCjk(text)) CAP_RATIO_CJK else FONT_CAP_RATIO
        val fsH = boxH / cap
        val fsW = boxW / maxOf(1e-6, textWidth(text, 1))
        return maxOf(8, Math.round(minOf(fsH, fsW)).toInt())
    }

    fun anchorDyText(fontSize: Int, lines: Int = 1): Int = lines * fontSize

    fun anchorDyBarcode1d(barHeight: Int): Int = barHeight

    fun anchorDyQr(blackH: Int, magnification: Int): Int = blackH + 4 * magnification

    /**
     * Code128 模块数 = 11 × 符号数 + 35（起始符 + 校验符 + 终止符）。
     *
     * 纯数字必须按 **Code128C** 算：两位数字压进一个符号，符号数 = ceil(长度/2) = (n+1)/2。
     * 教训：用户那张药品追溯码标签是 20 位纯数字，按"一位一符号"算成 255 模块 -> 模块宽估成
     * 0.44 点 -> 被"放不下"规则再压到 1.8 点，打出来照样糊。按 C 码算只有 145 模块，
     * 2 点模块宽 = 290 点，标签装得下。
     */
    fun code128Modules(nChars: Int, digitsOnly: Boolean = false): Int {
        val symbols = if (digitsOnly && nChars >= 4) (nChars + 1) / 2 else nChars
        return 11 * symbols + 35
    }

    fun code39Modules(nChars: Int): Int = 16 * (nChars + 2) + 1

    /** QR 版本容量（纠错级 Q，^BQ 默认）：模块数 / 字母数字容量 / 字节容量 */
    private val QR_CAPACITY_Q = listOf(
        intArrayOf(21, 29, 20), intArrayOf(25, 47, 32), intArrayOf(29, 67, 46),
        intArrayOf(33, 87, 60), intArrayOf(37, 107, 74), intArrayOf(41, 128, 86),
        intArrayOf(45, 154, 108), intArrayOf(49, 180, 130), intArrayOf(53, 205, 151),
        intArrayOf(57, 230, 177),
    )

    fun qrModulesForText(text: String): Int {
        for (row in QR_CAPACITY_Q) {
            val alnum = text.all { it.isLetterOrDigit() && it.code < 128 }
            val cap = if (alnum) row[1] else row[2]
            if (text.length <= cap) return row[0]
        }
        return QR_CAPACITY_Q.last()[0]
    }

    fun isCjk(text: String): Boolean = text.any { it.code > 0x2E80 }
}
