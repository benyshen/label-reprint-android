package com.tiaomabang.labelreprint.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tiaomabang.labelreprint.pipeline.TextLine
import org.opencv.android.OpenCVLoader

/**
 * PP-OCRv4 文字识别的门面：把 [OcrEngine]（从 paddleocr-android-app 移植过来的、已与
 * Python 参考实现 `tools/ppocr_ref.py` 对齐的实现）的输出转成流水线要的 [TextLine]。
 *
 * OcrResult.box 是 4 个角点（8 个数），转成 x0,y0,x1,y1 的轴对齐框 —— 与 PC 端
 * `label_to_zpl.py` 用 cv2 边界矩形一致。
 */
class PaddleOcrEngine(
    private val ctx: Context,
    backend: InferenceBackend = InferenceBackend.CPU,
) {

    private val engine: OcrEngine? = if (ensureOpenCv()) {
        runCatching { OcrEngine(ctx, backend) }.getOrElse { t ->
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "OCR 引擎初始化失败：$lastError", t)
            null
        }
    } else {
        null
    }

    var lastError: String? = null
        private set

    var lastMs: Long = 0
        private set

    val ready: Boolean get() = engine != null

    /** 推理后端实际落在哪个 EP（请求 NPU 但驱动不可用时会回退 CPU，原因在 [backendNote]） */
    val backendLabel: String get() = engine?.activeBackend?.label ?: "不可用"
    val backendNote: String get() = engine?.backendNote ?: ""

    fun lines(bitmap: Bitmap): List<TextLine> {
        val e = engine ?: return emptyList()
        val t0 = System.currentTimeMillis()
        val out = e.run(bitmap).map { r ->
            // box = 4 角点（x0,y0,x1,y1,x2,y2,x3,y3）；轴对齐包围盒与 PC 端 cv2.boundingRect 一致
            val xs = listOf(r.box[0], r.box[2], r.box[4], r.box[6])
            val ys = listOf(r.box[1], r.box[3], r.box[5], r.box[7])
            TextLine(
                text = r.text,
                px = listOf(xs.min(), ys.min(), xs.max(), ys.max()),
                conf = r.confidence,
                source = "ocr",
            )
        }
        lastMs = System.currentTimeMillis() - t0
        Log.i(TAG, "OCR >>> ${bitmap.width}x${bitmap.height} -> ${out.size} 行（${lastMs}ms，" +
            "backend=$backendLabel ${backendNote}）")
        out.forEach { Log.i(TAG, "OCRLINE >>> '${it.text}' conf=${"%.2f".format(it.conf)} px=${it.px}") }
        return out
    }

    fun close() {
        runCatching { engine?.close() }
    }

    private fun ensureOpenCv(): Boolean {
        if (openCvReady) return true
        val ok = runCatching { OpenCVLoader.initLocal() }.getOrElse { false }
        if (ok) openCvReady = true else lastError = "OpenCV 动态库加载失败"
        Log.i(TAG, "OpenCV initLocal -> $ok")
        return ok
    }

    companion object {
        private const val TAG = "LABELREPRINT"

        @Volatile
        private var openCvReady = false
    }
}
