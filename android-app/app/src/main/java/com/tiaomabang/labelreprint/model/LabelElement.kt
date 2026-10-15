package com.tiaomabang.labelreprint.model

enum class ElementKind { BARCODE_1D, BARCODE_2D, TEXT }

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
