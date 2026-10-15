package com.tiaomabang.labelreprint.ocr

/**
 * One recognized text line.
 *
 * @param text       decoded text
 * @param confidence mean CTC confidence in [0,1]
 * @param box        4 corner points in upright frame pixel coordinates,
 *                   ordered clockwise from the top-left (x0,y0,x1,y1,x2,y2,x3,y3)
 */
data class OcrResult(
    val text: String,
    val confidence: Float,
    val box: FloatArray
) {
    val minX: Float get() = minOf(box[0], box[2], box[4], box[6])
    val minY: Float get() = minOf(box[1], box[3], box[5], box[7])
    val centerX: Float get() = (box[0] + box[2] + box[4] + box[6]) / 4f
    val centerY: Float get() = (box[1] + box[3] + box[5] + box[7]) / 4f

    override fun equals(other: Any?): Boolean =
        other is OcrResult && other.text == text && other.confidence == confidence

    override fun hashCode(): Int = 31 * text.hashCode() + confidence.hashCode()
}
