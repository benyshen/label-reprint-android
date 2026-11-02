package com.tiaomabang.labelreprint.model

enum class ElementKind { BARCODE_1D, BARCODE_2D, TEXT, BOX }

/**
 * 识别出来的一个打印要素。不可变 + copy()：Compose 的 StateFlow 靠 equals 判变化，
 * 原地改字段再 copy 会因为新旧相等而不触发重组（"点了没反应"）。
 */
data class LabelElement(
    val kind: ElementKind,
    val text: String,
    val format: String = "",          // zxing 的码制名，如 "Code 128" / "QR Code"
    val px: List<Float> = listOf(0f, 0f, 0f, 0f),   // 照片像素坐标 x0,y0,x1,y1
    val hri: Boolean = false,         // 条码下方人眼可读串 -> 由 ^BC...,Y 打印，不再单独出文字要素
    val hriPx: List<Float>? = null,
    val dots: List<Float> = listOf(0f, 0f, 0f, 0f), // 标签点阵坐标 x0,y0,x1,y1
    val conf: Float = 1f,
    val source: String = "",          // zxing / ocr / user
    /** 内部码制键（ean13/code128/…）：决定发 ^BE 还是 ^BC，见 ZplGeometry.symbology */
    val symbology: String = "",
    /** 墨迹尺寸（照片像素）：字号必须按墨迹算，OCR 行框比字形胖 10~20% */
    val inkPxW: Float = 0f,
    val inkPxH: Float = 0f,
    /** 墨迹尺寸（点）：layout 时由 inkPx × scale 换算，ZplBuilder 只用这一份 */
    val inkW: Float = 0f,
    val inkH: Float = 0f,
    /** 用户指定的字号（打印点）：0 = 按墨迹自动算。只对 TEXT 生效 */
    val fsOverride: Int = 0,
    /** 用户指定的条码模块宽 ^BY 的 n（点）：0 = 按原图推算。只对 BARCODE_1D 生效 */
    val byN: Float = 0f,
    /** 用户指定的条宽比 ^BY 的 m：0 = 默认 2.5。只对 BARCODE_1D 生效 */
    val byM: Float = 0f,
) {
    val w: Float get() = dots[2] - dots[0]
    val h: Float get() = dots[3] - dots[1]

    fun shiftDots(dx: Float, dy: Float): LabelElement = copy(
        dots = listOf(dots[0] + dx, dots[1] + dy, dots[2] + dx, dots[3] + dy))

    fun withDots(x0: Float, y0: Float, x1: Float, y1: Float): LabelElement =
        copy(dots = listOf(x0, y0, x1, y1))
}

/** 一条识别流水线的结果 */
data class ReprintPlan(
    val imageName: String,
    val imageSize: Pair<Int, Int>,
    val basis: String,                       // label / full / content
    val template: Int,
    val labelDots: Pair<Int, Int>,
    val scale: Float,
    val elements: List<LabelElement>,
    val dropped: List<String>,               // 去掉的 OCR 行及原因（可解释性）
    val warnings: List<String>,
    val zpl: String,
    val zplErrors: List<String>,
)

data class DetectedBarcode(
    val text: String,
    val format: String,
    val isTwoD: Boolean,
    val px: List<Float>,
    val quad: List<Pair<Float, Float>>,
    val source: String,
)
