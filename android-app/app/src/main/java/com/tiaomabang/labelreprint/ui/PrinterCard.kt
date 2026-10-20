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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tiaomabang.labelreprint.print.Transport
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
    val target: String = "",      // "AC:3F:A4:F2:56:2F"
    val name: String = "",        // 配对表 / BLE 广播里的设备名（ZR668 之类）
    val model: String = "",       // 打印机自己报的型号（SGD device.product_name）
    val detail: String = "",      // 就绪 / 缺纸 / 已发送 123 ms / 失败原因
    val at: Long = 0L,            // detail 的时间戳
    val transport: Transport = Transport.NONE,
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

    /** 型号优先：蓝牙名常是 "XXZKJ184301333" 这种出厂串，型号 ZR668 才是人想看的 */
    val title: String
        get() = listOf(model, name).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

private val LinkPhase.color: Color
    get() = when (this) {
        LinkPhase.CONNECTED -> ZebraPalette.Lime
        LinkPhase.CONNECTING -> ZebraPalette.BlueBright
        LinkPhase.ERROR -> ZebraPalette.Danger
        LinkPhase.IDLE -> ZebraPalette.Muted
    }

/** 链路类型的小徽标（SPP / BLE）。两种链路的排查手段完全不同，界面上必须能一眼看出。 */
@Composable
fun TransportBadge(transport: Transport, modifier: Modifier = Modifier) {
    if (transport == Transport.NONE) return
    val c = if (transport == Transport.BLE) ZebraPalette.BlueBright else ZebraPalette.Muted
    Text(
        transport.short,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = c,
        modifier = modifier
            .background(c.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * 结果页上的一条窄栏：只说"现在能不能打"，详细设置都进独立页面。
 *
 * 为什么不在结果页摆设备列表：结果页的任务是"核对这张标签然后打出去"，
 * 里面塞一堆设备 chip 会把「重拍 / 打印」挤出屏幕；车间里换打印机的频率远低于看标签。
 */
@Composable
fun PrinterBar(ui: PrinterUi, enabled: Boolean, onOpen: () -> Unit) {
    val c = ui.phase.color
    Row(
        Modifier
            .fillMaxWidth()
            .background(ZebraPalette.CardHigh, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Text(
            ui.headline,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = c,
            modifier = Modifier.padding(start = 6.dp),
        )
        if (ui.transport != Transport.NONE) {
            TransportBadge(ui.transport, Modifier.padding(start = 6.dp))
        }
        Text(
            ui.title.ifBlank { if (ui.phase == LinkPhase.IDLE) "没选打印机" else "--" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        // 详情短就显示（"就绪"），长原因留给打印机页；这条栏只有一行的高度
        if (ui.phase == LinkPhase.CONNECTED && ui.detail.isNotBlank()) {
            Text(
                ui.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        TextButton(onClick = onOpen, enabled = enabled) { Text("打印机") }
    }
}

/**
 * 打印机页顶部的大卡片：状态灯 + 型号/地址 + 实时状态 + 刷新/断开/重连。
 *
 * 为什么要有"刷新"按钮：状态查询是**主动问打印机**（SGD 往返，蓝牙下一次约 0.3~1s），
 * 不适合每帧都问。自动轮询负责常态，按钮负责"我刚换了纸，立刻看结果"。
 */
@Composable
fun PrinterStatusCard(
    ui: PrinterUi,
    busy: Boolean,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ZebraPalette.Card),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(ui.phase.color, CircleShape))
                Text(
                    ui.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ui.phase.color,
                    modifier = Modifier.padding(start = 8.dp),
                )
                if (ui.transport != Transport.NONE) {
                    TransportBadge(ui.transport, Modifier.padding(start = 8.dp))
                }
                Box(Modifier.weight(1f))
                if (ui.detailIsLive) {
                    Text(
                        ago(ui.at),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                ui.title.ifBlank { "还没有选打印机" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (ui.target.isNotBlank()) {
                Text(
                    ui.target,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.detail.isNotBlank()) {
                Text(
                    ui.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.phase == LinkPhase.ERROR) ZebraPalette.Danger
                    else MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !busy && ui.phase == LinkPhase.CONNECTED,
                ) { Text("刷新状态") }
                OutlinedButton(
                    onClick = onReconnect,
                    enabled = !busy && ui.target.isNotBlank(),
                ) { Text(if (ui.phase == LinkPhase.CONNECTED) "重连" else "连接") }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !busy && ui.phase != LinkPhase.IDLE,
                ) { Text("断开") }
            }
        }
    }
}

internal fun ago(at: Long): String {
    if (at <= 0L) return ""
    val sec = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
    return when {
        sec < 2 -> "刚刚"
        sec < 60 -> "${sec}s 前"
        else -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
    }
}
