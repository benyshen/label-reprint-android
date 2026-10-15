package com.tiaomabang.labelreprint.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tiaomabang.labelreprint.model.ElementKind
import com.tiaomabang.labelreprint.model.LabelElement
import com.tiaomabang.labelreprint.model.ReprintPlan
import com.tiaomabang.labelreprint.print.PairedDevice
import com.tiaomabang.labelreprint.zpl.ZplGeometry

/** 一个要素在「元素表」里改了什么，交给 Activity 去重排（UI 不碰几何） */
sealed interface ElementEdit {
    data class Text(val index: Int, val value: String) : ElementEdit
    data class Format(val index: Int, val value: String) : ElementEdit
    data class Delete(val index: Int) : ElementEdit
}

/**
 * 拍照页。相机预览用 Activity 建好的 PreviewView（CameraX 的生命周期绑在 Activity 上，
 * 不放 Compose 里，免得重组时反复 bind/unbind）。
 */
@Composable
fun CameraPane(
    previewView: PreviewView,
    status: String,
    busy: Boolean,
    canShoot: Boolean,
    template: Int,
    onTemplate: (Int) -> Unit,
    onShoot: () -> Unit,
    onPick: () -> Unit,
    onRunSelfTest: () -> Unit,
) {
    // 相机预览/拍照都固定 4:3；屏幕上显示的宽高比取决于持机方向：竖持是 3:4、横持是 4:3。
    // 容器必须照这个比例约束，否则 FIT_CENTER 会留黑边、框就对不上照片范围。
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val cameraViewAspect = if (landscape) 4f / 3f else 3f / 4f
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("标签复刻：拍一张标签照片，识别条码与文字，生成 ZPL", style = MaterialTheme.typography.bodySmall)
        // 预览容器按相机显示比例约束成 3:4（竖持时的 4:3 画幅）：
        // 相机侧预览与拍照都固定 4:3、PreviewView 用 FIT_CENTER 不裁切，
        // 所以这个矩形就是**照片的实际范围** —— 取景框里看到的和拍下来的才是同一视野。
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(cameraViewAspect)) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                // 取景指示框：长宽比 = 当前模板，所以框就是在告诉用户"该拍成什么形状、该占多大"
                CaptureGuide(
                    aspect = ZplGeometry.aspectOf(template),
                    hint = "标签四边对进框内 · 尽量占满",
                    modifier = Modifier.fillMaxSize(),
                )
                // 模板切换搬到拍照页：形状不对时先在这改，不用拍完再去结果页点
                Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZplGeometry.TEMPLATES.values.forEach { t ->
                        val sel = t.id == template
                        Text(
                            "${t.label} ${t.wMm.toInt()}×${t.hMm.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else Color.White,
                            modifier = Modifier
                                .background(
                                    if (sel) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
                                    RoundedCornerShape(5.dp),
                                )
                                .clickable { onTemplate(t.id) }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShoot, enabled = canShoot && !busy, modifier = Modifier.weight(1f)) {
                Text(if (busy) "识别中…" else "拍照识别")
            }
            OutlinedButton(onClick = onPick, enabled = !busy) { Text("相册") }
        }
        TextButton(onClick = onRunSelfTest, enabled = !busy) { Text("跑内置样图（自检）") }
    }
}

/** 结果页：照片 + 要素框叠加、可编辑要素表、ZPL 预览与导出 */
@Composable
fun ResultPane(
    bitmap: Bitmap,
    plan: ReprintPlan,
    status: String,
    busy: Boolean,
    useOcr: Boolean,
    onEdit: (ElementEdit) -> Unit,
    onUseOcr: (Boolean) -> Unit,
    onTemplate: (Int) -> Unit,
    onReanalyze: () -> Unit,
    onReshoot: () -> Unit,
    version: String,
    onCopyZpl: () -> Unit,
    onSaveZpl: () -> Unit,
    onPrint: () -> Unit,
    printerState: String,
    paired: List<PairedDevice>,
    onChoosePrinter: (PairedDevice) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("识别结果", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // 版本号只有一处真源（build.gradle 的 versionName），UI 只读不写死
            Text("V$version", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 重新识别：拿**同一张照片**重跑整条链（拍照没变、只是识别结果想再要一次）。
            // 与"重拍"的区别是它不碰相机；重跑从原始图开始（见 MainActivity.lastRaw 的说明）。
            TextButton(onClick = onReanalyze, enabled = !busy) { Text("重新识别") }
            TextButton(onClick = onReshoot, enabled = !busy) { Text("重拍") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)

        PhotoWithBoxes(bitmap, plan.elements, Modifier.fillMaxWidth().height(240.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("模板", style = MaterialTheme.typography.bodySmall)
            ZplGeometry.TEMPLATES.values.forEach { t ->
                // 标签只放 "模板1 60×40"，且不换行：让 chip 自己换行会把整行撑高、基线错位
                FilterChip(
                    selected = plan.template == t.id,
                    onClick = { onTemplate(t.id) },
                    label = {
                        Text("${t.label} ${t.wMm.toInt()}×${t.hMm.toInt()}",
                            maxLines = 1, softWrap = false)
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = useOcr, onClick = { onUseOcr(true) }, label = { Text("含文字 OCR") })
            FilterChip(selected = !useOcr, onClick = { onUseOcr(false) }, label = { Text("仅条码") })
            Text("基准=${plan.basis}", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("要素 ${plan.elements.size} 个（点一下改内容/码制，✕ 删除）",
            style = MaterialTheme.typography.titleSmall)
        // 单独包一层：卡片之间 4dp（外层 Column 的 8dp 是给"区块之间"用的，
        // 列表内部不需要那么松）
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            plan.elements.forEachIndexed { i, e ->
            Card(Modifier.fillMaxWidth()) {
                // 紧凑排布：卡片内边距 6dp、行间不加间距；"改/✕"用 Text + clickable 而不是 TextButton
                // —— M3 的 TextButton 有 48dp 最小触控高度，会把"类型行"上下撑出大片空白。
                Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${kindLabel(e.kind)}${if (e.format.isNotEmpty()) " · ${e.format}" else ""}"
                                + if (e.hri) " · 明文行" else "",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("改", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { editing = i }
                                .padding(horizontal = 12.dp, vertical = 2.dp))
                        Text("✕", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onEdit(ElementEdit.Delete(i)) }
                                .padding(horizontal = 12.dp, vertical = 2.dp))
                    }
                    SelectionContainer {
                        Text(e.text.ifBlank { "(空)" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            }
        }
        plan.dropped.forEach { Text("· 丢弃：$it", style = MaterialTheme.typography.bodySmall) }
        plan.warnings.forEach { Text("· 警告：$it", style = MaterialTheme.typography.bodySmall) }
        plan.zplErrors.forEach { Text("· 校验错误：$it", style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        Text("ZPL（${plan.labelDots.first}×${plan.labelDots.second} 点）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopyZpl) { Text("复制") }
            OutlinedButton(onClick = onSaveZpl, enabled = !busy) { Text("导出 .zpl") }
        }

        // ---- 打印（Zebra Link-OS 蓝牙 / 网口） ----
        HorizontalDivider()
        Text("打印", style = MaterialTheme.typography.titleSmall)
        Text(printerState, style = MaterialTheme.typography.bodySmall)
        if (paired.isEmpty()) {
            Text("没有已配对的蓝牙设备：先去系统设置里配对打印机（Android 不允许 App 自动配对）",
                style = MaterialTheme.typography.bodySmall)
        } else {
            // 列出已配对设备供选择；顺序与 print.listPairedDevices 一致（按名字排序）
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                paired.forEach { d ->
                    AssistChip(
                        onClick = { onChoosePrinter(d) },
                        label = { Text(d.name, maxLines = 1, softWrap = false) },
                    )
                }
            }
        }
        Button(onClick = onPrint, enabled = !busy) { Text("打印这张标签") }
        Card(Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(plan.zpl.trim(), style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
            }
        }
    }

    editing?.let { idx ->
        val e = plan.elements[idx]
        EditDialog(
            element = e,
            onDismiss = { editing = null },
            onConfirm = { newText, newFormat ->
                if (newFormat != e.format) onEdit(ElementEdit.Format(idx, newFormat))
                if (newText != e.text) onEdit(ElementEdit.Text(idx, newText))
                editing = null
            },
        )
    }
}

/** 照片上按要素框叠加描边，让「识别到哪、框到哪」一眼可见（坐标是照片像素） */
@Composable
private fun PhotoWithBoxes(bitmap: Bitmap, elements: List<LabelElement>, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.background(Color(0xFF202020))) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().onSizeChanged { size = it },
        )
        Canvas(Modifier.fillMaxSize()) {
            if (size.width == 0 || bitmap.width == 0) return@Canvas
            // ContentScale.Fit 的实际绘制矩形：等比缩放后居中
            val s = minOf(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
            val ox = (size.width - bitmap.width * s) / 2f
            val oy = (size.height - bitmap.height * s) / 2f
            elements.forEach { e ->
                drawRect(
                    color = if (e.kind == ElementKind.TEXT) Color(0xFF4CAF50) else Color(0xFFFF5252),
                    topLeft = Offset(ox + e.px[0] * s, oy + e.px[1] * s),
                    size = Size((e.px[2] - e.px[0]) * s, (e.px[3] - e.px[1]) * s),
                    style = Stroke(width = 3f),
                )
            }
        }
    }
}

@Composable
private fun EditDialog(
    element: LabelElement,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var text by remember { mutableStateOf(element.text) }
    var format by remember { mutableStateOf(element.format) }
    val formats = formatsFor(element.kind)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑要素") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (formats.isNotEmpty()) {
                    Text("码制", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        formats.forEach { f ->
                            FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f) })
                        }
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text, format) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** ZPL 只实现了 ^BC(128)/^B3(39)/^BQ(QR)，码制选项就限定在这三种，不让用户选到打不出的 */
private fun formatsFor(kind: ElementKind): List<String> = when (kind) {
    ElementKind.BARCODE_1D -> listOf("Code 128", "Code 39")
    ElementKind.BARCODE_2D -> listOf("QR Code")
    ElementKind.TEXT -> emptyList()
}

private fun kindLabel(kind: ElementKind): String = when (kind) {
    ElementKind.BARCODE_1D -> "一维条码"
    ElementKind.BARCODE_2D -> "二维码"
    ElementKind.TEXT -> "文字"
}
