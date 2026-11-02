package com.tiaomabang.labelreprint.zpl

import com.tiaomabang.labelreprint.model.LabelElement
import kotlin.math.roundToInt

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
        2 to Template(2, "模板2", 76.0, 70.0, 203),   // 整张纸 76x70mm（实测），可打印区更窄、四周留白
        // 模板3 是「自定义」：尺寸由用户设（默认 76x70mm，和现场标签一致）
        3 to Template(3, "自定义", 76.0, 70.0, 203),
    )
    const val DEFAULT_TEMPLATE = 1

    /** 自定义模板号 */
    const val CUSTOM_TEMPLATE = 3

    /**
     * 设置「自定义」模板的标签纸尺寸（mm）。
     *
     * 直接改 TEMPLATES[3] 而不是加一条分支：aspectOf / 模板 chip 文案 / 布局 / ZPL 画布
     * 全都读 TEMPLATES，改这一处就全线跟随，不会漏。
     */
    fun setCustomSize(wMm: Double, hMm: Double) {
        if (!(wMm > 10.0 && wMm < 300.0 && hMm > 10.0 && hMm < 300.0)) return
        (TEMPLATES as MutableMap)[CUSTOM_TEMPLATE] = Template(CUSTOM_TEMPLATE, "自定义", wMm, hMm, 203)
    }

    fun customSize(): Pair<Double, Double> {
        val t = TEMPLATES.getValue(CUSTOM_TEMPLATE)
        return t.wMm to t.hMm
    }

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

    /**
     * QR（二维）码与**下方**要素的净空 = 通用净空 + 1mm（203dpi 下 1mm ≈ 8 点 → 合计 14 点 ≈ 1.75mm）。
     *
     * 为什么单独给二维码加一格：^BQ 的 quiet zone 靠模型自身的 4 个模块（mag4 时约 8 点），
     * 印出来后下缘再被文字/HRI 蹭掉一点，安静区一破，扫描器锁定位图就变难。
     * 一维码的 HRI 本来就该贴着条（那是码制规范），所以只对二维码加这一毫米。
     */
    val QR_TEXT_GAP: Int get() = MIN_TEXT_BARCODE_GAP + mm2dots(QR_EXTRA_GAP_MM)

    /** 二维码额外净空（毫米）。单独抽出来，改数值不用碰逻辑。 */
    const val QR_EXTRA_GAP_MM = 1.0

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
        // 实测整版字号统一小一号（约 12~15%）：OCR 行框往往比字形窄一点点，
        // 双约束取 min 之后又偏保守，纸上就整体小一号。统一放大 1.15 补偿。
        return maxOf(8, Math.round(minOf(fsH, fsW) * FONT_GAIN).toInt())
    }

    /**
     * 文字基线偏移。
     *
     * 原式 lines × 字号 会让整版文字偏高约 1.5mm（用户实测：文字压在上一条分隔线上方）。
     * 加 12 点 = 1.5mm @203dpi（8 点/mm）把整版文字压回设计位置；只影响文字，不动框线/条码。
     */
    fun anchorDyText(fontSize: Int, lines: Int = 1): Int = lines * fontSize + TEXT_BASELINE_FIX

    /** 1.5mm @203dpi */
    private const val TEXT_BASELINE_FIX = 12

    fun anchorDyBarcode1d(barHeight: Int): Int = barHeight

    fun anchorDyQr(blackH: Int, magnification: Int): Int = blackH + 4 * magnification

    /** QR 实印参数：mag、黑模块高、^FT 原点到符号下缘的偏移 */
    data class QrLayout(val mag: Int, val blackH: Int, val anchorDy: Int)

    /**
     * 算二维码**实印**尺寸。抽成一处给 [ZplBuilder] 净空计算双方共用 —— 两处各写一份 mag 算法
     * 迟早漂移，而净空算错就是"文字压到二维码上"。
     *
     * 关键点：`^BQ` 的 ^FT 锚点是符号（含 4 个模块 quiet zone）的**下缘**，所以"元素框底"
     * 不等于"印出来的底" —— 框底通常比实印下缘高 20~60 点，只按框底加净空会真的重叠。
     */
    fun qrLayout(x: Int, w: Float, text: String, labelW: Int): QrLayout {
        val modules = qrModulesForText(text)
        val usable = labelW - MARGIN - x
        var mag = (w / modules).roundToInt().coerceIn(2, 10)
        while (mag > 2 && modules * mag > usable) mag -= 1
        val blackH = modules * mag
        return QrLayout(mag, blackH, anchorDyQr(blackH, mag))
    }

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

    // ------------------------------------------------------------------
    // 码制：ZXing/Zebra 报的码制名 -> 内部键 -> ZPL 命令 + 模块数
    //
    // V1.9 的老逻辑只有一句 `format.contains("128") ? code128 : code39`，
    // 于是 EAN-13 的标签被打成 ^BC（Code128）—— 纸面上条码看着像，扫描器却能看出
    // 起止符和校验位全不对，HRI 也被重排（用户实测：6 934024 512123 -> 934024"512123）。
    // 这里与 PC 端 tools/zpl_geometry.py 的 SYMBOLOGY/BARCODE_CMD 必须逐项一致。
    // ------------------------------------------------------------------
    private val SYMBOLOGY = mapOf(
        "EAN-13" to "ean13", "EAN13" to "ean13",
        "EAN-8" to "ean8", "EAN8" to "ean8",
        "UPC-A" to "upca", "UPCA" to "upca",
        "UPC-E" to "upce", "UPCE" to "upce",
        "ITF" to "i2of5", "I2OF5" to "i2of5", "ITF-14" to "i2of5", "ITF14" to "i2of5",
        "Code 128" to "code128", "CODE128" to "code128", "Code128" to "code128",
        "Code 39" to "code39", "CODE39" to "code39", "Code39" to "code39",
        "Code 93" to "code93", "CODE93" to "code93",
        "Codabar" to "codabar", "CODABAR" to "codabar",
    )

    /** 固定模块数的零售码（zpl-core photo.py:_FIXED_MODULES）。按通用公式估会算小 15%，
     *  模块宽跟着放大，打出来比原码宽一截 —— 用户报的"条码比原标签胖"。 */
    private val FIXED_MODULES = mapOf("ean13" to 95, "upca" to 95, "ean8" to 67, "upce" to 51)

    /** 码制键 -> ZPL 命令（zpl-core builder.py:BARCODE_SPECS） */
    fun symbology(format: String?): String =
        SYMBOLOGY[format?.trim() ?: ""] ?: "code128"

    fun isFixedSymbology(sym: String): Boolean = FIXED_MODULES.containsKey(sym)

    /** 该码制的模块总数（用于从照片宽度反推模块宽） */
    fun barcodeModules(sym: String, text: String): Int {
        val n = maxOf(1, text.length)
        FIXED_MODULES[sym]?.let { return it }
        val digits = text.all { it.isDigit() }
        return when (sym) {
            "code39" -> code39Modules(n)
            "code93" -> 9 * n + 28
            // Interleaved 2 of 5：每对数字 16 模块（宽窄比 2.5）+ 起始 4 + 终止 4.5
            "i2of5" -> 8 * n + 9
            "codabar" -> 11 * n + 21
            else -> code128Modules(n, digits)
        }
    }

    /**
     * 一维码字段：^BY + ^FT + 码制命令。
     *
     * 参数按各命令自己的字母序写全（^B3/^B0 是 o,e,h,f,g；^B9/^B2/^BA 是 o,h,f,g[,e,j]）——
     * 少写一个逗号后面的值会被当成下一个参数，打出来是错的码制或者直接报 ^XA 错误。
     * PC 端 tools/zpl_geometry.py:barcode_1d_field 是同一份实现。
     */
    fun barcode1dField(
        sym: String,
        x: Int,
        anchor: Int,
        moduleWidth: Double,
        barHeight: Int,
        hri: Boolean,
        ratio: Double = DEFAULT_BY_RATIO,
    ): String {
        val mw = fmtNum(moduleWidth)
        val f = if (hri) "Y" else "N"
        val head = "^BY$mw,${fmtNum(ratio)},$barHeight^FT$x,$anchor"
        val cmd = when (sym) {
            "ean13" -> "^BEN,$barHeight,$f,N"
            "ean8" -> "^B8N,$barHeight,$f,N"
            "upca" -> "^B9N,$barHeight,$f,N,N"
            "upce" -> "^B0N,N,$barHeight,$f,N"
            "i2of5" -> "^B2N,$barHeight,$f,N,N,N"
            "code39" -> "^B3N,N,$barHeight,$f,N"
            "code93" -> "^BAN,$barHeight,$f,N,N"
            "codabar" -> "^BKN,N,$barHeight,$f,N,A,A"
            else -> "^BCN,$barHeight,$f,N,N"
        }
        return head + cmd
    }

    /** `^BY n,m` 里 m 的默认值（条宽比）。现场旧标签几乎都是 2.5 */
    const val DEFAULT_BY_RATIO = 2.5

    /**
     * 文字自动字号 —— ZplBuilder 与「编辑要素」弹窗**共用这一份**。
     * 弹窗要显示"自动值"让用户在此基础上加减，若两边各写一套公式，弹窗里的数字
     * 就会和实际打出来的字号对不上（用户会以为改坏了）。
     */
    fun autoFontSize(e: LabelElement, labelW: Int): Int {
        val x = roundHalf(e.dots[0].toDouble())
        val maxW = maxOf(40, labelW - MARGIN - x)
        val inkW = if (e.inkW > 0f) e.inkW else (e.dots[2] - e.dots[0])
        val inkH = if (e.inkH > 0f) e.inkH else (e.dots[3] - e.dots[1])
        val budgetW = maxOf(20.0, minOf(maxW.toDouble(), inkW / WIDTH_SLACK))
        return fontSizeFor(budgetW, inkH.toDouble(), e.text)
    }

    /** 条码模块宽 ^BY 的 n（自动值）—— 同上，供弹窗显示 */
    fun autoModuleWidth(e: LabelElement): Double {
        val sym = e.symbology.ifEmpty { symbology(e.format) }
        val modules = barcodeModules(sym, e.text)
        if (modules <= 0) return MIN_MODULE_DOTS
        val derived = (e.dots[2] - e.dots[0]) / modules
        return maxOf(MIN_MODULE_DOTS, Math.round(minOf(derived, 10f) * 2) / 2.0)
    }

    internal fun fmtNum(v: Double): String =
        if (v == Math.floor(v)) v.toInt().toString() else v.toString()

    /** 印出来的墨迹宽 / 推进宽（zpl-core 实测）。照片量到的是墨迹宽，反推字号要除回来。 */
    const val WIDTH_SLACK = 0.92
    /** 字号整体补偿系数：实测整版偏小一号（12~15%），取 1.15 */
    const val FONT_GAIN = 1.15

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
