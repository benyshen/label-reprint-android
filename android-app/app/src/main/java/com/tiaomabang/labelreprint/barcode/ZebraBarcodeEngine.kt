package com.tiaomabang.labelreprint.barcode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.tiaomabang.labelreprint.model.DetectedBarcode
import com.zebra.ai.vision.detector.AIVisionSDK
import com.zebra.ai.vision.detector.BBox
import com.zebra.ai.vision.detector.BarcodeDecoder
import com.zebra.ai.vision.detector.ImageData
import com.zebra.ai.vision.detector.InferencerOptions
import com.zebra.ai.vision.detector.Localizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Zebra AI Data Capture 的 DL 条码模型（barcode-decoder-5.0.4 + AI-Data-Capture-SDK-4.1.0）。
 *
 * 为什么用它而不是 zxing-cpp：zxing-cpp 的 Android 封装对照片里的一维码会回"扫描线"
 * 而不是符号包围盒（同一版本在 PC 上回整框），条高只能自己从像素量；DL 模型直接给
 * 包围盒 + 四角，且对模糊/倾斜/低对比的照片更稳。
 *
 * 静态照片有 3 条可走的路，实现里全部试一遍再合并去重（[Strategy]）：
 *   PROCESS      —— `process(ImageData.fromBitmap())`，摄像头那条正规链路，返回多结果
 *   LOCALIZE     —— 自己 `Localizer.detect()` 出候选框，再 `decode(bitmap, bboxes)` 逐个解
 *   DECODE_ALL   —— `decode(bitmap)` 整幅图，解出一个就把该框涂白再解下一个
 *
 * 实测结论（probe 矩阵，TC73 + barcode-decoder-5.0.4）：只有 DECODE_ALL 能出一维码。
 * LOCALIZE 这条解不出一维码 —— DL 的 localizer 会把一个 Code128 拆成 2~3 个相互重叠的
 * 半框（干净测试图 605px 宽也给 47..223 / 328..530 / 144..350 三个），原框、外扩框、
 * 合并框、整图 2x 放大后重新检测的框，统统喂给 `decode(bitmap, boxes)` 都不出结果；
 * 但同一张图走 `decode(bitmap)` 的原生内部管线就稳稳解出。所以 LOCALIZE 只当二维码的补充。
 * DECODE_ALL 是主力，且必须配 AI_BARCODE_DECODE=false。
 */
class ZebraBarcodeEngine(
    private val ctx: Context,
    /** 检测模型输入边长；1600 更准但更慢，1280 是 demo 的默认档 */
    private val modelInput: Int = 1280,
    private val timeoutSec: Long = 120,
) : BarcodeEngine {

    override val name = "zebra-dl"

    enum class Strategy { PROCESS, LOCALIZE, DECODE_ALL }

    /** SUCCESS = 有结果；NONE = 跑通了但没有符号；FAILED = 抛异常（原因写进 lastError） */
    data class Step(val strategy: Strategy, val outcome: String, val count: Int, val detail: String)

    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "zebra-decode") }

    private var decoder: BarcodeDecoder? = null
    private var localizer: Localizer? = null
    private var settings: BarcodeDecoder.Settings? = null

    /** 每次 decode() 的各策略明细，给 UI/诊断看"到底是哪条路解出来的" */
    val steps = ArrayList<Step>()

    var initMs: Long = 0
        private set
    var lastMs: Long = 0
        private set
    var lastError: String? = null
        private set

    private val lock = Any()

    /** 本次 decode 的截止时刻（见 [DECODE_BUDGET_MS]） */
    private var deadlineMs = 0L

    private fun expired(): Boolean = System.currentTimeMillis() > deadlineMs

    @Volatile
    private var ready = false

    val isReady: Boolean get() = ready

    /** 初始化 SDK + DL 模型。模型资产 147MB，首次会解到应用私有目录，耗时几十秒。 */
    fun ensureInit(): Boolean {
        if (ready) return true
        synchronized(lock) {
            if (ready) return true
            val t0 = System.currentTimeMillis()
            try {
                val sdk = AIVisionSDK.getInstance(ctx)
                if (!sdk.init()) {
                    lastError = "AIVisionSDK.init() 返回 false"
                    return false
                }
                settings = buildSettings(loc = true, ai = AI_BARCODE_DECODE, symMode = 1)
                val st = settings!!
                Log.i(TAG, "ZEBRA settings: detectorSetting=${st.detectorSetting != null} " +
                    "enableAI=${st.enableAIBarcodeDecode} enableLoc=${st.enableLocalization} " +
                    "dims=${st.detectorSetting?.inferencerOptions?.defaultDims?.width}x" +
                    "${st.detectorSetting?.inferencerOptions?.defaultDims?.height}")

                decoder = decoderFor(enableLocalization = true)
                initMs = System.currentTimeMillis() - t0
                ready = true
                lastError = null
                Log.i(TAG, "ZEBRA decoder ready in ${initMs}ms")
                return true
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${rootMessage(t)}"
                Log.e(TAG, "ZEBRA init failed: $lastError", t)
                return false
            }
        }
    }

    /** decoder 变体缓存：key = "loc|ai|symMode"。换 loc / AI 开关 / 码制都要重建实例 */
    private val variants = HashMap<String, BarcodeDecoder>()

    /**
     * @param loc     enableLocalization
     * @param ai      enableAIBarcodeDecode
     * @param symMode 0 = 完全不碰 symbology（贴近 demo 默认）
     *                1 = 打开常用码制（逐个 enable(true)）
     *                2 = DisableAllSymbologies() 后只留 CODE128 + QRCODE
     */
    private fun buildSettings(loc: Boolean, ai: Boolean, symMode: Int): BarcodeDecoder.Settings {
        val st = BarcodeDecoder.Settings(RESOURCE)
        st.enableAIBarcodeDecode = ai
        st.enableDuplicateBarcodeDecode = true
        st.enableLocalization = loc
        when (symMode) {
            1 -> enableCommonSymbologies(st)
            2 -> {
                st.DisableAllSymbologies()
                st.Symbology?.CODE128?.enable(true)
                st.Symbology?.QRCODE?.enable(true)
            }
        }
        st.detectorSetting?.inferencerOptions?.let { io ->
            // DSP(2) -> CPU(0) -> GPU(1)：TC73 有 HTP，DSP 最快；CPU 兜底
            io.runtimeProcessorOrder = arrayOf(
                InferencerOptions.DSP, InferencerOptions.CPU, InferencerOptions.GPU)
            io.defaultDims?.let { d ->
                d.width = modelInput
                d.height = modelInput
            }
        }
        return st
    }

    @Synchronized
    private fun decoderVariant(loc: Boolean, ai: Boolean, symMode: Int): BarcodeDecoder {
        val key = "$loc|$ai|$symMode"
        variants[key]?.let { return it }
        val st = buildSettings(loc, ai, symMode)
        if (loc) settings = st
        val d = BarcodeDecoder.getBarcodeDecoder(st, exec).get(timeoutSec, TimeUnit.SECONDS)
        variants[key] = d
        return d
    }

    private fun decoderFor(enableLocalization: Boolean): BarcodeDecoder =
        decoderVariant(enableLocalization, ai = AI_BARCODE_DECODE, symMode = 1)

    override fun decode(bitmap: Bitmap): List<DetectedBarcode> {
        steps.clear()
        if (!ensureInit()) return emptyList()
        val t0 = System.currentTimeMillis()
        // 单张照片的解码总预算。真机 12MP 照片上，检测网络会把桌面/包装纹理也当候选，
        // 逐轮"放大重扫"能烧掉几分钟（现场表现是界面一直"识别中"）。超预算就收手，
        // 交给 zxing 的一维码结果 + 用户重拍。
        deadlineMs = t0 + DECODE_BUDGET_MS
        val out = ArrayList<DetectedBarcode>()

        for (s in Strategy.entries) {
            val got = try {
                runStrategy(s, bitmap)
            } catch (t: Throwable) {
                steps += Step(s, "FAILED", 0, "${t.javaClass.simpleName}: ${rootMessage(t)}")
                Log.w(TAG, "ZEBRA $s 抛异常", t)
                continue
            }
            steps += Step(s, if (got.isEmpty()) "NONE" else "SUCCESS", got.size, describe(got))
            Log.i(TAG, "ZEBRA $s -> ${if (got.isEmpty()) "无结果" else describe(got)}")
            for (b in got) merge(out, b)
        }

        lastMs = System.currentTimeMillis() - t0
        Log.i(TAG, "ZEBRA 合并后 ${out.size} 个（${lastMs}ms，init ${initMs}ms）：${describe(out)}")
        return out
    }

    // ---------------- 三条策略 ----------------

    // 块体函数：表达式体里不允许 bare return，而各分支都要能提前退出
    private fun runStrategy(s: Strategy, bmp: Bitmap): List<DetectedBarcode> {
        return when (s) {
            Strategy.PROCESS -> {
                val d = decoder ?: return emptyList()
                val img = ImageData.fromBitmap(bmp, 0)
                d.process(img, exec).get(timeoutSec, TimeUnit.SECONDS).mapNotNull { e ->
                    val box = e.boundingBox
                    if (box == null || box.width() <= 0 || box.height() <= 0) null
                    else DetectedBarcode(
                        text = e.value ?: "",
                        format = friendly(e.symbology, e.label),
                        isTwoD = isTwoD(e.symbology, e.label),
                        px = listOf(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
                        quad = e.corners?.takeIf { it.size >= 4 }?.map { it.x.toFloat() to it.y.toFloat() }
                            ?: quadOf(box),
                        source = "$name/PROCESS",
                    )
                }
            }

            Strategy.LOCALIZE -> {
                val loc = localizer() ?: return emptyList()
                val d = decoder ?: return emptyList()
                val boxes = loc.detect(bmp, exec).get(timeoutSec, TimeUnit.SECONDS)
                Log.i(TAG, "ZEBRA localizer 出 ${boxes.size} 个候选框：" +
                    boxes.joinToString { "(${it.xmin.toInt()},${it.ymin.toInt()})-(${it.xmax.toInt()},${it.ymax.toInt()}) p=${it.prob}" })
                if (boxes.isEmpty()) return emptyList()
                d.decode(bmp, boxes, exec).get(timeoutSec, TimeUnit.SECONDS).mapNotNull { r -> fromResult(r, "LOCALIZE") }
            }

            Strategy.DECODE_ALL -> {
                // decode(bitmap) 一次只回 top-1，标签上有多个符号（一维码 + 二维码）时必须
                // 靠"把已解出来的涂白再解下一个"逐个取。实测这是唯一能解出一维码的路：喂框给
                // decodeMulti 无论框多准都不出结果（localizer 会把一维符号拆成 2~3 段）。
                val d = decoder ?: return emptyList()
                val out = ArrayList<DetectedBarcode>()
                scanAndErase(d, bmp, 1f, 0f, 0f, out, "")
                // 检测网络看见了、1x 却没解出来的区域 -> 裁出来放大重扫。
                // 为什么需要：原图 2.5px/模块的 Code128，1x 解不出；同样内容放大到 8~10px/模块
                // 就能解（干净测试图 4.3px/模块可解，这条线在 3~4px 之间）。
                val seen = try { localize(bmp) } catch (t: Throwable) { emptyArray<BBox>() }
                val missing = seen.filter { bx -> out.none { covers(it.px, bx) } }
                if (missing.isEmpty() || out.size >= MAX_SYMBOLS) return out
                val reg = expandToBox(unionOf(missing), 0.06f, bmp.width, bmp.height)
                val regionPx = (reg[2] - reg[0]).toDouble() * (reg[3] - reg[1])
                val imagePx = bmp.width.toDouble() * bmp.height
                // 这条兜底只对「低分辨率小图」有意义（合成样图那种 2.5px/模块）。真机 12MP 照片上
                // 一个候选区域就有 2 百万像素，×4 放大 = 7000 万像素：既算不动也会 OOM，
                // 而且是白烧时间（检测网络把桌面/包装纹理也当成了候选）。所以按面积与预算设闸。
                if (imagePx > UPSCALE_MAX_SRC_PIXELS || regionPx > UPSCALE_MAX_REGION_PIXELS) {
                    Log.i(TAG, "ZEBRA DECODE_ALL: 跳过放大重扫（图 ${bmp.width}x${bmp.height}，" +
                        "区域 ${"%.0f".format(regionPx / 1e6)}MP 超过闸值 ${UPSCALE_MAX_REGION_PIXELS / 1e6}MP，" +
                        "真机上交给 zxing 处理一维码）")
                    return out
                }
                Log.i(TAG, "ZEBRA DECODE_ALL: ${missing.size} 个候选区域没解出，" +
                    "区域=[${reg.joinToString { "%.0f".format(it) }}] -> 裁出放大重扫")
                // 插值方式很关键：默认的双线性会把条码边缘抹成灰渐变（2.5px/模块本来就只有
                // 灰边），放大后反而更糊 -> 先试最近邻（硬边），最后再兜一次双线性。
                val ladder = listOf(2f to false, 3f to false, 4f to false, 3f to true)
                for ((f, smooth) in ladder) {
                    if (expired()) {
                        Log.i(TAG, "ZEBRA DECODE_ALL: 预算用尽，停止放大重扫")
                        break
                    }
                    if (regionPx * f * f > UPSCALE_MAX_OUT_PIXELS) {
                        Log.i(TAG, "ZEBRA DECODE_ALL: 跳过 ${f}x（放大后 >" +
                            "${UPSCALE_MAX_OUT_PIXELS / 1e6}MP）")
                        continue
                    }
                    scanAndErase(d, cropScale(bmp, reg, f, smooth), f, reg[0], reg[1], out,
                        "@${f.toInt()}x${if (smooth) "s" else "n"}")
                    if (missing.all { bx -> out.any { covers(it.px, bx) } }) break
                }
                out
            }
        }
    }

    private fun localizer(): Localizer? {
        localizer?.let { return it }
        val st = settings?.detectorSetting
        if (st == null) {
            Log.w(TAG, "ZEBRA settings.detectorSetting 为 null，LOCALIZE 策略跳过")
            return null
        }
        val l = Localizer.getLocalizer(st, exec).get(timeoutSec, TimeUnit.SECONDS)
        localizer = l
        return l
    }

    private fun fromResult(r: BarcodeDecoder.Result?, from: String): DetectedBarcode? {
        if (r == null) return null
        val v = r.value ?: return null
        if (v.isEmpty()) return null
        val box = r.corners ?: r.bboxData ?: return null
        val x0 = box.xmin
        val y0 = box.ymin
        val x1 = box.xmax
        val y1 = box.ymax
        if (x1 - x0 < 1f || y1 - y0 < 1f) return null
        return DetectedBarcode(
            text = v,
            format = friendly(r.symbology, r.label),
            isTwoD = isTwoD(r.symbology, r.label),
            px = listOf(x0, y0, x1, y1),
            quad = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1),
            source = "$name/$from",
        )
    }

    // ---------------- 合并去重 ----------------

    private fun merge(out: ArrayList<DetectedBarcode>, b: DetectedBarcode) {
        if (b.text.isEmpty()) return
        val i = out.indexOfFirst { it.text == b.text && it.format == b.format && iou(it.px, b.px) > 0.3f }
        if (i < 0) {
            out += b
            return
        }
        if (area(b.px) > area(out[i].px)) out[i] = b
    }

    private fun area(p: List<Float>) = maxOf(0f, p[2] - p[0]) * maxOf(0f, p[3] - p[1])

    private fun iou(a: List<Float>, b: List<Float>): Float {
        val ix = maxOf(0f, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val iy = maxOf(0f, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        val inter = ix * iy
        val uni = area(a) + area(b) - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    // ---------------- 码制 ----------------

    /**
     * 码制名对齐 zxing（"Code 128" / "QR Code"…），因为 ZPL 生成和 PC 端参考实现
     * 都按这套名字选 ^BC/^B3/^BQ，两端不能各叫各的。
     */
    private fun friendly(symbology: Int, label: String?): String {
        val n = symbologyName(symbology) ?: (label ?: "").uppercase()
        return when {
            n.contains("CODE128") || n.contains("CODE_128") -> "Code 128"
            n.contains("CODE39") || n.contains("CODE_39") -> "Code 39"
            n.contains("CODE93") || n.contains("CODE_93") -> "Code 93"
            n.contains("QRCODE") || n.contains("QR_CODE") || n.contains("QR ") -> "QR Code"
            n.contains("MICROQR") || n.contains("MICRO_QR") -> "Micro QR Code"
            n.contains("DATAMATRIX") || n.contains("DATA_MATRIX") -> "Data Matrix"
            n.contains("GS1_DATAMATRIX") -> "Data Matrix"
            n.contains("PDF417") || n.contains("PDF_417") -> "PDF417"
            n.contains("AZTEC") -> "Aztec"
            n.contains("EAN_13") || n.contains("EAN13") -> "EAN-13"
            n.contains("EAN_8") || n.contains("EAN8") -> "EAN-8"
            n.contains("UPC_A") || n.contains("UPCA") -> "UPC-A"
            n.contains("UPC_E") || n.contains("UPCE") -> "UPC-E"
            n.contains("I2OF5") || n.contains("ITF") || n.contains("D2OF5") -> "ITF"
            n.contains("CODABAR") -> "Codabar"
            n.contains("MAXICODE") || n.contains("MAXI_CODE") -> "MaxiCode"
            n.contains("MSI") -> "MSI"
            else -> n.ifEmpty { "unknown" }
        }
    }

    private fun isTwoD(symbology: Int, label: String?): Boolean {
        val n = symbologyName(symbology) ?: (label ?: "").uppercase()
        return listOf("QRCODE", "QR_CODE", "MICROQR", "MICRO_QR", "DATAMATRIX", "DATA_MATRIX",
            "PDF417", "PDF_417", "AZTEC", "MAXICODE", "MAXI_CODE", "GRID_MATRIX", "DOTCODE", "HANXIN")
            .any { n.contains(it) }
    }

    private fun symbologyName(v: Int): String? = try {
        BarcodeDecoder.Settings.SymbologyType.getByValue(v)?.name
    } catch (t: Throwable) {
        null
    }

    // ---------------- 诊断矩阵 ----------------

    /** 一次实验的结果，probe() 用 */
    data class Probe(val name: String, val outcome: String, val detail: String)

    /**
     * 为什么需要这个：DL 的 localizer 会把一个 Code128 拆成两个相互重叠的半框
     * （实测样例 1：真框 25..277，localizer 给 24..153 和 94..230），半框单独喂给
     * decodeMulti 解不出来。这个矩阵把"换输入 vs 换框"两个变量分开跑，
     * 一次上机就能看出到底哪条路能把一维码拿出来。
     */
    fun probe(bmp: Bitmap, label: String = ""): List<Probe> {
        // 就地打印：矩阵一轮要跑好几分钟，攒到函数返回再打会看不出卡在哪一步
        val out = object : ArrayList<Probe>() {
            override fun add(element: Probe): Boolean {
                Log.i(TAG, "ZPROBE >>> $label ${element.name} | ${element.outcome} | ${element.detail}")
                return super.add(element)
            }
        }
        if (!ensureInit()) return listOf(Probe("init", "FAILED", lastError ?: ""))

        val boxes = try {
            localize(bmp)
        } catch (t: Throwable) {
            out += Probe("localize", "FAILED", "${t.javaClass.simpleName}: ${rootMessage(t)}")
            return out
        }
        out += Probe("localize", if (boxes.isEmpty()) "NONE" else "SUCCESS", boxesDesc(boxes))

        val merged = mergeBoxes(boxes)
        out += Probe("merge", if (merged.size == boxes.size) "SAME" else "MERGED",
            "$boxes -> ${boxesDesc(merged)}")

        val padded = boxes.map { pad(it, 0.15f, bmp.width, bmp.height) }.toTypedArray()
        val mergedPadded = merged.map { pad(it, 0.15f, bmp.width, bmp.height) }.toTypedArray()

        val x2 = Bitmap.createScaledBitmap(bmp, bmp.width * 2, bmp.height * 2, true)
        val boxes2 = try { localize(x2) } catch (t: Throwable) { emptyArray<BBox>() }
        val merged2 = mergeBoxes(boxes2)
        out += Probe("localize2x", if (boxes2.isEmpty()) "NONE" else "SUCCESS", boxesDesc(boxes2))

        fun attempt(name: String, b: Bitmap, bx: Array<BBox>) {
            out += try {
                val rs = decodeBoxes(b, bx)
                Probe(name, if (rs.isEmpty()) "NONE" else "SUCCESS", rs)
            } catch (t: Throwable) {
                Probe(name, "FAILED", "${t.javaClass.simpleName}: ${rootMessage(t)}")
            }
        }

        if (boxes.isNotEmpty()) {
            attempt("RAW", bmp, boxes)
            attempt("PAD15", bmp, padded)
        }
        if (merged.isNotEmpty()) {
            attempt("MERGED", bmp, merged)
            attempt("MERGED_PAD15", bmp, mergedPadded)
        }
        if (boxes2.isNotEmpty()) {
            attempt("X2_RAW", x2, boxes2)
            attempt("X2_MERGED_PAD15", x2,
                merged2.map { pad(it, 0.15f, x2.width, x2.height) }.toTypedArray())
        }
        // 换设置：关掉 localizer，让原生层自己在整幅图上找
        val noLoc = try { decoderFor(enableLocalization = false) } catch (t: Throwable) {
            out += Probe("NOLOC_init", "FAILED", "${t.javaClass.simpleName}: ${rootMessage(t)}")
            null
        }
        if (noLoc != null) {
            listOf("NOLOC_1x" to bmp, "NOLOC_2x" to x2).forEach { (nm, b) ->
                out += try {
                    val r = fromResult(noLoc.decode(b, exec).get(timeoutSec, TimeUnit.SECONDS), nm)
                    Probe(nm, if (r == null) "NONE" else "SUCCESS", r?.let { describe(listOf(it)) } ?: "")
                } catch (t: Throwable) {
                    Probe(nm, "FAILED", "${t.javaClass.simpleName}: ${rootMessage(t)}")
                }
            }
        }

        // 设置矩阵：是不是 symbology / AI 开关把一维码关掉了。
        // 每个变体临时建一个 decoder、测完就 dispose —— 模型实例很重，7 个一起挂着会 OOM。
        for (ai in listOf(true, false)) {
            for (sym in listOf(0, 1, 2)) {
                // 这里不跳过任何格子：ai=true + sym=1（显式开常用码制）是官方 demo 的默认组合，
                // 之前误以为"就等于主链路"而被 continue 掉，恰好漏掉了唯一没验证过的那格。

                val nm = "CFG_ai${if (ai) 1 else 0}_sym$sym"
                val d = try {
                    BarcodeDecoder.getBarcodeDecoder(buildSettings(true, ai, sym), exec)
                        .get(timeoutSec, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    out += Probe(nm, "FAILED", "建实例失败 ${t.javaClass.simpleName}: ${rootMessage(t)}")
                    continue
                }
                try {
                    out += try {
                        val r = fromResult(d.decode(bmp, exec).get(timeoutSec, TimeUnit.SECONDS), nm)
                        Probe(nm, if (r == null) "NONE" else "SUCCESS", r?.let { describe(listOf(it)) } ?: "")
                    } catch (t: Throwable) {
                        Probe(nm, "FAILED", "${t.javaClass.simpleName}: ${rootMessage(t)}")
                    }
                    if (merged.isNotEmpty()) {
                        out += try {
                            val rs = d.decode(bmp, merged, exec).get(timeoutSec, TimeUnit.SECONDS)
                                .mapNotNull { fromResult(it, nm) }
                            Probe("${nm}_box", if (rs.isEmpty()) "NONE" else "SUCCESS", describe(rs))
                        } catch (t: Throwable) {
                            Probe("${nm}_box", "FAILED", "${t.javaClass.simpleName}: ${rootMessage(t)}")
                        }
                    }
                } finally {
                    runCatching { d.dispose() }
                }
            }
        }

        // 把宽扁的框（一维码）单独裁出来放大再解：排除"框里混进别的东西/模块太细"
        for (bx in merged) {
            val bw = bx.xmax - bx.xmin
            val bh = bx.ymax - bx.ymin
            if (bh <= 0f || bw / bh < 1.5f) continue
            val nm = "CROP4X_${bx.xmin.toInt()}"
            out += try {
                val crop = cropScale(bmp, bx, 4f)
                val full = BBox().also {
                    it.xmin = 0f; it.ymin = 0f
                    it.xmax = crop.width.toFloat(); it.ymax = crop.height.toFloat()
                }
                val rs = decoderFor(true).decode(crop, arrayOf(full), exec).get(timeoutSec, TimeUnit.SECONDS)
                    .mapNotNull { fromResult(it, nm) }
                Probe(nm, if (rs.isEmpty()) "NONE" else "SUCCESS", "${crop.width}x${crop.height} ${describe(rs)}")
            } catch (t: Throwable) {
                Probe(nm, "FAILED", "${t.javaClass.simpleName}: ${rootMessage(t)}")
            }
        }
        // 真机路径预演：把候选框裁出来当"整幅图"直接 process()，让条码填满模型输入。
        // 文档里 BarcodeDecoder 的正规用法就是"先从整幅图或 bbox 解码"；真机 12MP 照片上
        // 条码只占画面一小块，不裁的话模型输入里每个模块没剩几个像素。
        for (bx in merged) {
            val nm = "CROPPROC_${bx.xmin.toInt()}"
            out += try {
                val crop = cropScale(bmp, bx, 2f)
                val rs = decoderFor(true).process(ImageData.fromBitmap(crop, 0), exec)
                    .get(timeoutSec, TimeUnit.SECONDS)
                    .mapNotNull { e -> e.value?.takeIf { it.isNotEmpty() } }
                Probe(nm, if (rs.isEmpty()) "NONE" else "SUCCESS", "${crop.width}x${crop.height} $rs")
            } catch (t: Throwable) {
                Probe(nm, "FAILED", "${t.javaClass.simpleName}: ${rootMessage(t)}")
            }
        }
        return out
    }

    /** 按框裁出来再放大 f 倍（先裁后放大，别整图放大再裁，免得白烧一遍像素） */
    private fun cropScale(bmp: Bitmap, b: BBox, f: Float): Bitmap {
        val x0 = b.xmin.toInt().coerceIn(0, bmp.width - 1)
        val y0 = b.ymin.toInt().coerceIn(0, bmp.height - 1)
        val w = (b.xmax - b.xmin).toInt().coerceIn(1, bmp.width - x0)
        val h = (b.ymax - b.ymin).toInt().coerceIn(1, bmp.height - y0)
        val c = Bitmap.createBitmap(bmp, x0, y0, w, h)
        return Bitmap.createScaledBitmap(c, (w * f).toInt(), (h * f).toInt(), true)
    }

    /**
     * 在 [src] 上反复 decode + 把解出的框涂白，直到解不出为止；坐标按 1/f 缩回原图。
     * [offsetX]/[offsetY] 是 [src] 在原图里的左上角（裁出来放大时用）。
     */
    private fun scanAndErase(
        d: BarcodeDecoder, src: Bitmap, f: Float, offsetX: Float, offsetY: Float,
        out: MutableList<DetectedBarcode>, tagSuffix: String,
    ) {
        val work = src.copy(Bitmap.Config.ARGB_8888, true) ?: return
        val canvas = Canvas(work)
        val erase = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        for (i in 0 until MAX_SYMBOLS) {
            // 每轮 decode 都是一次完整推理（真机 2~4s），没有预算闸就会一路烧下去
            if (expired()) {
                Log.i(TAG, "ZEBRA scanAndErase: 预算用尽，已取出 ${out.size} 个符号")
                break
            }
            val r = fromResult(d.decode(work, exec).get(timeoutSec, TimeUnit.SECONDS),
                "DECODE_ALL$tagSuffix") ?: break
            out += r.copy(
                px = listOf(offsetX + r.px[0] / f, offsetY + r.px[1] / f,
                            offsetX + r.px[2] / f, offsetY + r.px[3] / f),
                quad = r.quad.map { (offsetX + it.first / f) to (offsetY + it.second / f) },
            )
            // 涂白要用 work 自己的尺度，所以这里用未缩放的 r.px
            canvas.drawRect(
                (r.px[0] - 2f).coerceAtLeast(0f),
                (r.px[1] - 2f).coerceAtLeast(0f),
                (r.px[2] + 2f).coerceAtMost(work.width.toFloat()),
                (r.px[3] + 2f).coerceAtMost(work.height.toFloat()), erase)
        }
    }

    /** 已解出的框 [px] 是否盖住了检测框 [bx]（用于判断"检测到但没解出"） */
    private fun covers(px: List<Float>, bx: BBox): Boolean {
        val cx = (bx.xmin + bx.xmax) / 2f
        val cy = (bx.ymin + bx.ymax) / 2f
        return cx in px[0]..px[2] && cy in px[1]..px[3]
    }

    private fun unionOf(boxes: List<BBox>): List<Float> = listOf(
        boxes.minOf { it.xmin }, boxes.minOf { it.ymin },
        boxes.maxOf { it.xmax }, boxes.maxOf { it.ymax })

    private fun expandToBox(r: List<Float>, f: Float, w: Int, h: Int): List<Float> {
        val dx = (r[2] - r[0]) * f
        val dy = (r[3] - r[1]) * f
        return listOf(
            (r[0] - dx).coerceAtLeast(0f), (r[1] - dy).coerceAtLeast(0f),
            (r[2] + dx).coerceAtMost(w.toFloat()), (r[3] + dy).coerceAtMost(h.toFloat()))
    }

    /**
     * 按 [x0,y0,x1,y1] 裁出来再放大 f 倍。
     * [smooth]=false 走最近邻：条码放大必须保硬边，双线性会把黑条/白条之间抹出灰带，
     * 2.5px/模块那种细码本来边缘就带灰，再插值就彻底解不出了。
     */
    private fun cropScale(bmp: Bitmap, r: List<Float>, f: Float, smooth: Boolean = false): Bitmap {
        val x0 = r[0].toInt().coerceIn(0, bmp.width - 1)
        val y0 = r[1].toInt().coerceIn(0, bmp.height - 1)
        val w = (r[2] - r[0]).toInt().coerceIn(1, bmp.width - x0)
        val h = (r[3] - r[1]).toInt().coerceIn(1, bmp.height - y0)
        return Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bmp, x0, y0, w, h), (w * f).toInt(), (h * f).toInt(), smooth)
    }

    private fun localize(bmp: Bitmap): Array<BBox> =
        localizer()?.detect(bmp, exec)?.get(timeoutSec, TimeUnit.SECONDS) ?: emptyArray()

    private fun decodeBoxes(bmp: Bitmap, boxes: Array<BBox>): String {
        val d = decoder ?: return "(no decoder)"
        val rs = d.decode(bmp, boxes, exec).get(timeoutSec, TimeUnit.SECONDS)
            .mapNotNull { fromResult(it, "probe") }
        return describe(rs)
    }

    /** 把相互重叠/贴边的框并成一个：localizer 会把一个符号拆成几段，半段永远解不出来 */
    private fun mergeBoxes(boxes: Array<BBox>, grow: Float = 0.05f): Array<BBox> {
        val list = boxes.map { floatArrayOf(it.xmin, it.ymin, it.xmax, it.ymax) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (!overlaps(list[i], list[j], grow)) continue
                    list[i] = union(list[i], list[j])
                    list.removeAt(j)
                    changed = true
                    break@outer
                }
            }
        }
        return list.map { (x0, y0, x1, y1) ->
            BBox().also { it.xmin = x0; it.ymin = y0; it.xmax = x1; it.ymax = y1 }
        }.toTypedArray()
    }

    private fun overlaps(a: FloatArray, b: FloatArray, grow: Float): Boolean {
        val ax = (a[2] - a[0]) * grow
        val ay = (a[3] - a[1]) * grow
        val bx = (b[2] - b[0]) * grow
        val by = (b[3] - b[1]) * grow
        return a[0] - ax < b[2] + bx && b[0] - bx < a[2] + ax &&
            a[1] - ay < b[3] + by && b[1] - by < a[3] + ay
    }

    private fun union(a: FloatArray, b: FloatArray) = floatArrayOf(
        minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))

    private fun pad(b: BBox, f: Float, w: Int, h: Int): BBox {
        val dx = (b.xmax - b.xmin) * f
        val dy = (b.ymax - b.ymin) * f
        return BBox().also {
            it.xmin = (b.xmin - dx).coerceAtLeast(0f)
            it.ymin = (b.ymin - dy).coerceAtLeast(0f)
            it.xmax = (b.xmax + dx).coerceAtMost(w.toFloat())
            it.ymax = (b.ymax + dy).coerceAtMost(h.toFloat())
        }
    }

    private fun boxesDesc(b: Array<BBox>) = if (b.isEmpty()) "(空)" else b.joinToString(" ") {
        "(${it.xmin.toInt()},${it.ymin.toInt()})-(${it.xmax.toInt()},${it.ymax.toInt()})"
    }

    // ---------------- 杂项 ----------------

    /**
     * 只开标签上真会出现的码制，其余保持默认关：开着会让检测网络误报（照片背景纹理被判成码）。
     * 逐个调用而不用 listOf(...)：DecoderParams 是 package-private 类，Kotlin 推断不出
     * `List<DecoderParams>` 这种类型（报 "inferred as invisible type"）。
     */
    private fun enableCommonSymbologies(st: BarcodeDecoder.Settings) {
        val sym = st.Symbology ?: return
        sym.CODE128.enable(true)
        sym.CODE39.enable(true)
        sym.CODE93.enable(true)
        sym.I2OF5.enable(true)
        sym.D2OF5.enable(true)
        sym.CODABAR.enable(true)
        sym.MSI.enable(true)
        sym.QRCODE.enable(true)
        sym.MICROQR.enable(true)
        sym.DATAMATRIX.enable(true)
        sym.PDF417.enable(true)
        sym.AZTEC.enable(true)
        sym.MAXICODE.enable(true)
        sym.EAN13.enable(true)
        sym.EAN8.enable(true)
        sym.UPCA.enable(true)
        sym.UPCE0.enable(true)
    }

    private fun quadOf(r: Rect) = listOf(
        r.left.toFloat() to r.top.toFloat(),
        r.right.toFloat() to r.top.toFloat(),
        r.right.toFloat() to r.bottom.toFloat(),
        r.left.toFloat() to r.bottom.toFloat(),
    )

    private fun describe(list: List<DetectedBarcode>): String = list.joinToString(" | ") {
        "[${it.format} '${it.text}' (${it.px[0].toInt()},${it.px[1].toInt()})-(${it.px[2].toInt()},${it.px[3].toInt()}) via ${it.source}]"
    }

    private fun rootMessage(t: Throwable): String {
        var c: Throwable = t
        val seen = HashSet<Throwable>()
        while (c.cause != null && c.cause !== c && seen.add(c.cause!!)) c = c.cause!!
        return c.message ?: c.javaClass.simpleName
    }

    fun dispose() {
        try {
            localizer?.dispose()
            decoder?.dispose()
        } catch (t: Throwable) {
            Log.w(TAG, "dispose 失败", t)
        }
        localizer = null
        decoder = null
        ready = false
    }

    companion object {
        private const val TAG = "LABELREPRINT"

        /** BarcodeDecoder.Settings 的资源名 = AAR 里 assets/barcode-decoder/ 这个目录名 */
        const val RESOURCE = "barcode-decoder"

        /** 一张标签最多擦几轮。擦一轮出一个符号，8 对标签足够，也防死循环 */
        const val MAX_SYMBOLS = 8

        /** 单张照片给 DL 引擎的总预算（ms）。超了就不再试后面的策略/放大轮次。 */
        const val DECODE_BUDGET_MS = 25_000L

        /** 超过这个像素数（12MP 真机照片）就不用"裁出来放大重扫"这条兜底：算不动也没必要 */
        const val UPSCALE_MAX_SRC_PIXELS = 4_000_000.0

        /** 候选区域超过这个像素数就不放大重扫（真机上检测网络常把背景纹理当候选） */
        const val UPSCALE_MAX_REGION_PIXELS = 1_000_000.0

        /** 放大后的位图上限，防 OOM */
        const val UPSCALE_MAX_OUT_PIXELS = 6_000_000.0

        /**
         * 主链路是否开 AI 解码。**必须 false** —— 实测（tools/device_zebra_probe 矩阵）：
         *   AI 开 + 默认码制      -> 一维码解不出（只出二维码）
         *   AI 开 + 只留 CODE128  -> 能解
         *   AI 关 + 任意码制      -> 都能解
         * 也就是说 AI 解码那条路对这批印刷码是负收益。要重开它，先跑矩阵回归。
         */
        const val AI_BARCODE_DECODE = false
    }
}
