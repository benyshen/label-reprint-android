package com.tiaomabang.labelreprint.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.tiaomabang.labelreprint.zpl.ZplGeometry
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tiaomabang.labelreprint.print.BleDevice
import com.tiaomabang.labelreprint.print.PairedDevice
import com.tiaomabang.labelreprint.print.Transport
import com.tiaomabang.labelreprint.ui.theme.ZebraPalette

/**
 * 打印机设置页（从结果页的「打印机」按钮进来）。
 *
 * 分成两栏是本页的核心设计：**经典蓝牙**和**低功耗蓝牙**是两条物理链路，
 * 能不能连、要不要先配对、扫不扫得到，全都不同。混在一个"设备列表"里，
 * 用户看到 ZD421 没出现就会以为打印机坏了 —— 其实它只在 BLE 广播里。
 */
/** 标签纸尺寸卡片：写进 ZplGeometry 的自定义模板，并持久化到 prefs */
@Composable
private fun LabelSizeCard(onCustomSize: (String, String) -> Unit) {
    val size = ZplGeometry.customSize()
    var wTxt by remember { mutableStateOf(size.first.toInt().toString()) }
    var hTxt by remember { mutableStateOf(size.second.toInt().toString()) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(ZebraPalette.SurfaceLow, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("标签纸尺寸（自定义模板）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "按整张纸的实际毫米数填，默认 76×70。点阵点数随分辨率换算：" +
                ZplGeometry.sizeText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = wTxt,
                onValueChange = { wTxt = it.filter(Char::isDigit).take(3); onCustomSize(wTxt, hTxt) },
                label = { Text("宽 mm") }, singleLine = true, modifier = Modifier.width(110.dp),
            )
            OutlinedTextField(
                value = hTxt,
                onValueChange = { hTxt = it.filter(Char::isDigit).take(3); onCustomSize(wTxt, hTxt) },
                label = { Text("高 mm") }, singleLine = true, modifier = Modifier.width(110.dp),
            )
        }
    }
}

/**
 * 打印分辨率卡片。
 *
 * 为什么必须有这一项：ZPL 里每个数字都是**点**。同一张 76×70mm 的标签，
 * 203dpi 是 607×559 点、300 是 898×827、600 是 1795×1654 ——
 * 按 203 的坐标发给 300 的机器，纸上只会印出 2/3 大的标签（而"打印成功"照样返回）。
 * 所以分辨率是**打印机属性**，放在这一页而不是识别页。
 */
@Composable
private fun DpiCard(dpi: Int, onDpi: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ZebraPalette.SurfaceLow, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("打印分辨率（打印头 DPI）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "203dpi = 8 点/mm、300 = 11.8、600 = 23.6。坐标、字号、条码模块宽、框线粗细都按这个换算。" +
                "连接打印机时会自动读取打印头 DPI；这里手选后以手选为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZplGeometry.SUPPORTED_DPI.forEach { d ->
                val sel = d == dpi
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (sel) ZebraPalette.Lime else ZebraPalette.CardHigh,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onDpi(d) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${d}dpi",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) ZebraPalette.OnLime else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(
            "当前标签：${ZplGeometry.sizeText()}",
            style = MaterialTheme.typography.labelMedium,
            color = if (dpi == 203) ZebraPalette.Muted else ZebraPalette.Lime,
        )
    }
}

@Composable
fun PrinterScreen(
    ui: PrinterUi,
    paired: List<PairedDevice>,
    bleDevices: List<BleDevice>,
    bleScanning: Boolean,
    bleNote: String,
    autoConnect: Boolean,
    busy: Boolean,
    version: String,
    dpi: Int = ZplGeometry.DPI_DEFAULT,
    onDpi: (Int) -> Unit = {},
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onChooseClassic: (PairedDevice) -> Unit,
    onChooseBle: (BleDevice) -> Unit,
    onScanBle: () -> Unit,
    onOpenBtSettings: () -> Unit,
    btGranted: Boolean = true,
    onRequestBt: () -> Unit = {},
    onAutoConnect: (Boolean) -> Unit,
    onTestPrint: () -> Unit,
    onCalibrate: () -> Unit,
    onCustomSize: (String, String) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxSize()) {
        // ---------- 标题行 ----------
        // 这是底部标签页，不是二级页：没有返回箭头，标题只说明"你在哪一页"
        Row(
            Modifier
                .fillMaxWidth()
                .background(ZebraPalette.SurfaceLow)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "打印机",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "v$version",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 纸尺寸是**打印机/耗材**的属性，跟拍照姿势无关，放这一页最顺；
            // 之前盖在取景框上那两块输入框很难点到（用户反馈无法修改），已挪走。
            LabelSizeCard(onCustomSize)
            DpiCard(dpi = dpi, onDpi = onDpi)

            // 权限没到位：扫描与打印都做不了，必须在这一页就有可点的入口
            // （V2.4 现场反馈：只看到"缺少蓝牙权限"，没有任何地方能补授权）
            if (!btGranted) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "缺少蓝牙权限（附近的设备）。没有它扫不到打印机、也连不上。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestBt) { Text("申请权限") }
                        OutlinedButton(onClick = onOpenBtSettings) { Text("打开系统蓝牙设置") }
                    }
                    Text(
                        "若系统不再弹窗：到「设置 → 应用 → 标签复刻机 → 权限 → 附近的设备」手动允许",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            PrinterStatusCard(
                ui = ui,
                busy = busy,
                onRefresh = onRefresh,
                onReconnect = onReconnect,
                onDisconnect = onDisconnect,
            )

            // ---------- 经典蓝牙 ----------
            SectionHeader(
                icon = { Icon(Icons.Filled.Bluetooth, null, tint = ZebraPalette.Muted) },
                title = "经典蓝牙（SPP）",
                hint = "要先在系统蓝牙里配对；老机（ZR668 / ZT 系列）走这条",
            )
            if (paired.isEmpty()) {
                EmptyHint("没有已配对的蓝牙设备。Android 不允许 App 自己配对，请去系统设置里配对打印机。")
                OutlinedButton(onClick = onOpenBtSettings) { Text("打开系统蓝牙设置") }
            } else {
                paired.forEach { d ->
                    DeviceRow(
                        name = d.name,
                        mac = d.mac,
                        badge = Transport.SPP,
                        extra = if (d.mac.equals(ui.target, true)) "当前" else null,
                        selected = d.mac.equals(ui.target, true),
                        enabled = !busy,
                        onClick = { onChooseClassic(d) },
                    )
                }
            }

            // ---------- 低功耗蓝牙 ----------
            SectionHeader(
                icon = { Icon(Icons.Filled.BluetoothSearching, null, tint = ZebraPalette.BlueBright) },
                title = "低功耗蓝牙（BLE）",
                hint = "不用配对；新机（ZD421 这类只开 BLE 的）走这条",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onScanBle,
                    enabled = !bleScanning && !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ZebraPalette.Lime,
                        contentColor = ZebraPalette.OnLime,
                    ),
                ) {
                    if (bleScanning) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = ZebraPalette.OnLime,
                        )
                        Text("扫描中…", Modifier.padding(start = 8.dp))
                    } else {
                        Text("扫描附近打印机")
                    }
                }
                if (bleDevices.isNotEmpty() && !bleScanning) {
                    Text(
                        "${bleDevices.size} 台",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (bleNote.isNotBlank()) {
                Text(
                    bleNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (bleDevices.isEmpty() && !bleScanning && bleNote.isBlank()) {
                EmptyHint("还没扫描。BLE 打印机一开机就在广播，扫不到时先确认打印机蓝牙已开、且离得够近。")
            }
            bleDevices.forEach { d ->
                DeviceRow(
                    name = d.name,
                    mac = d.mac,
                    badge = Transport.BLE,
                    // Zebra SDK 认出来的才敢说"这是打印机"；原生扫描到的只能标"未识别"
                    extra = buildString {
                        if (d.zebra) append("Zebra") else append("未识别")
                        d.rssi?.let { append(" · ${it}dBm") }
                        if (d.mac.equals(ui.target, true)) append(" · 当前")
                    },
                    selected = d.mac.equals(ui.target, true),
                    enabled = !busy,
                    onClick = { onChooseBle(d) },
                )
            }

            HorizontalDivider(color = ZebraPalette.Line)

            // ---------- 其他 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启动时自动连这台", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "车间每天开机就打印，省掉每次重连",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoConnect,
                    onCheckedChange = onAutoConnect,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ZebraPalette.OnLime,
                        checkedTrackColor = ZebraPalette.Lime,
                    ),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTestPrint, enabled = !busy) {
                    Icon(Icons.Filled.Print, null, Modifier.size(16.dp))
                    Text("打印测试页", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onCalibrate, enabled = !busy && ui.phase == LinkPhase.CONNECTED) {
                    Text("校准（换纸后）")
                }
            }

            Text(
                "提示：同一条蓝牙链路同一时间只能被一台手机占用。BLE 打印机被别的手机连着时，" +
                    "这里会一直连不上——先把那台手机的 App 关掉。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(icon: @Composable () -> Unit, title: String, hint: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 1.dp),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 一行设备：名字 + MAC + 链路徽标 + 可选附加信息。整行可点。 */
@Composable
private fun DeviceRow(
    name: String,
    mac: String,
    badge: Transport,
    extra: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) ZebraPalette.Lime.copy(alpha = 0.12f) else ZebraPalette.CardHigh
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) ZebraPalette.Lime else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                mac,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (extra != null) {
            Text(
                extra,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        TransportBadge(badge, Modifier.padding(start = 6.dp))
    }
}
