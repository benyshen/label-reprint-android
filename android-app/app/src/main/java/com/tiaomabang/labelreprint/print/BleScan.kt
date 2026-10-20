package com.tiaomabang.labelreprint.print

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.zebra.sdk.btleComm.BluetoothLeDiscoverer
import com.zebra.sdk.btleComm.DiscoveredPrinterBluetoothLe
import com.zebra.sdk.printer.discovery.DiscoveryHandler
import com.zebra.sdk.printer.discovery.DiscoveredPrinter
import java.util.concurrent.ConcurrentHashMap

/** 扫到的一台低功耗蓝牙设备。 */
data class BleDevice(
    val mac: String,
    val name: String,
    /** 广播里的信号强度；SDK 发现（不经原生扫描）时没有，给 null */
    val rssi: Int? = null,
    /** 是不是 Zebra SDK 认出来的打印机。false 只是"附近有台 BLE 设备"，不代表它能收 ZPL。 */
    val zebra: Boolean = false,
)

/**
 * 低功耗蓝牙（BLE）打印机扫描。
 *
 * 为什么两路一起扫，而不是只用 Zebra SDK 的 `BluetoothLeDiscoverer`：
 *
 * - **SDK 那路是"可信名单"**：它按 Zebra 的广播/服务做过滤，扫出来的基本就是 Zebra 打印机，
 *   但正因为过滤，固件没广播那串 UUID 的机器（老固件、或被别家贴牌的机器）永远扫不到，
 *   用户只会看到"没找到打印机"，无从排查。
 * - **原生那路是"照妖镜"**：`BluetoothLeScanner` 把附近所有 BLE 设备原样列出来（名字 + RSSI）。
 *   它能回答"打印机到底在不在广播"这个最关键的问题。非 Zebra 的设备标成"未识别"，
 *   点它也能连（有的第三方 ZPL 机器就是私有服务，SDK 不认识但 GATT 能写）。
 *
 * 两路结果按 MAC 合并：同一个 MAC 只要 SDK 认过，就把 `zebra` 标上。
 *
 * 注意：
 * 1) SDK 的 `findPrinters` 是**阻塞**的，且要求调用线程有 Looper（官方样例写 `Looper.prepare()`），
 *    所以它单独起一个带 Looper 的线程，不能占主线程。
 * 2) 原生扫描要 `BLUETOOTH_SCAN`；Android 12+ 我们已经用 `neverForLocation` 声明，不需要定位权限。
 * 3) 回调可能落在 BLE 线程/主线程两处，统一 post 回主线程再交给界面。
 */
object BleScan {
    private const val TAG = "BleScan"

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    /**
     * 扫 [seconds] 秒。用 [onFound] 逐台回调（已去重），[onDone] 在结束时回调一次。
     * 已经有一轮在跑时直接返回 false，避免两次扫描互相打断。
     */
    fun scan(
        ctx: Context,
        seconds: Long = 8_000,
        onFound: (BleDevice) -> Unit,
        onNote: (String) -> Unit = {},
        onDone: () -> Unit = {},
    ): Boolean {
        if (running) {
            onNote("已经在扫描了")
            return false
        }
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onNote("缺少蓝牙扫描权限（BLUETOOTH_SCAN）")
            return false
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onNote("蓝牙没打开：先打开手机蓝牙再扫描")
            return false
        }

        running = true
        val found = ConcurrentHashMap<String, BleDevice>()

        fun publish(d: BleDevice) {
            val old = found[d.mac]
            // SDK 认过的信息更可信（名字 + zebra 标记），原生那路的 RSSI 更可信 —— 各取所长
            val merged = BleDevice(
                mac = d.mac,
                name = d.name.ifBlank { old?.name.orEmpty() },
                rssi = d.rssi ?: old?.rssi,
                zebra = d.zebra || (old?.zebra ?: false),
            )
            found[d.mac] = merged
            main.post { onFound(merged) }
        }

        // ---- 第一路：原生扫描（所有 BLE 设备） ----
        val scanner = adapter.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = runCatching { r.device.name }.getOrNull()
                    ?: r.scanRecord?.deviceName
                    ?: ""
                publish(BleDevice(r.device.address, name.ifBlank { "(未广播名称)" }, r.rssi, false))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLESCAN >>> 原生扫描失败 errorCode=$errorCode")
                main.post { onNote("扫描失败（errorCode=$errorCode），稍后再试") }
            }
        }
        val started = runCatching {
            scanner?.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
        }
        if (started?.isFailure == true || scanner == null) {
            running = false
            onNote("原生扫描启动失败：${started?.exceptionOrNull()?.message ?: "扫描器不可用"}")
            return false
        }
        Log.i(TAG, "BLESCAN >>> 原生扫描开始 ${seconds}ms")

        // ---- 第二路：Zebra SDK 发现（只认 Zebra 打印机，需要 Looper 线程） ----
        Thread({
            Looper.prepare()
            try {
                BluetoothLeDiscoverer.setDiscoveryTimeoutInterval(seconds)
                BluetoothLeDiscoverer.findPrinters(app, object : DiscoveryHandler {
                    override fun foundPrinter(printer: DiscoveredPrinter) {
                        val mac = printer.address ?: return
                        val name = (printer as? DiscoveredPrinterBluetoothLe)?.friendlyName
                            ?: runCatching { printer.discoveryDataMap?.get("friendlyName") }.getOrNull()
                            ?: "Zebra 打印机"
                        Log.i(TAG, "BLESCAN >>> SDK 发现 $mac $name")
                        publish(BleDevice(mac, name, null, true))
                    }

                    override fun discoveryFinished() {
                        Log.i(TAG, "BLESCAN >>> SDK 发现结束，共 ${found.size} 台")
                    }

                    override fun discoveryError(message: String) {
                        Log.w(TAG, "BLESCAN >>> SDK 发现失败：$message")
                        main.post { onNote("Zebra 发现通道：$message") }
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "BLESCAN >>> SDK 发现异常：${t.javaClass.name}: ${t.message}")
            } finally {
                runCatching { Looper.myLooper()?.quitSafely() }
            }
        }, "ble-sdk-scan").start()

        // ---- 到点收工 ----
        main.postDelayed({
            runCatching { scanner?.stopScan(cb) }
            running = false
            Log.i(TAG, "BLESCAN >>> 结束，共 ${found.size} 台：" +
                found.values.joinToString { "${it.name}@${it.mac}(zebra=${it.zebra})" })
            onDone()
        }, seconds)
        return true
    }
}
