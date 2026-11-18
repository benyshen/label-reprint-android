package com.tiaomabang.labelreprint.print

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.zebra.sdk.btleComm.BluetoothLeConnection
import com.zebra.sdk.comm.BluetoothConnection
import com.zebra.sdk.comm.BluetoothConnectionInsecure
import com.zebra.sdk.comm.Connection
import com.zebra.sdk.comm.ConnectionException
import com.zebra.sdk.printer.PrinterLanguage
import com.zebra.sdk.printer.PrinterStatus
import com.zebra.sdk.printer.SGD
import com.zebra.sdk.printer.ZebraPrinterFactory
import com.zebra.sdk.printer.ZebraPrinterLinkOs
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 链路类型。界面上必须能看出"这条是经典蓝牙还是低功耗蓝牙"，两者排查手段完全不同。 */
enum class Transport(val label: String, val short: String) {
    NONE("未连接", ""),
    SPP("经典蓝牙（SPP）", "SPP"),
    BLE("低功耗蓝牙（BLE）", "BLE"),
}

/**
 * 打印通道抽象。真机用 [ZebraGateway]；没有打印机时用 [DryRunGateway] 只生成 ZPL，
 * 这样界面与版面逻辑可以在没有硬件的情况下先验证完。
 *
 * 移植自 E:/workspace/voice-print-android（同一套 Link-OS ZSDK_ANDROID_API.jar），
 * 那边踩过的坑在这里全部保留，别删注释里的原因说明。
 */
interface PrinterGateway {
    /** "BT:AA:BB:.." 经典蓝牙 | "BLE:AA:BB:.." 低功耗蓝牙 | "TCP:1.2.3.4[:9100]" 网口 */
    suspend fun connect(spec: String): GatewayResult
    suspend fun status(): String
    suspend fun printZpl(zpl: String): GatewayResult
    suspend fun sendSgd(name: String, value: String): GatewayResult
    suspend fun getSgd(name: String): GatewayResult
    suspend fun disconnect()
    fun isConnected(): Boolean
    /** 当前走的是哪条链路（连上之前是 NONE） */
    fun transport(): Transport
}

sealed class GatewayResult {
    data class Ok(val info: String = "") : GatewayResult()
    data class Err(val reason: String) : GatewayResult()
}

/** 已配对的蓝牙设备（用于界面选打印机；序号与界面显示必须同一排序） */
data class PairedDevice(val name: String, val mac: String)

/**
 * 枚举已配对的蓝牙设备。Android 12+ 需要 BLUETOOTH_CONNECT，没有权限时返回空表而不是抛异常
 * —— 让界面能区分"没权限"和"没配对"。
 */
fun listPairedDevices(ctx: Context): List<PairedDevice> {
    val need = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (need && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED
    ) {
        Log.w("PrinterGateway", "缺少 BLUETOOTH_CONNECT，无法枚举已配对设备")
        return emptyList()
    }
    return runCatching {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        (mgr.adapter ?: BluetoothAdapter.getDefaultAdapter())?.bondedDevices
            ?.map { PairedDevice(it.name ?: "(无名)", it.address) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }.getOrElse {
        Log.w("PrinterGateway", "枚举已配对设备失败：${it.message}")
        emptyList()
    }
}

/**
 * Zebra Link-OS（ZSDK_ANDROID_API.jar 手工放 app/libs/，并必须显式加 Jackson 三件套）。
 *
 * 硬约束（官方样例里写死的，别省）：
 * 1) **调用线程必须有 Looper**（官方样例 `Looper.prepare()`）。Zebra SDK 内部用 Handler 做
 *    状态回调与超时，在没有 Looper 的线程上 `open()`/`getCurrentStatus()` 会莫名超时。
 *    这里用专用 HandlerThread + asCoroutineDispatcher，所有 SDK 调用都落在这个线程上。
 * 2) 同一 MAC 串行：一个 Connection 同时只能有一个在途写 -> Mutex。
 * 3) 写完 `^XZ` 不能立刻 close，至少等 300~500ms，否则尾包丢、标签只打一半。
 * 4) 经典蓝牙：Android 不允许程序自动配对，打印机必须先在系统设置里配对好。
 *    低功耗蓝牙（BLE）：**不需要系统配对**，直接拿 MAC 建 GATT，所以新机（ZD421 这类只开 BLE 的）能连上。
 * 5) 连接后先强制 ZPL 语言，并用 getInstance(PrinterLanguage.ZPL, conn) 免掉语言探测往返。
 * 6) **不要用 ConnectionBuilder.build()**：2.14 的 Android 版即使字符串合法也会抛
 *    ConnectionException("Invalid connection type")，必须直接构造连接对象。
 * 7) BLE 需要 `com.zebra.sdk.btleComm.*`（ZSDK 2.15+ 才有）。旧的 2.14 jar 里没有这个包，
 *    换成新 jar 才编得过；`app/libs/ZSDK_ANDROID_API-2.14-noBLE.jar.bak` 是旧包的备份。
 */
class ZebraGateway(
    private val appContext: Context,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : PrinterGateway {

    companion object {
        private const val TAG = "ZebraGateway"

        /** 连接后先发这个，否则 line-mode 机器会把 ZPL 当纯文本吐出来 */
        const val SET_ZPL = "! U1 setvar \"device.languages\" \"zpl\"\n"

        /** 换纸后校准 */
        const val CALIBRATE = "~jc^xa^jus^xz"

        /** 蓝牙模式：ZD/ZT 系列必须 both/classic 才有 SPP，LE-only 配对连不上 */
        const val SGD_BT_MODE = "bluetooth.le.controller_mode"
    }

    // 专用 SDK 线程（带 Looper），满足官方 Looper.prepare() 的要求
    private val thread = HandlerThread("zebra-sdk").apply { start() }
    private val sdk = Handler(thread.looper).asCoroutineDispatcher()

    private val lock = Mutex()
    @Volatile
    private var conn: Connection? = null

    @Volatile
    private var printer: ZebraPrinterLinkOs? = null

    /** 当前链路类型（连上之后才有效）。BLE 的 MTU / 分包行为和 SPP 完全不同，界面与日志都要能区分。 */
    @Volatile
    private var link: Transport = Transport.NONE

    override fun transport(): Transport = link

    override fun isConnected(): Boolean = runCatching { conn?.isConnected == true }.getOrDefault(false)

    override suspend fun connect(spec: String): GatewayResult = withContext(sdk) {
        lock.withLock {
            try {
                closeInternal()
                val normalized = spec.trim()
                // 前缀只在这里判定一次，界面/调用方不再猜，避免 "BT:" 与 "BLE:" 互相回退掩盖真错误
                val attempts: List<Pair<String, () -> Connection>> = when {
                    // 低功耗蓝牙：不需要系统配对，直接建 GATT（ZD421 这类只开 BLE 的新机就走这条）
                    normalized.startsWith("BLE:", true) -> {
                        val mac = normalized.substringAfter(':').trim()
                        listOf("BluetoothLeConnection" to {
                            BluetoothLeConnection(mac, appContext) as Connection
                        })
                    }
                    normalized.startsWith("BT:", true) -> {
                        val mac = normalized.substringAfter(':').trim()
                        listOf(
                            "BluetoothConnection" to { BluetoothConnection(mac) as Connection },
                            "BluetoothConnectionInsecure" to { BluetoothConnectionInsecure(mac) as Connection }
                        )
                    }
                    else -> {
                        val body = normalized.removePrefix("TCP:").removePrefix("tcp:")
                        val host = body.substringBefore(':').trim()
                        if (host.isBlank()) throw IllegalArgumentException("不支持的目标：$normalized")
                        val port = body.substringAfter(':', "9100").trim().toIntOrNull() ?: 9100
                        listOf("TcpConnection:$host:$port" to {
                            com.zebra.sdk.comm.TcpConnection(host, port) as Connection
                        })
                    }
                }
                var lastErr: Throwable? = null
                var opened: Connection? = null
                var usedName = ""
                for ((name, factory) in attempts) {
                    val cand = try {
                        factory()
                    } catch (t: Throwable) {
                        log("$name 构造失败：${t.message}")
                        lastErr = t
                        continue
                    }
                    try {
                        cand.open()
                        opened = cand
                        usedName = name
                        break
                    } catch (t: Throwable) {
                        // 原始异常必须完整进日志：humanReason() 是给界面看的人话，
                        // 排查时它会把真实原因盖掉（实测踩过：真正的 IO 错误被显示成"缺蓝牙权限"）。
                        log("$name open 失败：${t.javaClass.name}: ${t.message}")
                        lastErr = t
                        runCatching { cand.close() }
                    }
                }
                val c: Connection = opened ?: throw (lastErr ?: IllegalStateException("无可用的连接方式"))
                link = when {
                    usedName == "BluetoothLeConnection" -> Transport.BLE
                    usedName.startsWith("BluetoothConnection") -> Transport.SPP
                    else -> Transport.NONE
                }
                if (link == Transport.BLE) {
                    // MTU 决定每包能塞多少字节：SDK 会自己按 MTU 分包，但日志里记下来，
                    // 一旦出现"只打了一半"就能先看 MTU 是不是被协商成了默认 23。
                    val mtu = runCatching { (c as BluetoothLeConnection).getCurrentMTU() }.getOrNull()
                    log("已建立连接：$usedName（BLE，MTU=$mtu）")
                } else {
                    log("已建立连接：$usedName")
                }
                c.write(SET_ZPL.toByteArray(Charsets.UTF_8))
                val p: ZebraPrinterLinkOs? = runCatching { ZebraPrinterFactory.getLinkOsPrinter(c) }
                    .getOrElse {
                        log("getLinkOsPrinter 失败(${it.message})，改用 getInstance(ZPL)")
                        runCatching { ZebraPrinterFactory.getInstance(PrinterLanguage.ZPL, c) }
                            .map { gp -> ZebraPrinterFactory.createLinkOsPrinter(gp, PrinterLanguage.ZPL) }
                            .getOrNull()
                    }
                val model = runCatching { SGD.GET("device.product_name", c)?.trim() }.getOrNull()
                    ?: runCatching { SGD.GET("appl.name", c)?.trim() }.getOrNull()
                    ?: "Zebra 打印机"
                conn = c
                printer = p
                log("connected: $model @ $normalized (linkOs=${p != null})")
                GatewayResult.Ok(model)
            } catch (t: Throwable) {
                log("connect failed: ${t.javaClass.name}: ${t.message}")
                closeInternal()
                GatewayResult.Err(humanReason(t))
            }
        }
    }

    override suspend fun status(): String = withContext(sdk) {
        // 与打印共用同一把锁：轮询（每 5s 一次）绝不能插进一次 ZPL 发送中间去写同一条链路，
        // 否则蓝牙通道上的读写会交错，成纸可能只打一半。
        lock.withLock {
            val c = conn ?: return@withLock "未连接"
            runCatching {
                val st: PrinterStatus = (printer ?: ZebraPrinterFactory.getLinkOsPrinter(c)).currentStatus
                when {
                    st.isReadyToPrint -> "就绪"
                    st.isPaused -> "已暂停（请在打印机上恢复）"
                    st.isHeadOpen -> "打印头未合上"
                    st.isPaperOut -> "缺纸（请装纸后校准）"
                    st.isReceiveBufferFull -> "缓冲已满，稍后再发"
                    st.isRibbonOut -> "碳带用尽"
                    else -> "状态未知"
                }
            }.getOrElse { humanReason(it) }
        }
    }

    override suspend fun printZpl(zpl: String): GatewayResult = withContext(sdk) {
        lock.withLock {
            val t0 = System.currentTimeMillis()
            try {
                val c = conn ?: return@withLock GatewayResult.Err("未连接打印机")
                if (!c.isConnected) c.open()
                c.write(zpl.toByteArray(Charsets.UTF_8))
                Thread.sleep(400)   // 等尾包落盘，少了这句标签只打一半
                val st = runCatching {
                    (printer ?: ZebraPrinterFactory.getLinkOsPrinter(c)).currentStatus
                }.getOrNull()
                if (st != null && (st.isPaperOut || st.isHeadOpen || st.isPaused)) {
                    GatewayResult.Err(statusTextOf(st))
                } else {
                    GatewayResult.Ok("${System.currentTimeMillis() - t0} ms")
                }
            } catch (t: Throwable) {
                log("print failed: ${t.message}")
                GatewayResult.Err(humanReason(t))
            }
        }
    }

    /** 换纸后校准：~jc^xa^jus^xz */
    suspend fun calibrate(): GatewayResult = withContext(sdk) {
        lock.withLock {
            runCatching {
                conn?.write(CALIBRATE.toByteArray(Charsets.UTF_8))
                Thread.sleep(300)
                GatewayResult.Ok("已发送校准指令")
            }.getOrElse { GatewayResult.Err(humanReason(it)) }
        }
    }

    override suspend fun sendSgd(name: String, value: String): GatewayResult = withContext(sdk) {
        lock.withLock {
            runCatching {
                val c = conn ?: return@withLock GatewayResult.Err("未连接打印机")
                SGD.SET(name, value, c)
                GatewayResult.Ok("$name = ${SGD.GET(name, c)}")
            }.getOrElse { GatewayResult.Err(humanReason(it)) }
        }
    }

    override suspend fun getSgd(name: String): GatewayResult = withContext(sdk) {
        lock.withLock {
            runCatching {
                val c = conn ?: return@withLock GatewayResult.Err("未连接打印机")
                GatewayResult.Ok(SGD.GET(name, c))
            }.getOrElse { GatewayResult.Err(humanReason(it)) }
        }
    }

    override suspend fun disconnect() = withContext(sdk) { lock.withLock { closeInternal() } }

    /**
     * 读打印头分辨率（203 / 300 / 600）。
     *
     * SGD 键 `head.resolution.in_dpi` 是 Zebra 官方给的"查打印头 DPI"命令（Support 000026368）。
     * 老固件没有这个键时才退到 `ezpl.resolution`。读不到返回 null —— 调用方保持用户手选的档位，
     * 绝不猜：猜错的代价是整批标签按错误点阵印出来（300 的机器收到 203 的坐标，只印出 2/3 大）。
     */
    suspend fun resolution(): Int? = withContext(sdk) {
        lock.withLock {
            val c = conn ?: return@withLock null
            val keys = listOf("head.resolution.in_dpi", "ezpl.resolution")
            for (k in keys) {
                val v = runCatching { SGD.GET(k, c) }.getOrNull()?.trim()?.trim('"')
                val d = v?.toIntOrNull() ?: continue
                if (d in listOf(203, 300, 600)) {
                    log("resolution: $k = $d dpi")
                    return@withLock d
                }
            }
            log("resolution: 读不到（备用键全部无效），沿用设置里手选的档位")
            null
        }
    }


    /** 退出时调用：复用同一个 HandlerThread，别忘了 quit */
    fun shutdown() = runCatching { thread.quitSafely() }

    private fun closeInternal() {
        runCatching { conn?.close() }
        conn = null
        printer = null
        link = Transport.NONE
    }

    private fun statusTextOf(st: PrinterStatus): String = when {
        st.isPaused -> "打印机已暂停"
        st.isHeadOpen -> "打印头未合上"
        st.isPaperOut -> "打印机缺纸"
        st.isReceiveBufferFull -> "缓冲区满"
        else -> "打印机未就绪"
    }

    private fun humanReason(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            m.contains("read failed", true) || m.contains("socket might closed", true) ->
                "蓝牙链路建不起来（SPP 套接字失败）。常见原因：这个配对是 BLE-only，" +
                    "打印机蓝牙模式需改为 both/classic 后重新配对；或打印机不在范围内。" +
                    "也可能它是 BLE 机型 —— 换「低功耗蓝牙」那一栏连接试试。"
            // BLE 的失败原因和 SPP 完全不同：GATT 133 是最常见的一个，几乎都跟"没连上/被抢走"有关
            m.contains("133", true) || m.contains("GATT", true) ->
                "低功耗蓝牙连接被中断（GATT 133）：先关掉手机的蓝牙再打开，或把打印机重启一次；" +
                    "注意同一条 BLE 打印机同一时间只能被一台手机连接"
            m.contains("mtu", true) || m.contains("negotiate", true) ->
                "低功耗蓝牙协商 MTU 失败：离打印机近一点再试（BLE 距离比 SPP 敏感）"
            t is ConnectionException && m.contains("timed out", true) ->
                "打印机无响应（超时）：确认已开机、在蓝牙/网络可达范围内"
            m.contains("Service discovery", true) || m.contains("uuid", true) ->
                "找不到串口服务(SPP)：该设备可能不是打印机，或配对方式不对"
            // 这两个都是 Android 12+ 的运行时权限，缺任意一个都在 open() 就炸
            m.contains("BLUETOOTH_SCAN", true) ->
                "缺少蓝牙扫描权限（Android 12+：Zebra SDK 连接时会调 cancelDiscovery，需要 BLUETOOTH_SCAN）"
            m.contains("permission", true) || m.contains("SecurityException", true) ->
                "缺少蓝牙权限（Android 12+ 需 BLUETOOTH_CONNECT + BLUETOOTH_SCAN）"
            m.contains("not connected", true) -> "蓝牙未连接"
            m.contains("Unable to connect to network printer", true) || m.contains("connect to", true) ->
                "网络打印机连不上：确认 IP、端口 9100、与打印机同网段"
            else -> m.ifBlank { "未知错误" }
        }
    }
}

/** 无打印机时的干跑通道：只记录 ZPL，用于验证版面与流程 */
class DryRunGateway : PrinterGateway {
    override suspend fun connect(spec: String) = GatewayResult.Ok("干跑模式（未连接真实打印机）")
    override suspend fun status() = "干跑模式"
    override suspend fun printZpl(zpl: String) =
        GatewayResult.Ok("已生成 ZPL（未发送，${zpl.length} 字符）")
    override suspend fun sendSgd(name: String, value: String) = GatewayResult.Ok("干跑模式：$name=$value")
    override suspend fun getSgd(name: String) = GatewayResult.Ok("干跑模式")
    override suspend fun disconnect() {}
    override fun isConnected() = true
    override fun transport() = Transport.NONE
}
