package com.tiaomabang.labelreprint.barcode

import android.graphics.Bitmap
import android.graphics.Rect
import com.tiaomabang.labelreprint.model.DetectedBarcode
import zxingcpp.BarcodeReader

/** 条码/二维码识别引擎。Zebra 机型走 AI Data Capture 的 DL 模型，其它机型回落 zxing-cpp。 */
interface BarcodeEngine {
    val name: String
    fun decode(bitmap: Bitmap): List<DetectedBarcode>
}

/**
 * zxing-cpp 1.6+（`io.github.zxing-cpp:android:3.1.1`）。
 * 纯本地 natives、4 个 ABI、无 Google Play Services 依赖 —— 企业机/无 GMS 机器也能用。
 * 与 PC 端参考实现用的是同一个解码库，所以识别结果可以逐字对比。
 */
class ZxingCppEngine : BarcodeEngine {

    override val name = "zxing-cpp"

    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.ALL_READABLE),
            // tryHarder 是这里唯一必需的开关，实测（tools/device_zpl_check.py + ZxingDiag）：
            //  默认            -> 样例2 的一维码直接解不出；解出的也回"扫描线"（2 点高的框）
            //  tryHarder=true  -> 解得出，且返回**完整符号包围盒**（lines=63，与 PC 端逐点一致）
            tryHarder = true,
        )
    )

    /** 最近一次解码耗时（ms），用于真机性能观测 */
    var lastMs: Long = 0
        private set

    override fun decode(bitmap: Bitmap): List<DetectedBarcode> {
        val t0 = System.currentTimeMillis()
        val out = ArrayList<DetectedBarcode>()
        // 必须显式给整幅 cropRect：默认值 Rect() 是"空矩形"，原生层按 0 高 ROI 处理，
        // 解码照样成功但返回的坐标会被裁歪（实测一维码只回 2 点高的框、部分码直接丢）。
        val full = Rect(0, 0, bitmap.width, bitmap.height)
        for (r in reader.read(bitmap, full, 0)) {
            android.util.Log.i("LABELREPRINT",
                "ZXRAW >>> ${r.format} '${r.text}' err=${r.error} lines=${r.lineCount} rot=${r.orientation} " +
                    "TL=(${r.position.topLeft.x},${r.position.topLeft.y}) " +
                    "BR=(${r.position.bottomRight.x},${r.position.bottomRight.y})")
            if (r.error != null) continue                       // 校验和/纠错没过的一律不要
            val p = r.position
            val xs = listOf(p.topLeft.x, p.topRight.x, p.bottomRight.x, p.bottomLeft.x)
            val ys = listOf(p.topLeft.y, p.topRight.y, p.bottomRight.y, p.bottomLeft.y)
            out += DetectedBarcode(
                text = r.text ?: "",
                format = friendly(r.format),
                isTwoD = isTwoD(r.format),
                px = listOf(xs.min().toFloat(), ys.min().toFloat(), xs.max().toFloat(), ys.max().toFloat()),
                quad = listOf(
                    p.topLeft.x.toFloat() to p.topLeft.y.toFloat(),
                    p.topRight.x.toFloat() to p.topRight.y.toFloat(),
                    p.bottomRight.x.toFloat() to p.bottomRight.y.toFloat(),
                    p.bottomLeft.x.toFloat() to p.bottomLeft.y.toFloat(),
                ),
                source = name,
            )
        }
        lastMs = System.currentTimeMillis() - t0
        return out
    }

    private fun isTwoD(f: BarcodeReader.Format): Boolean = when (f) {
        BarcodeReader.Format.QR_CODE, BarcodeReader.Format.QR_CODE_MODEL_1, BarcodeReader.Format.QR_CODE_MODEL_2,
        BarcodeReader.Format.MICRO_QR_CODE, BarcodeReader.Format.RMQR_CODE, BarcodeReader.Format.DATA_MATRIX,
        BarcodeReader.Format.PDF_417, BarcodeReader.Format.COMPACT_PDF_417, BarcodeReader.Format.MICRO_PDF_417,
        BarcodeReader.Format.AZTEC, BarcodeReader.Format.AZTEC_CODE, BarcodeReader.Format.AZTEC_RUNE,
        BarcodeReader.Format.MAXI_CODE -> true
        else -> false
    }

    /** 与 PC 端 zxing 的字符串名对齐（ZPL 生成时按名字判断 ^BC/^B3/^BQ） */
    private fun friendly(f: BarcodeReader.Format): String = when (f) {
        BarcodeReader.Format.CODE_128 -> "Code 128"
        BarcodeReader.Format.CODE_39, BarcodeReader.Format.CODE_39_STD -> "Code 39"
        BarcodeReader.Format.CODE_39_EXT -> "Code 39 Extended"
        BarcodeReader.Format.CODE_93 -> "Code 93"
        BarcodeReader.Format.QR_CODE, BarcodeReader.Format.QR_CODE_MODEL_2 -> "QR Code"
        BarcodeReader.Format.MICRO_QR_CODE -> "Micro QR Code"
        BarcodeReader.Format.DATA_MATRIX -> "Data Matrix"
        BarcodeReader.Format.PDF_417 -> "PDF417"
        BarcodeReader.Format.AZTEC, BarcodeReader.Format.AZTEC_CODE -> "Aztec"
        BarcodeReader.Format.EAN_13 -> "EAN-13"
        BarcodeReader.Format.EAN_8 -> "EAN-8"
        BarcodeReader.Format.UPC_A -> "UPC-A"
        BarcodeReader.Format.UPC_E -> "UPC-E"
        BarcodeReader.Format.ITF -> "ITF"
        BarcodeReader.Format.CODABAR -> "Codabar"
        else -> f.name
    }
}
