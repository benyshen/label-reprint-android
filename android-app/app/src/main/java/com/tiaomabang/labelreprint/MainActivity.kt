package com.tiaomabang.labelreprint

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.tiaomabang.labelreprint.label.LabelFrame
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.tiaomabang.labelreprint.ui.theme.ZebraTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tiaomabang.labelreprint.barcode.BarcodeEngine
import com.tiaomabang.labelreprint.barcode.HybridBarcodeEngine
import com.tiaomabang.labelreprint.barcode.ZebraBarcodeEngine
import com.tiaomabang.labelreprint.barcode.ZxingCppEngine
import com.tiaomabang.labelreprint.model.ReprintPlan
import com.tiaomabang.labelreprint.ocr.InferenceBackend
import com.tiaomabang.labelreprint.ocr.PaddleOcrEngine
import com.tiaomabang.labelreprint.pipeline.LabelPipeline
import com.tiaomabang.labelreprint.print.BleDevice
import com.tiaomabang.labelreprint.print.BleScan
import com.tiaomabang.labelreprint.print.GatewayResult
import com.tiaomabang.labelreprint.print.PairedDevice
import com.tiaomabang.labelreprint.print.PrinterPrefs
import com.tiaomabang.labelreprint.print.Transport
import com.tiaomabang.labelreprint.print.ZebraGateway
import com.tiaomabang.labelreprint.print.listPairedDevices
import com.tiaomabang.labelreprint.ui.CameraPane
import com.tiaomabang.labelreprint.ui.ElementEdit
import com.tiaomabang.labelreprint.ui.LinkPhase
import com.tiaomabang.labelreprint.ui.PrinterScreen
import com.tiaomabang.labelreprint.ui.PrinterUi
import com.tiaomabang.labelreprint.ui.ResultPane
import com.tiaomabang.labelreprint.zpl.ZplGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val TAG = "LABELREPRINT"

/** 进流水线的照片像素上限（8MP）。12MP 原图对条码/文字识别没有增量，却要几倍的拷贝内存。 */
private const val MAX_PIPELINE_PIXELS = 8_000_000L

/**
 * 标签复刻：拍照 → 条码/文字识别 → 元素表 → ZPL。
 *
 * 分工：相机/权限/文件/剪贴板这类平台事在 Activity；界面在 ui/LabelScreen.kt（Compose 只读状态
 * 加回调，不碰几何）；识别与几何在 barcode/ocr/pipeline/zpl（与 PC 端参考实现逐条对齐）。
 * 调试注入口（`--es all 1` / `asset:` / `file:` / `diag:` / `ocr` / `zprobe`）保留，
 * 供 tools/device_zpl_check.py 这类脚本做端到端回归。
 */
class MainActivity : ComponentActivity() {

    /**
     * 版本号唯一真源 = `build.gradle.kts` 的 `versionName`；界面只从 PackageManager 读，
     * 绝不写死字面量（写死的那份迟早和构建产物不一致）。
     */
    private val appVersion: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }.getOrDefault("?")
    }

    private val zxing by lazy { ZxingCppEngine() }

    /**
     * Zebra DL 引擎。lazy：非 Zebra 机上构造/初始化会抛，没点之前别碰它。
     * 模型首次解压几十秒，所以初始化挂在首次 decode 里（ensureInit），不在这里。
     */
    private val zebra by lazy { ZebraBarcodeEngine(applicationContext) }

    /** 主力引擎：两个引擎互补（二维码走 DL、一维码走 zxing），见 HybridBarcodeEngine 的说明 */
    private val hybrid by lazy { HybridBarcodeEngine(applicationContext) }

    /** 文字 OCR：PP-OCRv4 on-device。模型加载重，lazy + 首次用到才建。 */
    private val ocr by lazy { PaddleOcrEngine(applicationContext, InferenceBackend.CPU) }

    private var engineKind by mutableStateOf("hybrid")
    private val engine: BarcodeEngine get() = when (engineKind) {
        "zebra" -> zebra
        "zxing" -> zxing
        else -> hybrid
    }

    /** 注入的调试指令。用 mutableStateOf 承接，because setIntent() 不会触发 Compose 重组。 */
    private var injected by mutableStateOf<String?>(null)

    // ---- 界面状态 ----
    private var screen by mutableStateOf("camera")          // camera | result
    private var photo by mutableStateOf<Bitmap?>(null)

    /**
     * 最近一次识别的原始图 / 校正图。
     *
     * 界面要显示**校正图**：要素框的 px 就是在校正图上量的，显示原始照片会让所有框错位。
     * 重跑必须从**原始图**重来：拿校正图再校正一次会做第二次透视变换（标签会被越裁越小）。
     */
    private var lastRaw: Bitmap? = null
    private var lastWorking: Bitmap? = null
    private var plan by mutableStateOf<ReprintPlan?>(null)
    private var status by mutableStateOf("对准标签拍照；一维码在照片里至少要有 120px 宽")
    private var busy by mutableStateOf(false)
    private var useOcr by mutableStateOf(true)
    private var template by mutableStateOf(ZplGeometry.DEFAULT_TEMPLATE)

    /**
     * 用户是否在界面上手动点过模板。点过就完全以他为准（自动选不再覆盖），
     * 否则每次识别都按源图长宽比自动选 —— 拍 70×70 的标签却按 60×40 打，整张会纵向压扁。
     */
    private var templatePinned by mutableStateOf(false)
    private var cameraReady by mutableStateOf(false)

    // ---- 打印（Zebra Link-OS：经典蓝牙 / 低功耗蓝牙 / 网口） ----
    private val printer = ZebraGateway(applicationContext)
    private val printerPrefs by lazy { PrinterPrefs(this) }

    /** 目标打印机："BT:<MAC>" / "BLE:<MAC>" / "TCP:<IP>"。冷启时取上次用的那台（可关）。 */
    private var printerSpec by mutableStateOf<String?>(null)
    private var printerState by mutableStateOf("未连接打印机（点「打印机」选一台）")

    /**
     * 打印机状态（界面唯一真源）。与 [printerState] 并存：后者只用来打日志/兼容注入路径，
     * 界面上的一切（灯色、型号、实时状态、"几秒前"）都从这份快照推。
     */
    private var printerUi by mutableStateOf(PrinterUi())
    /** 发送中（避免轮询在打印时抢同一条链路） */
    private var printing by mutableStateOf(false)
    private var paired by mutableStateOf<List<PairedDevice>>(emptyList())

    // ---- 打印机设置页（独立页面） ----
    private var printerPage by mutableStateOf(false)
    private var bleDevices by mutableStateOf<List<BleDevice>>(emptyList())
    private var bleScanning by mutableStateOf(false)
    private var bleNote by mutableStateOf("")
    private var autoConnect by mutableStateOf(true)

    /**
     * `--es print 1` 注入的自动打印标志。用 mutableStateOf 承接而不是直接读 intent：
     * setIntent() 不触发 Compose 重组，钩子会静默不生效。
     */
    private var printWanted by mutableStateOf(false)

    /** `--es savephoto 1`：把拍摄原图落到 files/last_shot.png（验证取景是否所见即所得） */
    private var savePhoto by mutableStateOf(false)
    private var printSeq by mutableStateOf(0)

    /**
     * Android 12+ 连接蓝牙要 BLUETOOTH_CONNECT，**并额外要 BLUETOOTH_SCAN**：
     * Zebra SDK 的 BluetoothConnection.open() 内部会调 cancelDiscovery()，那是 SCAN 权限。
     * 两个必须一起申请，只给 CONNECT 会在 open() 抛 SecurityException（实测踩过）。
     */
    private val btPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val missing = result.filterValues { !it }.keys
            if (missing.isEmpty()) {
                paired = listPairedDevices(this)
                Log.i(TAG, "PRINT >>> 蓝牙权限已授予，已配对 ${paired.size} 台：" +
                    paired.joinToString { "${it.name}@${it.mac}" })
                restorePrinterTarget()
            } else {
                printerState = "缺少蓝牙权限：${missing.joinToString()}"
                printerUi = printerUi.copy(
                    phase = LinkPhase.ERROR,
                    detail = "缺少蓝牙权限：${missing.joinToString()}",
                )
                Log.w(TAG, "PRINT >>> 蓝牙权限被拒：${missing.joinToString()}")
            }
        }

    // ---- 相机 ----
    private val previewView by lazy { PreviewView(this) }
    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status = if (granted) "相机就绪" else "没有相机权限：可以用「相册」选一张标签照片"
            if (granted) bindCamera()
        }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = true
        status = "读取照片…"
        Thread {
            val bmp = runCatching {
                contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            runOnUiThread {
                if (bmp == null) {
                    busy = false
                    status = "这张图读不出来"
                } else {
                    onPhoto(bmp, "相册")
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injected = extrasKey(intent)
        engineKind = engineKindOf(intent)
        readPrintExtras(intent)
        Log.i(TAG, "INTENT >>> keys=${intent?.extras?.keySet()} injected=$injected engine=$engineKind " +
            "printer=$printerSpec print=$printWanted")

        setContent {
            ZebraTheme {
                if (!hasCameraPermission()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("需要相机权限才能拍照识别；也可先用「相册」选照片")
                    }
                }
                App()
            }
        }

        if (hasCameraPermission()) bindCamera() else cameraPermission.launch(Manifest.permission.CAMERA)

        // 蓝牙：枚举已配对设备 + 真实打印都要这两个权限（Android 12+ 是运行时权限）
        val missing = missingBtPermissions()
        if (missing.isNotEmpty()) {
            btPermission.launch(missing.toTypedArray())
        } else {
            paired = listPairedDevices(this)
            Log.i(TAG, "PRINT >>> 已配对 ${paired.size} 台：" +
                paired.joinToString { "${it.name}@${it.mac}" })
            restorePrinterTarget()
        }
    }

    /**
     * 启动时恢复上次用的打印机。
     *
     * 只在这里读一次 prefs：BLE 打印机不在系统配对表里，不记就是"每次开机都要重扫一遍"。
     * 注入 `--es bt` 时以注入为准（脚本回归要用指定机器）。
     */
    private fun restorePrinterTarget() {
        autoConnect = printerPrefs.autoConnect
        val saved = printerPrefs.spec
        if (saved.isBlank()) return
        // 先把握手信息填上：即使不自动连接，界面也该显示"上次是 ZR668"
        printerUi = printerUi.copy(target = addrOf(saved), name = printerPrefs.name)
        if (!autoConnect || printerSpec != null) {
            Log.i(TAG, "PRINT >>> 恢复上次目标 $saved（不自动连接：auto=$autoConnect 注入=${printerSpec != null}）")
            return
        }
        printerSpec = saved
        Log.i(TAG, "PRINT >>> 自动连接上次的打印机 $saved")
    }

    /** 缺哪个报哪个：只返回确实没授的权限，已授的重复申请会静默返回 false 把人带偏 */
    private fun missingBtPermissions(): List<String> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return emptyList()
        return listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * `--es bt <MAC或IP>` + `--es print 1`：脚本回归用（先连打印机、再出纸，端到端可测）。
     * 分开读两个 extra，因为"只连不打"（验连接与状态）与"连了就打"都是需要的场景。
     */
    private fun readPrintExtras(i: Intent?) {
        val bt = i?.getStringExtra("bt")
        if (!bt.isNullOrBlank()) {
            printerSpec = normalizeSpec(bt)
            Log.i(TAG, "PRINT >>> 注入目标打印机 $printerSpec")
        }
        if (i?.hasExtra("print") == true) {
            printWanted = true
            Log.i(TAG, "PRINT >>> 注入自动打印标志")
        }
        if (i?.hasExtra("savephoto") == true) {
            savePhoto = true
            Log.i(TAG, "PHOTO >>> 注入保存原图标志")
        }
        // 裸 MAC 补 BT: 前缀，裸 IP 补 TCP:（ViewModel 那一侧不再猜，统一在这里归一）
        if (i?.getStringExtra("tcp") != null) printerSpec = "TCP:${i.getStringExtra("tcp")}"
    }

    private fun normalizeSpec(spec: String): String {
        val s = spec.trim()
        return when {
            s.startsWith("BT:", true) || s.startsWith("BLE:", true) || s.startsWith("TCP:", true) -> s
            s.contains(':') && s.length >= 17 -> "BT:$s"      // AA:BB:CC:DD:EE:FF
            else -> "TCP:$s"
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @Composable
    private fun App() {
        val p = plan
        val bmp = photo
        if (printerPage) {
            // 打印机设置是独立页面：结果页只看"能不能打"，选设备/扫描/校准都在这里
            PrinterScreen(
                ui = printerUi,
                paired = paired,
                bleDevices = bleDevices,
                bleScanning = bleScanning,
                bleNote = bleNote,
                autoConnect = autoConnect,
                busy = printing || busy,
                version = appVersion,
                onBack = { printerPage = false },
                onRefresh = ::refreshPrinterStatus,
                onReconnect = ::reconnectPrinter,
                onDisconnect = ::disconnectPrinter,
                onChooseClassic = { d -> choosePrinter("BT:${d.mac}", d.name) },
                onChooseBle = { d -> choosePrinter("BLE:${d.mac}", d.name) },
                onScanBle = ::scanBle,
                onOpenBtSettings = ::openBluetoothSettings,
                onAutoConnect = { v -> autoConnect = v; printerPrefs.autoConnect = v },
                onTestPrint = ::printTestPage,
                onCalibrate = ::calibratePrinter,
            )
        } else if (screen == "result" && p != null && bmp != null) {
            ResultPane(
                bitmap = bmp, plan = p, status = status, busy = busy, useOcr = useOcr,
                onEdit = ::applyEdit,
                onUseOcr = { v -> useOcr = v; reprocess() },
                onTemplate = { t ->
                    templatePinned = true          // 手动选过之后不再被自动选覆盖
                    template = t
                    plan = LabelPipeline.remap(p, p.elements, t)
                },
                onReshoot = ::reshoot,
                onReanalyze = { reprocess() },   // 同一张照片重跑整链，不碰相机
                version = appVersion,
                onCopyZpl = { copy(p.zpl) },
                onSaveZpl = { saveZpl(p) },
                onPrint = { printZpl(p.zpl) },
                printerUi = printerUi,
                onOpenPrinter = { printerPage = true },
            )
        } else {
            CameraPane(
                previewView = previewView,
                status = status,
                busy = busy,
                canShoot = cameraReady,
                template = template,
                // 拍照页选模板 = 先定标签形状（取景框跟着变），所以也要 pin，别被自动选覆盖
                onTemplate = { t -> templatePinned = true; template = t },
                onShoot = ::shoot,
                onPick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRunSelfTest = { injected = "ALL" },
            )
        }

        // 注入指令 -> 状态变 -> 重组 -> 跑流水线（调试钩子走的是同一条主链）
        LaunchedEffect(injected) {
            val key = injected ?: return@LaunchedEffect
            busy = true
            status = "识别中…"
            val (p2, msg) = withContext(Dispatchers.Default) { runInjected(key) }
            busy = false
            status = msg
            if (p2 != null) {
                photo = lastWorking ?: photo ?: assetsBitmap("samples/label_sample_1.png")
                plan = p2
                template = p2.template
                if (!key.startsWith("PROBE") && !key.startsWith("diag")) screen = "result"
            }
        }

        // 选定/注入打印机 -> 连接，并读一次状态（"连上但没纸"这类问题必须能看出来）
        LaunchedEffect(printerSpec) {
            val spec = printerSpec ?: return@LaunchedEffect
            connectPrinter(spec)
        }

        // 连着的时候每 5s 问一次状态：缺纸/开盖/暂停都是"连得上但打不了"，
        // 只在连接那一刻读一次的话，用户换了纸或打开盖子界面上永远显示"就绪"。
        // 打印中/识别中跳过（同一条链路不要并发写）。
        LaunchedEffect(printerUi.phase) {
            if (printerUi.phase != LinkPhase.CONNECTED) return@LaunchedEffect
            while (isActive) {
                delay(5_000)
                if (!printing && !busy) refreshPrinterStatus()
            }
        }

        // 自动打印（钩子）：等 plan 出来再打。键里必须带 plan —— 只键 printWanted 的话，
        // flag 在 onCreate 就置位、那时还没有 plan，effect 立即返回且再也不会重跑。
        LaunchedEffect(plan, printWanted) {
            if (!printWanted) return@LaunchedEffect
            val p = plan ?: return@LaunchedEffect
            printWanted = false
            printZpl(p.zpl)
        }
    }

    // ---------------- 打印 ----------------

    /**
     * 用户在某栏里点了设备。`spec` 已带前缀（BT:/BLE:），名字一起记住：
     * BLE 打印机不在系统配对表里，重启 App 后只能靠存下来的名字显示，不然界面上只有一串 MAC。
     */
    private fun choosePrinter(spec: String, name: String) {
        printerSpec = spec
        printerUi = printerUi.copy(
            phase = LinkPhase.CONNECTING,
            target = addrOf(spec),
            name = name,
            detail = "正在连接…",
        )
        Log.i(TAG, "PRINT >>> 选择目标 $spec（$name）")
    }

    private fun connectPrinter(spec: String) {
        printerState = "连接中…（$spec）"
        // 名字优先从配对表里补（注入 MAC 时界面只有地址，没有名字会显示成纯 MAC）
        val known = paired.firstOrNull { it.mac.equals(addrOf(spec), ignoreCase = true) }?.name
        printerUi = printerUi.copy(
            phase = LinkPhase.CONNECTING,
            target = addrOf(spec),
            name = printerUi.name.ifBlank { known ?: printerPrefs.name },
            detail = "正在建立连接…",
        )
        lifecycleScope.launch {
            when (val r = printer.connect(spec)) {
                is GatewayResult.Ok -> {
                    val st = printer.status()
                    printerState = "已连接 ${r.info} · $st"
                    printerUi = printerUi.copy(
                        phase = LinkPhase.CONNECTED, model = r.info, detail = st,
                        at = System.currentTimeMillis(),
                        transport = printer.transport(),
                    )
                    printerPrefs.remember(spec, printerUi.name)
                    Log.i(TAG, "PRINT >>> 已连接 ${r.info}（$spec，${printer.transport().label}）状态=$st")
                }
                is GatewayResult.Err -> {
                    printerState = "连接失败：${r.reason}"
                    printerUi = printerUi.copy(phase = LinkPhase.ERROR, detail = r.reason, at = 0L)
                    Log.w(TAG, "PRINT >>> 连接失败 $spec：${r.reason}")
                }
            }
        }
    }

    /** 断开：不删记住的目标，下次点「连接」还连它 */
    private fun disconnectPrinter() {
        lifecycleScope.launch {
            printer.disconnect()
            printerUi = printerUi.copy(
                phase = LinkPhase.IDLE, model = "", detail = "", at = 0L, transport = Transport.NONE,
            )
            printerState = "已断开"
            Log.i(TAG, "PRINT >>> 已断开")
        }
    }

    /**
     * 扫 BLE 打印机。扫描结果同时来自原生 BLE 扫描与 Zebra SDK 发现（见 [BleScan]），
     * 每扫到一台就追加到列表；结束后更新提示。
     */
    private fun scanBle() {
        if (bleScanning) return
        bleDevices = emptyList()
        bleNote = ""
        bleScanning = true
        val started = BleScan.scan(
            ctx = this,
            seconds = 8_000,
            onFound = { d ->
                bleDevices = bleDevices.filterNot { it.mac.equals(d.mac, true) } + d
                // Zebra 认出来的排前面：那是"基本肯定能打"的
                bleDevices = bleDevices.sortedWith(
                    compareByDescending<BleDevice> { it.zebra }.thenByDescending { it.rssi ?: -999 }
                )
            },
            onNote = { bleNote = it; Log.w(TAG, "PRINT >>> BLE 扫描：$it") },
            onDone = {
                bleScanning = false
                if (bleDevices.isEmpty()) {
                    bleNote = "没扫到 BLE 打印机：确认打印机开机、蓝牙已开、离近一点（BLE 距离比经典蓝牙敏感）"
                } else {
                    bleNote = "扫到 ${bleDevices.size} 台（标「Zebra」的是 SDK 确认过的打印机）"
                }
                Log.i(TAG, "PRINT >>> BLE 扫描结束，${bleDevices.size} 台")
            },
        )
        if (!started) bleScanning = false
    }

    private fun openBluetoothSettings() {
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            val ok = runCatching { startActivity(i) }.isSuccess
            if (ok) return
        }
        status = "打不开系统蓝牙设置，请手动到「设置 → 蓝牙」配对打印机"
    }

    /** 测试页：一张最小标签，验证"链通 + 能出纸"。换机器、换纸、换现场都先打这张。 */
    private fun printTestPage() {
        val zpl = buildString {
            append("^XA")
            append("^PW480^LL320")
            append("^FO20,20^A0N,34,34^FD测试页 TEST PAGE^FS")
            append("^FO20,70^A0N,22,22^FD${appVersion} / ${printer.transport().short.ifBlank { "无链路" }}^FS")
            append("^FO20,110^A0N,22,22^FD${printerUi.target}^FS")
            append("^FO20,150^BY2^BCN,90,Y,N,N^FD${printerUi.target.replace(":", "")}^FS")
            append("^XZ")
        }
        Log.i(TAG, "PRINT >>> 打印测试页（${printer.transport().label}）")
        printZpl(zpl)
    }

    /** 换纸后校准（`~jc`）：标签间隙重新测一遍，否则每张都会偏 */
    private fun calibratePrinter() {
        lifecycleScope.launch {
            val r = printer.calibrate()
            printerUi = printerUi.copy(
                detail = if (r is GatewayResult.Ok) "已发送校准指令" else (r as GatewayResult.Err).reason,
                at = System.currentTimeMillis(),
            )
            Log.i(TAG, "PRINT >>> 校准：$r")
        }
    }

    /** 主动问一次打印机当前状态（换纸后点"刷新状态"就走这里，不重连） */
    private fun refreshPrinterStatus() {
        if (printerUi.phase != LinkPhase.CONNECTED || printing || busy) return
        lifecycleScope.launch {
            val st = printer.status()
            // 掉线时 status() 返回的是人话原因；识别成掉线并把灯改成红，而不是继续显示"就绪"
            val alive = printer.isConnected() && !st.contains("未连接")
            printerUi = printerUi.copy(
                phase = if (alive) LinkPhase.CONNECTED else LinkPhase.ERROR,
                detail = st,
                at = System.currentTimeMillis(),
                transport = printer.transport(),
            )
            Log.i(TAG, "PRINT >>> 状态刷新：$st（alive=$alive）")
        }
    }

    private fun reconnectPrinter() {
        val spec = printerSpec
        if (spec == null) {
            printerUi = printerUi.copy(phase = LinkPhase.ERROR, detail = "还没选打印机：点上方设备名选一台")
            return
        }
        connectPrinter(spec)
    }

    /** "BT:AA:BB:.." / "BLE:AA:BB:.." -> "AA:BB:.."；"TCP:1.2.3.4:9100" 原样显示 */
    private fun addrOf(spec: String): String =
        spec.removePrefix("BT:").removePrefix("bt:").removePrefix("BLE:").removePrefix("ble:")
            .removePrefix("TCP:").removePrefix("tcp:")

    private fun printZpl(zpl: String) {
        val spec = printerSpec
        if (spec == null && !printer.isConnected()) {
            printerState = "还没选打印机：点上方设备名选一台"
            printerUi = printerUi.copy(
                phase = LinkPhase.ERROR, target = "", detail = "还没选打印机：点设备名选一台",
            )
            Log.w(TAG, "PRINT >>> 没有目标打印机，未发送")
            return
        }
        lifecycleScope.launch {
            printing = true
            try {
                if (!printer.isConnected()) {
                    when (val r = printer.connect(spec!!)) {
                        is GatewayResult.Ok -> {
                            printerState = "已连接 ${r.info}"
                            printerUi = printerUi.copy(
                                phase = LinkPhase.CONNECTED, model = r.info,
                                detail = "已重新连接", at = System.currentTimeMillis(),
                            )
                            Log.i(TAG, "PRINT >>> 已连接 ${r.info}")
                        }
                        is GatewayResult.Err -> {
                            printerState = "连接失败：${r.reason}"
                            printerUi = printerUi.copy(
                                phase = LinkPhase.ERROR, detail = r.reason, at = 0L,
                            )
                            Log.w(TAG, "PRINT >>> 连接失败：${r.reason}")
                            return@launch
                        }
                    }
                }
                // 发送结果也必须进日志：只记 ZPL 的话，"日志成功、纸上一片空白" 完全看不出来
                Log.i(TAG, "PRINT >>> 发往 $spec，ZPL ${zpl.length} 字节")
                when (val r = printer.printZpl(zpl)) {
                    is GatewayResult.Ok -> {
                        printerState = "已发送（${r.info}）"
                        printerUi = printerUi.copy(
                            phase = LinkPhase.CONNECTED, detail = "已发送 · ${r.info}",
                            at = System.currentTimeMillis(),
                        )
                        Log.i(TAG, "PRINT >>> 成功 ${r.info}｜$spec")
                    }
                    is GatewayResult.Err -> {
                        printerState = "打印失败：${r.reason}"
                        // 打印机缺纸/开盖/暂停是"链路通但打不了"，灯要变红而不是继续绿的
                        printerUi = printerUi.copy(phase = LinkPhase.ERROR, detail = r.reason, at = 0L)
                        Log.w(TAG, "PRINT >>> 失败 ${r.reason}｜$spec")
                    }
                }
            } finally {
                printing = false
            }
        }
    }

    // ---------------- 相机 ----------------
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val prov = future.get()
                provider = prov
                // 预览必须与拍照用**同一个画幅**（都是 4:3）：否则 PreviewView 会按自己的比例
                // 裁掉两边去填满容器，而 ImageCapture 拿到的是完整 4:3 —— 用户看到的就是
                // "取景框里的内容跟拍下来的照片不一样"。配合 scaleType=FIT_CENTER（不裁切）
                // 与容器 aspectRatio=3:4，框内所见 == 拍到的画面。
                // 另外拍照侧要 4:3 是为了像素：一维码至少 4px/模块，标签在照片里得够宽。
                val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val previewSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(previewSelector)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(selector)
                    .build()
                imageCapture = capture
                prov.unbindAll()
                prov.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                cameraReady = true
                status = "相机就绪"
                // 把两侧实际选中的分辨率打进日志：预览与拍照画幅不一致时（取景框 ≠ 照片）
                // 只有这两行能看出来，别靠肉眼比对
                val pr = preview.resolutionInfo?.resolution
                val cr = capture.resolutionInfo?.resolution
                Log.i(TAG, "CAMERA >>> bound｜preview=${pr?.width}x${pr?.height}" +
                    " capture=${cr?.width}x${cr?.height} scaleType=${previewView.scaleType}")
                if (pr != null && kotlin.math.abs(pr.width.toFloat() / pr.height - 4f / 3f) > 0.01f) {
                    Log.w(TAG, "CAMERA >>> 预览画幅 ${pr.width}x${pr.height} 不是 4:3（aspect fallback），" +
                        "取景框与照片会有偏差")
                }
            } catch (t: Throwable) {
                cameraReady = false
                status = "相机不可用：${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "CAMERA bind failed", t)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shoot() {
        val cap = imageCapture
        if (cap == null) {
            status = "相机还没就绪"
            return
        }
        busy = true
        status = "拍摄中…"
        cap.takePicture(ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = runCatching { jpegToBitmap(image) }.getOrElse { t ->
                        Log.e(TAG, "照片解码失败", t)
                        null
                    }
                    image.close()
                    if (bmp == null) {
                        busy = false
                        status = "照片解码失败"
                    } else {
                        onPhoto(bmp, "拍照")
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    busy = false
                    status = "拍摄失败：${exc.message}"
                    Log.e(TAG, "takePicture failed", exc)
                }
            })
    }

    /** ImageCapture 默认输出 JPEG，整张套在 plane[0] 里；再按 rotationDegrees 摆正 */
    private fun jpegToBitmap(image: ImageProxy): Bitmap {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("JPEG 解不出位图（${bytes.size} 字节）")
        val deg = image.imageInfo.rotationDegrees
        Log.i(TAG, "PHOTO >>> ${raw.width}x${raw.height} rot=$deg（${bytes.size / 1024}KB）")
        if (deg == 0) return raw
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun onPhoto(bmp: Bitmap, from: String) {
        // 每次**拍照**都在外部目录留一份带时间戳的备份，事后能核对"当时拍的到底是什么样"：
        //   /sdcard/Android/data/com.tiaomabang.labelreprint/files/shots/20261015_223344.jpg
        // 选外部目录的理由：不需要任何权限、卸载即清、手机文件管理器和 USB 都能直接翻。
        // 用 JPEG(q92) 而不是 PNG：同样 12MP，PNG 约 9MB、JPEG 约 2~3MB；验收看的是内容和几何，
        // 不是像素级无损。相册选图不备份（原文件本来就在磁盘上）。
        if (from == "拍照") {
            val src = bmp
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            Thread {
                runCatching {
                    val dir = File(getExternalFilesDir(null), "shots").apply { mkdirs() }
                    val f = File(dir, "$stamp.jpg")
                    java.io.FileOutputStream(f).use { src.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    Log.i(TAG, "PHOTO >>> backup ${f.absolutePath} " +
                        "${src.width}x${src.height} ${f.length() / 1024}KB")
                }.onFailure { Log.e(TAG, "PHOTO backup failed", it) }
            }.start()
        }
        // 调试口：把**原图**（缩放前）落到应用私有目录。验证"取景框所见 == 拍到的照片"时
        // 这是唯一可信的对照物 —— 拿屏幕截图去目测会被预览自身的缩放骗到。
        // 取回：adb shell "run-as com.tiaomabang.labelreprint cat files/last_shot.png" > shot.png
        if (savePhoto) {
            runCatching {
                val f = File(filesDir, "last_shot.png")
                java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "PHOTO >>> saved ${f.absolutePath} ${bmp.width}x${bmp.height}")
            }.onFailure { Log.e(TAG, "PHOTO save failed", it) }
        }
        // 12MP 原图（4080x3060 = 50MB ARGB）在 Java/CV 两侧都要各拷几份，且没人用得上这么多像素：
        // 条码只要 4px/模块。上限 8MP，既保住条码细节又省内存与 OpenCV 时间。
        val px = bmp.width.toLong() * bmp.height
        val use = if (px > MAX_PIPELINE_PIXELS) {
            val f = kotlin.math.sqrt(MAX_PIPELINE_PIXELS.toDouble() / px)
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
            Log.i(TAG, "PHOTO >>> 原图 ${bmp.width}x${bmp.height} 超 ${MAX_PIPELINE_PIXELS / 1_000_000}MP，" +
                "缩到 ${scaled.width}x${scaled.height} 再进流水线")
            scaled
        } else {
            bmp
        }
        photo = use
        // 70mm 标签大约占画面 35%：据此估一维码有多少像素，低于 120px 基本解不出
        Log.i(TAG, "PHOTO >>> $from ${use.width}x${use.height} 一维码估计宽度=${"%.0f".format(use.width * 0.35f)}px")
        process(use, from)
    }

    private fun reprocess() {
        // 从**原始图**重跑：photo 可能是上一次校正过的标签图，再校正一次会二次透视变换
        val bmp = lastRaw ?: photo ?: return
        process(bmp, "重跑")
    }

    private fun reshoot() {
        screen = "camera"
        plan = null
        photo = null
        lastRaw = null
        lastWorking = null
        template = ZplGeometry.DEFAULT_TEMPLATE
        status = "对准标签拍照"
        if (hasCameraPermission()) bindCamera()
    }

    private fun process(bmp: Bitmap, name: String) {
        busy = true
        status = "识别中…（$name）"
        Thread {
            val r = runCatching { analyze(bmp, "$name.png", useOcr) }
            runOnUiThread {
                busy = false
                val (p, msg) = r.getOrNull() ?: (null to "识别失败：${r.exceptionOrNull()?.message}")
                status = msg
                if (p != null) {
                    plan = p
                    template = p.template       // 同步自动选出的模板，界面 chip 与后续重排都以它为准
                    photo = lastWorking ?: bmp  // 显示校正后的标签图：要素框就是在它上面量的
                    screen = "result"
                    // 结果页不再需要取景器：解绑相机，省电也免得预览占着相机内存
                    runCatching { provider?.unbindAll() }
                    cameraReady = false
                } else {
                    Log.e(TAG, "pipeline failed", r.exceptionOrNull())
                }
            }
        }.start()
    }

    // ---------------- 要素编辑 ----------------

    private fun applyEdit(edit: ElementEdit) {
        val p = plan ?: return
        val list = p.elements.toMutableList()
        when (edit) {
            is ElementEdit.Delete -> if (edit.index in list.indices) list.removeAt(edit.index)
            is ElementEdit.Text -> list[edit.index] = list[edit.index].copy(text = edit.value, source = "user")
            is ElementEdit.Format -> list[edit.index] = list[edit.index].copy(format = edit.value, source = "user")
        }
        // 编辑后必须重排重出 ZPL（坐标从 px 重算）：只改内容不重排，旧 ZPL 里还是老数据
        val next = LabelPipeline.remap(p, list, p.template)
        plan = next
        status = "已更新，ZPL 已重算（${next.zplErrors.size} 个校验错误）"
    }

    private fun copy(zpl: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("ZPL", zpl))
        status = "ZPL 已复制到剪贴板"
    }

    private fun saveZpl(p: ReprintPlan) {
        val name = "label_${System.currentTimeMillis()}.zpl"
        val f = File(getExternalFilesDir(null) ?: filesDir, name)
        f.writeText(p.zpl)
        Log.i(TAG, "SAVE >>> ${f.absolutePath}")
        status = "已导出：${f.absolutePath}"
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, p.zpl)
        }
        startActivity(Intent.createChooser(share, "发送 ZPL"))
    }

    // ---------------- 调试钩子（脚本回归用，与界面共用同一条主链） ----------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        engineKind = engineKindOf(intent)
        readPrintExtras(intent)
        injected = extrasKey(intent)
    }

    /** `am start --es zebra 1`：只走 Zebra DL 模型；`--es zxing 1`：只走 zxing-cpp；默认混合 */
    private fun engineKindOf(i: Intent?): String = when {
        i == null -> "hybrid"
        i.hasExtra("zebra") -> "zebra"
        i.hasExtra("zxing") -> "zxing"
        else -> "hybrid"
    }

    private fun extrasKey(i: Intent?): String? = when {
        i == null -> null
        i.hasExtra("zprobeFile") -> "PROBE_FILE:${i.getStringExtra("zprobeFile")}"
        i.hasExtra("zprobe") -> "PROBE:${i.getStringExtra("zprobe")}"
        // 带 OCR 的整链（界面走的就是这条）：--es all 1 --es ocr 1
        i.hasExtra("all") && i.hasExtra("ocr") -> "OCR_ALL"
        i.hasExtra("all") || i.hasExtra("zebra") -> "ALL"
        // OCR_TEST 之前判断 asset：否则 `--es asset x.png --es ocr 1` 会命中 OCR_TEST、
        // 把刚加进来的用户图丢掉（实测：想跑"指定图 + OCR"必须先走这条）。
        i.hasExtra("asset") -> {
            val n = i.getStringExtra("asset")
            if (i.hasExtra("ocr")) "asset:$n#ocr" else "asset:$n"
        }
        i.hasExtra("diag") -> "diag:${i.getStringExtra("diag")}"
        i.hasExtra("testImage") -> "file:${i.getStringExtra("testImage")}"
        else -> null
    }

    private fun runInjected(key: String): Pair<ReprintPlan?, String> {
        return try {
            when {
                key == "ALL" -> runAllAssets(withOcr = false)
                key == "OCR_ALL" -> runAllAssets(withOcr = true)
                key == "OCR_TEST" -> runOcrTest()
                key.startsWith("PROBE:") -> runProbe(key.removePrefix("PROBE:"))
                key.startsWith("PROBE_FILE:") -> runProbeFile(key.removePrefix("PROBE_FILE:"))
                key.startsWith("asset:") -> {
                    val body = key.removePrefix("asset:")
                    val withOcr = body.endsWith("#ocr")
                    runAsset(body.removeSuffix("#ocr"), withOcr)
                }
                key.startsWith("file:") -> runFile(key.removePrefix("file:"))
                key.startsWith("diag:") -> runDiag(key.removePrefix("diag:"))
                else -> null to "未知指令"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "pipeline failed", t)
            null to "失败：${t.message}"
        }
    }

    /**
     * 实验矩阵入口。`--es zprobe 1` 跑 3 张标签样图；`--es zprobe gt_code128_clean.png`
     * 跑指定 asset（省时间：一次只验一张）。
     */
    private fun runProbe(key: String): Pair<ReprintPlan?, String> {
        val z = engine as? ZebraBarcodeEngine
            ?: return null to "probe 需要 zebra 引擎：加 --es zebra 1"
        val names = if (key.isBlank() || key == "1")
            listOf("label_sample_1.png", "label_sample_2.png", "label_sample_3.png")
        else listOf(if (key.endsWith(".png")) key else "$key.png")
        var ok = 0
        for (n in names) {
            val bmp = assetsBitmap("samples/$n")
            if (bmp == null) {
                Log.e(TAG, "ZPROBE >>> $n 读不到")
                continue
            }
            Log.i(TAG, "ZPROBE >>> [[$n]] size=${bmp.width}x${bmp.height}")
            ok += z.probe(bmp, n).size
        }
        return null to "probe 完成：$ok 条实验记录"
    }

    /** 对设备上的任意图片跑实验矩阵，用来验"模型到底能不能解干净的一维码" */
    private fun runProbeFile(path: String): Pair<ReprintPlan?, String> {
        val z = engine as? ZebraBarcodeEngine
            ?: return null to "probe 需要 zebra 引擎：加 --es zebra 1"
        val bmp = BitmapFactory.decodeFile(path) ?: return null to "文件读不到：$path"
        Log.i(TAG, "ZPROBE >>> [[$path]] size=${bmp.width}x${bmp.height}")
        val ps = z.probe(bmp, path.substringAfterLast('/'))
        return null to "probe 完成：${ps.size} 条实验记录"
    }

    /** `--es ocr 1`：只跑 OCR（不碰条码、不出 ZPL），专门验证 PP-OCRv4 在真机上通不通 */
    private fun runOcrTest(): Pair<ReprintPlan?, String> {
        val bmp = assetsBitmap("samples/label_sample_1.png") ?: return null to "样图读不到"
        val lines = ocr.lines(bmp)
        return null to "OCR：${lines.size} 行（backend=${ocr.backendLabel}，" +
            "err=${ocr.lastError ?: "无"}，${ocr.lastMs}ms）"
    }

    private fun runAsset(name: String, withOcr: Boolean = false): Pair<ReprintPlan?, String> {
        val bmp = assetsBitmap("samples/$name") ?: return null to "asset 读不到：$name"
        return analyze(bmp, name, withOcr = withOcr)
    }

    private fun runFile(path: String): Pair<ReprintPlan?, String> {
        val bmp = BitmapFactory.decodeFile(path) ?: return null to "文件读不到：$path"
        return analyze(bmp, File(path).name, withOcr = false)
    }

    private fun runDiag(name: String): Pair<ReprintPlan?, String> {
        val bmp = assetsBitmap("samples/$name") ?: return null to "asset 读不到：$name"
        com.tiaomabang.labelreprint.barcode.ZxingDiag.run(bmp, TAG)
        return null to "诊断完成：$name"
    }

    private fun runAllAssets(withOcr: Boolean): Pair<ReprintPlan?, String> {
        var last: ReprintPlan? = null
        var ok = 0
        for (n in listOf("label_sample_1.png", "label_sample_2.png", "label_sample_3.png")) {
            val (p, msg) = runAsset(n, withOcr)
            if (p != null) {
                ok++
                last = p
            } else {
                Log.e(TAG, "SAMPLE FAIL $n $msg")
            }
        }
        return last to "内置样图 $ok/3 完成（ocr=$withOcr）"
    }

    private fun assetsBitmap(path: String): Bitmap? =
        runCatching { assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    /**
     * 主链：照片 -> 要素 -> ZPL，并把每一步打进日志（脚本靠这些行做回归）。
     * [withOcr] 默认 false：`--es all 1` 这条回归链必须与 PC 端参考实现（只跑条码）逐字节可比，
     * 混进 OCR 文字要素就不可比了；界面上的流程走 [process]，按用户的开关来。
     */
    private fun analyze(bmp: Bitmap, name: String, withOcr: Boolean): Pair<ReprintPlan?, String> {
        // RUN 行必须在解码之前：设备日志里"解码明细"行会跑在 PLAN 行之前，
        // 校验脚本按 PLAN 切块会把每张图的数据算到上一张头上（早期真踩过）。
        Log.i(TAG, "RUN >>> $name ${bmp.width}x${bmp.height} engine=$engineKind ocr=$withOcr")
        // 先"找标签 + 校正成正视图"，再选模板。直接在原图上映射只在**照片已裁到标签边**时成立：
        // 实测竖幅实拍（2449×3265，标签只占画面中间一块）按 full 映射后整体缩了一半，
        // 条码模块宽只剩 1 点 —— 用户看到的正是"条码和文字都偏小 + 被扫成 39 码"。
        val prep = LabelFrame.prepare(bmp, if (templatePinned) template else null)
        lastRaw = bmp
        lastWorking = prep.working
        Log.i(TAG, "FRAME >>> $name ${prep.basis} 模板${prep.template} ${prep.note}" +
            (prep.quad?.joinToString(" ", prefix = " quad=") {
                "(${"%.0f".format(it.first)},${"%.0f".format(it.second)})"
            } ?: ""))
        val p = LabelPipeline.run(
            prep.working, engine, prep.template, basis = prep.basis,
            ocr = if (withOcr) { b -> ocr.lines(b) } else null,
            contentRect = prep.frameRect,   // 没检出标签边框时：只认取景框内的内容
        )
        // 两个引擎的 ZPL 分开打标签：ZPLZ 走 DL 模型，ZPL 走混合/zxing，
        // 这样 tools/device_zpl_check.py（对 PC 端 zxing）不会被 DL 的结果串味。
        val zplTag = if (engineKind == "zebra") "ZPLZ" else "ZPL"
        val z = engine
        if (z is ZebraBarcodeEngine) {
            Log.i(TAG, "ZSTEPS >>> $name ready=${z.isReady} initMs=${z.initMs} " +
                "decodeMs=${z.lastMs} err=${z.lastError}")
            z.steps.forEach { Log.i(TAG, "ZSTEP >>> $name ${it.strategy} ${it.outcome} n=${it.count} ${it.detail}") }
        }
        if (z is HybridBarcodeEngine) {
            Log.i(TAG, "HYBRIDSTEPS >>> $name zebra=${z.zebraAvailable} ms=${z.lastMs} ${z.notes.joinToString("；")}")
        }
        Log.i(TAG, "PLAN >>> $name ${prep.working.width}x${prep.working.height} basis=${prep.basis} " +
            "elements=${p.elements.size} errors=${p.zplErrors.size}")
        p.elements.forEach { Log.i(TAG, "ELEM >>> ${it.kind} ${it.format} '${it.text}' dots=${it.dots}") }
        p.dropped.forEach { Log.i(TAG, "DROP >>> $it") }
        p.warnings.forEach { Log.i(TAG, "WARN >>> $it") }
        p.zplErrors.forEach { Log.i(TAG, "ZPLERR >>> $it") }
        Log.i(TAG, "$zplTag >>> $name\n${p.zpl.replace("\r\n", "\n")}")
        return p to "$name 完成：${p.elements.size} 个要素，${p.zplErrors.size} 个错误"
    }

    override fun onDestroy() {
        runCatching { provider?.unbindAll() }
        runCatching { hybrid.dispose() }
        runCatching { ocr.close() }
        runCatching { printer.shutdown() }   // 复用同一个 HandlerThread，退出时要 quit
        super.onDestroy()
    }
}
