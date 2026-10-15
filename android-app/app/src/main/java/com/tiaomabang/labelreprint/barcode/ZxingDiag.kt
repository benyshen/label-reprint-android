package com.tiaomabang.labelreprint.barcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import zxingcpp.BarcodeReader

/**
 * 一次性诊断：为什么同一个 zxing-cpp 3.1.1 在 Android 上给一维码回"2 点细条"、
 * 在 PC(Python) 上给整框。逐项换输入/选项，把差异钉死。
 */
object ZxingDiag {

    fun run(bitmap: Bitmap, tag: String = "LABELREPRINT") {
        val full = Rect(0, 0, bitmap.width, bitmap.height)
        val cases = linkedMapOf<String, Pair<Bitmap, BarcodeReader.Options>>(
            "A formats=ALL_READABLE" to (bitmap to
                BarcodeReader.Options(formats = setOf(BarcodeReader.Format.ALL_READABLE))),
            "B Options()默认" to (bitmap to BarcodeReader.Options()),
            "C minLineCount=1" to (bitmap to
                BarcodeReader.Options(formats = setOf(BarcodeReader.Format.ALL_READABLE), minLineCount = 1)),
            "D minLineCount=10" to (bitmap to
                BarcodeReader.Options(formats = setOf(BarcodeReader.Format.ALL_READABLE), minLineCount = 10)),
            "E 灰度化ARGB" to (toGrayscaleArgb(bitmap) to
                BarcodeReader.Options(formats = setOf(BarcodeReader.Format.ALL_READABLE))),
            "F 2x放大" to (scale(bitmap, 2f) to
                BarcodeReader.Options(formats = setOf(BarcodeReader.Format.ALL_READABLE))),
            "G RGB_565" to (bitmap.copy(Bitmap.Config.RGB_565, false) to
                BarcodeReader.Options(formats = setOf(BarcodeReader.Format.ALL_READABLE))),
            "H binarizer=GLOBAL_HISTOGRAM" to (bitmap to BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.ALL_READABLE),
                binarizer = BarcodeReader.Binarizer.GLOBAL_HISTOGRAM)),
            "I binarizer=FIXED_THRESHOLD" to (bitmap to BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.ALL_READABLE),
                binarizer = BarcodeReader.Binarizer.FIXED_THRESHOLD)),
            "J tryHarder" to (bitmap to BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.ALL_READABLE), tryHarder = true)),
            "K tryDenoise" to (bitmap to BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.ALL_READABLE), tryDenoise = true)),
            "L tryDownscale=false" to (bitmap to BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.ALL_READABLE), tryDownscale = false)),
            "M 1.5x" to (scale(bitmap, 1.5f) to BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.ALL_READABLE))),
            "N 3x" to (scale(bitmap, 3f) to BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.ALL_READABLE))),
        )
        for ((label, cfg) in cases) {
            val (bmp, opt) = cfg
            val reader = BarcodeReader(opt)
            val sb = StringBuilder()
            try {
                for (r in reader.read(bmp, Rect(0, 0, bmp.width, bmp.height), 0)) {
                    val p = r.position
                    sb.append(" [${r.format} '${r.text}' lines=${r.lineCount} " +
                        "(${p.topLeft.x},${p.topLeft.y})-(${p.bottomRight.x},${p.bottomRight.y})]")
                }
                if (sb.isEmpty()) sb.append(" (无结果)")
            } catch (t: Throwable) {
                sb.append(" EX ${t.message}")
            }
            Log.i(tag, "DIAG $tag $label ->$sb")
        }
        Log.i(tag, "DIAG $tag full=$full cfg=${bitmap.config} size=${bitmap.width}x${bitmap.height}")
    }

    private fun toGrayscaleArgb(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(floatArrayOf(
                    0.299f, 0.587f, 0.114f, 0f, 0f,
                    0.299f, 0.587f, 0.114f, 0f, 0f,
                    0.299f, 0.587f, 0.114f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f)))
        }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun scale(src: Bitmap, f: Float): Bitmap =
        Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
}
