package com.tiaomabang.labelreprint.barcode

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tiaomabang.labelreprint.model.DetectedBarcode

/**
 * 混合引擎：两个引擎的短板正好互补，一起跑再按「同一物理符号」归并。
 *
 * 为什么要混合（都是 TC73 实测，见 tools/device_zebra_check.py）：
 *  - Zebra DL（barcode-decoder-5.0.4）：二维码稳、且给**真包围盒/四角**；一维码在照片里
 *    低于 ~4px/模块就完全解不出（检测到了、value 为空）。
 *  - zxing-cpp 3.1.1：一维码靠 `tryHarder=true` 解得出，但坐标是「扫描线」而不是符号框
 *    （条高只有 2 点，必须自己从像素量 —— 见 ImageMeasure.barRows）。二维码它也能解，
 *    只是对糊图的鲁棒性不如 DL。
 *
 * 归并规则：内容（归一化后的字母数字）+ IoU > 0.3 视为同一符号，留大框；
 * 内容不同或位置不重叠的一律都留（一张标签上两个码是正常的，不能因为"内容像"就删）。
 *
 * 非 Zebra 设备上 [ZebraBarcodeEngine] 初始化失败（AIVisionSDK.getInstance 抛
 * UnsupportedOperationException），此时静默退化成 zxing-only —— 通用机型也能用。
 */
class HybridBarcodeEngine(private val ctx: Context) : BarcodeEngine {

    override val name = "hybrid"

    private val zxing = ZxingCppEngine()
    private val zebra = ZebraBarcodeEngine(ctx)

    /** 最近一次 decode 的各引擎明细，给日志/诊断面板看「这个符号是谁解出来的」 */
    val notes = ArrayList<String>()

    /** Zebra DL 是否可用（模型缺失/非 Zebra 机 = false） */
    var zebraAvailable = false
        private set

    /** DL 不可用的原因（界面上要给出"为什么只剩 zxing"） */
    val zebraError: String? get() = zebra.lastError

    var lastMs: Long = 0
        private set

    override fun decode(bitmap: Bitmap): List<DetectedBarcode> {
        val t0 = System.currentTimeMillis()
        notes.clear()

        // Zebra DL 先跑：它的 quad 是真四角，二维码优先用它
        val zb = runCatching {
            zebraAvailable = zebra.ensureInit()
            if (zebraAvailable) zebra.decode(bitmap) else emptyList()
        }.getOrElse { t ->
            zebraAvailable = false
            notes += "zebra 失败：${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "zebra 引擎失败，退化为 zxing-only", t)
            emptyList()
        }

        // zxing 必跑：一维码只有它（在低分辨率照片上）能解
        val zx = runCatching { zxing.decode(bitmap) }.getOrElse { t ->
            notes += "zxing 失败：${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "zxing 引擎失败", t)
            emptyList()
        }

        val out = ArrayList<DetectedBarcode>()
        // zxing 先入：两个引擎都解出同一个符号时，留 zxing 的框 —— PC 端参考实现用的就是 zxing，
        // 只有端侧与它逐字节可比，tools/device_zpl_check.py 这条回归网才有意义。DL 的价值在于
        // 「zxing 没解出来的符号」（糊照片上的二维码、低分辨率下的一维码），那些框由它补上。
        for (b in zx) merge(out, b)
        for (b in zb) merge(out, b)

        lastMs = System.currentTimeMillis() - t0
        notes += "zebra(${if (zebraAvailable) "on" else "off"})=${zb.size} zxing=${zx.size} 合并=${out.size}"
        if (!zebraAvailable) notes += "DL 不可用：${zebra.lastError ?: "初始化失败"}"
        Log.i(TAG, "HYBRID >>> ${notes.joinToString("；")}　${lastMs}ms")
        return out
    }

    /**
     * 同一物理符号（内容一致 + 框重叠）只留一个，新框大 10% 以上才顶替。
     * **不能只比内容**：一张标签上同一个内容也可能同时有二维码和一维码，只按内容去重会把其中一个吃掉。
     */
    private fun merge(out: ArrayList<DetectedBarcode>, b: DetectedBarcode) {
        if (b.text.isEmpty()) return
        val i = out.indexOfFirst { norm(it.text) == norm(b.text) && iou(it.px, b.px) > 0.3f }
        if (i < 0) {
            out += b
            return
        }
        if (area(b.px) > area(out[i].px) * 1.1f) out[i] = b
    }

    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    private fun area(p: List<Float>) = maxOf(0f, p[2] - p[0]) * maxOf(0f, p[3] - p[1])

    private fun iou(a: List<Float>, b: List<Float>): Float {
        val ix = maxOf(0f, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val iy = maxOf(0f, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        val inter = ix * iy
        val uni = area(a) + area(b) - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    fun dispose() {
        runCatching { zebra.dispose() }
    }

    companion object {
        private const val TAG = "LABELREPRINT"
    }
}
