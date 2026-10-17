package com.tiaomabang.labelreprint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tiaomabang.labelreprint.print.PairedDevice
import com.tiaomabang.labelreprint.ui.theme.ZebraPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 打印机的链路状态。界面上只用这四档，别把 SDK 的十几项布尔值直接摆给人看。 */
enum class LinkPhase { IDLE, CONNECTING, CONNECTED, ERROR }

/**
 * 打印机状态快照（界面唯一真源）。
 *
 * 以前只有一个 `printerState: String`，消息互相覆盖：连上之后打印一次，"就绪"就被
 * "已发送（123 ms）"顶掉，用户再也看不出打印机现在到底能不能用。拆成"链路档位 + 详情"两层：
 * 档位决定灯色，详情是最近一次读到的东西（状态查询 / 发送结果 / 失败原因）。
 */
data class PrinterUi(
    val phase: LinkPhase = LinkPhase.IDLE,
    val target: String = "",      // "AC:3F:A4:F2:56:2F" / "192.168.1.20:9100"
    val name: String = "",        // 配对表里的设备名（ZR668 之类）
    val model: String = "",       // 打印机自己报的型号（SGD device.product_name）
    val detail: String = "",      // 就绪 / 缺纸 / 已发送 123 ms / 失败原因
    val at: Long = 0L,            // detail 的时间戳
) {
    val headline: String
        get() = when (phase) {
            LinkPhase.IDLE -> "未连接"
            LinkPhase.CONNECTING -> "连接中…"
            LinkPhase.CONNECTED -> "已连接"
            LinkPhase.ERROR -> "连接失败"
        }

    /** 这条详情是不是我们主动查来的（能显示"xx 前更新"），还是发送/失败的瞬时消息 */
    val detailIsLive: Boolean
        get() = phase == LinkPhase.CONNECTED && at > 0L
}

private val LinkPhase.color: Color
    get() = when (this) {
        LinkPhase.CONNECTED -> ZebraPalette.Lime
        LinkPhase.CONNECTING -> ZebraPalette.BlueBright
        LinkPhase.ERROR -> ZebraPalette.Danger
        LinkPhase.IDLE -> ZebraPalette.Muted
    }

/**
 * 打印机卡片：状态灯 + 型号/MAC + 实时状态 + 已配对设备选择 + 刷新/重连。
 *
 * 为什么要有"刷新"按钮：状态查询是**主动问打印机**（SGD 往返，蓝牙下一次约 0.3~1s），
 * 不适合每帧都问。自动轮询负责常态，按钮负责"我刚换了纸，立刻看结果"。
 */
@Composable
fun PrinterCard(
    ui: PrinterUi,
    paired: List<PairedDevice>,
    busy: Boolean,
    onChoose: (PairedDevice) -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ---- 第一行：状态灯 + 标题 + 型号/MAC ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(9.dp).background(ui.phase.color, CircleShape)
                )
                Text(
                    ui.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ui.phase.color,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Text(
                    // 型号优先：蓝牙配对名常是 "XXZKJ184301333" 这种出厂串，型号 ZR668 才是人想看的
                    listOf(ui.model, ui.name).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                        // 未选设备时不显示占位的 "--"（"未连接 --" 看着像出错）
                        .ifBlank { if (ui.phase == LinkPhase.IDLE) "" else "--" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                if (ui.phase == LinkPhase.CONNECTED && ui.detailIsLive) {
                    Text(
                        ago(ui.at),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 第二行：目标地址 + 详情 ----
            if (ui.target.isNotBlank()) {
                Text(
                    ui.target,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.detail.isNotBlank()) {
                Text(
                    ui.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.phase == LinkPhase.ERROR) ZebraPalette.Danger
                    else MaterialTheme.colorScheme.onSurface,
                )
            }

            // ---- 设备选择（选中的高亮；顺序与 listPairedDevices 一致：按名字） ----
            if (paired.isEmpty()) {
                Text(
                    "没有已配对的蓝牙设备：先去系统设置里配对打印机（Android 不允许 App 自动配对）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    paired.forEach { d ->
                        AssistChip(
                            onClick = { onChoose(d) },
                            // 用 mac 比对，不要用名字（车间里两台同名打印机很常见）
                            label = {
                                Text(
                                    d.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                            enabled = !busy,
                            colors = if (d.mac == ui.target) {
                                androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                    containerColor = ZebraPalette.Lime,
                                    labelColor = ZebraPalette.OnLime,
                                )
                            } else {
                                androidx.compose.material3.AssistChipDefaults.assistChipColors()
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    // 没连上时"刷新状态"没有意义，改成重连更符合直觉
                    enabled = !busy && ui.phase == LinkPhase.CONNECTED,
                ) { Text("刷新状态") }
                OutlinedButton(
                    onClick = onReconnect,
                    enabled = !busy && ui.target.isNotBlank(),
                ) { Text(if (ui.phase == LinkPhase.CONNECTED) "重连" else "连接") }
            }
        }
    }
}

private fun ago(at: Long): String {
    if (at <= 0L) return ""
    val sec = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
    return when {
        sec < 2 -> "刚刚"
        sec < 60 -> "${sec}s 前"
        else -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
    }
}
