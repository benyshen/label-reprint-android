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

    /** 最近一次解码耗时（ms），用于真机性能观测 */
    var lastMs: Long = 0
        private set

    /** 每一轮补救的命中情况（`1d=1/3 210ms` 这种），写进日志定位"卡在哪一轮" */
    val passes = ArrayList<String>()

    /**
     * 解码：先按原图全码制跑一轮；**没解出一维码**就依次补救。
     *
     * 为什么要补救：TC201 这类机器上 Zebra DL 引擎初始化不了（厂商 SDK 直接抛
     * `AI Vision SDK is not supported`，连 Zebra 自家 App 也一样），一维码只能靠 zxing。
     * 一维码在整幅手机照片里常常只有 2~3 px/模块、还带反光和轻微倾斜，默认一趟经常 0 命中；
     * 换成「限定一维码 + 去噪 + 反色 + 2 倍放大 + 直方图二值化」这几条更容易解的设置再跑几趟，
     * 命中率明显上来。代价是几轮合起来 100~400ms，只在一维码没解出来时才付。
     *
     * 坐标一律按放大倍数除回去，宁可坐标略粗，也不能因为坐标尺度不同把要素排到标签外面。
     */
    override fun decode(bitmap: Bitmap): List<DetectedBarcode> {
        val t0 = System.currentTimeMillis()
        passes.clear()
        val out = LinkedHashMap<String, DetectedBarcode>()

        // 第 1 轮：原图，全码制。二维码在这一轮基本都能出来（实测 TC201 上 <300ms）
        readInto("base", bitmap, 1f, out, ONE_D_AND_2D, invert = false, denoise = false, bin = null)

        val has1d = out.values.any { !it.isTwoD }
        if (!has1d) {
            // 第 2 轮：只认一维码 + 去噪。限定码制能显著减少误检、也把算力集中到扫描线上
            readInto("1d", bitmap, 1f, out, LINEAR, invert = false, denoise = true, bin = null)
            // 第 3 轮：反色（浅底深条之外，银底/黑底标签也有）
            if (out.values.none { !it.isTwoD }) {
                readInto("1d-inv", bitmap, 1f, out, LINEAR, invert = true, denoise = true, bin = null)
            }
            // 第 4/5 轮：2 倍放大。模块太细（每模块 < 2px）时，放大后 zxing 才能切出扫描线
            if (out.values.none { !it.isTwoD }) {
                val up = runCatching {
                    Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
                }.getOrNull()
                if (up != null) {
                    readInto("1d-2x", up, 2f, out, LINEAR, invert = false, denoise = true, bin = null)
                    if (out.values.none { !it.isTwoD }) {
                        // 直方图二值化：光照不均（一半亮一半暗）时比默认的局部平均更稳
                        readInto("1d-2x-hist", up, 2f, out, LINEAR, invert = false, denoise = true,
                            bin = BarcodeReader.Binarizer.GLOBAL_HISTOGRAM)
                    }
                    up.recycle()
                }
            }
        }

        lastMs = System.currentTimeMillis() - t0
        android.util.Log.i("LABELREPRINT", "ZXPASS >>> ${passes.joinToString(" ")} 合计=${lastMs}ms")
        return out.values.toList()
    }

    /** 跑一轮并把结果并进 [out]（同码制同内容只留框大的那个；[scale] 用来把放大图的坐标除回原图） */
    private fun readInto(
        tag: String,
        src: Bitmap,
        scale: Float,
        out: LinkedHashMap<String, DetectedBarcode>,
        formats: Set<BarcodeReader.Format>,
        invert: Boolean,
        denoise: Boolean,
        bin: BarcodeReader.Binarizer?,
    ) {
        val t0 = System.currentTimeMillis()
        val opt = BarcodeReader.Options(
            formats = formats,
            tryHarder = true,
            tryRotate = true,
            tryInvert = invert,
            tryDownscale = true,
            tryDenoise = denoise,
            binarizer = bin ?: BarcodeReader.Binarizer.LOCAL_AVERAGE,
            // 一维码的 lines 少也可以接受：手机上条高本来就小，默认 2 会把矮条码滤掉
            minLineCount = 1,
            maxNumberOfSymbols = 8,
        )
        val rs = runCatching {
            // 必须显式给整幅 cropRect：默认值 Rect() 是"空矩形"，原生层按 0 高 ROI 处理，
            // 解码照样成功但返回的坐标会被裁歪（实测一维码只回 2 点高的框、部分码直接丢）。
            BarcodeReader(opt).read(src, Rect(0, 0, src.width, src.height), 0)
        }.getOrElse { t ->
            passes += "$tag=ERR:${t.javaClass.simpleName}"
            return
        }
        var hit = 0
        for (r in rs) {
            if (tag == "base") {
                android.util.Log.i("LABELREPRINT",
                    "ZXRAW >>> ${r.format} '${r.text}' err=${r.error} lines=${r.lineCount} rot=${r.orientation} " +
                        "TL=(${r.position.topLeft.x},${r.position.topLeft.y}) " +
                        "BR=(${r.position.bottomRight.x},${r.position.bottomRight.y})")
            }
            if (r.error != null) continue                 // 校验和/纠错没过的一律不要
            val p = r.position
            val xs = listOf(p.topLeft.x, p.topRight.x, p.bottomRight.x, p.bottomLeft.x)
            val ys = listOf(p.topLeft.y, p.topRight.y, p.bottomRight.y, p.bottomLeft.y)
            val b = DetectedBarcode(
                text = r.text ?: "",
                format = friendly(r.format),
                isTwoD = isTwoD(r.format),
                px = listOf(xs.min() / scale, ys.min() / scale, xs.max() / scale, ys.max() / scale),
                quad = listOf(
                    p.topLeft.x / scale to p.topLeft.y / scale,
                    p.topRight.x / scale to p.topRight.y / scale,
                    p.bottomRight.x / scale to p.bottomRight.y / scale,
                    p.bottomLeft.x / scale to p.bottomLeft.y / scale,
                ),
                source = name,
            )
            val key = "${b.format}|${b.text}"
            val old = out[key]
            if (old == null || area(b.px) > area(old.px)) out[key] = b
            hit++
        }
        passes += "$tag=$hit/${rs.size} ${System.currentTimeMillis() - t0}ms"
    }

    private fun area(p: List<Float>) = maxOf(0f, p[2] - p[0]) * maxOf(0f, p[3] - p[1])

    private companion object {
        /** 一维码码制白名单（补救轮用） */
        val LINEAR: Set<BarcodeReader.Format> = setOf(
            BarcodeReader.Format.CODE_128,
            BarcodeReader.Format.CODE_39,
            BarcodeReader.Format.CODE_39_STD,
            BarcodeReader.Format.CODE_93,
            BarcodeReader.Format.CODABAR,
            BarcodeReader.Format.ITF,
            BarcodeReader.Format.ITF_14,
            BarcodeReader.Format.EAN_13,
            BarcodeReader.Format.EAN_8,
            BarcodeReader.Format.UPC_A,
            BarcodeReader.Format.UPC_E,
            BarcodeReader.Format.DATA_BAR,
            BarcodeReader.Format.DATA_BAR_EXP,
        )

        val ONE_D_AND_2D: Set<BarcodeReader.Format> = setOf(BarcodeReader.Format.ALL_READABLE)
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
