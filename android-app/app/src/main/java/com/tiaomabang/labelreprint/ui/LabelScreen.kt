package com.tiaomabang.labelreprint.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
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
    val index: Int
    data class Text(override val index: Int, val value: String) : ElementEdit
    data class Format(override val index: Int, val value: String) : ElementEdit
    data class Delete(override val index: Int) : ElementEdit
    /** 文字字号（打印点）。size = 0 表示恢复「按墨迹自动」 */
    data class TextSize(override val index: Int, val size: Int) : ElementEdit
    /** 条码 ^BY 的 n（模块宽）与 m（条宽比）。n/m = 0 表示恢复自动/默认 */
    data class BarcodeScale(override val index: Int, val n: Float, val m: Float) : ElementEdit
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
                                    RoundedCornerShape(20.dp),   // 站点胶囊：20px 圆角
                                )
                                .clickable { onTemplate(t.id) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
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

/** 结果页里所有 chip 都走这一个：文字统一 labelMedium —— 默认字号下三枚模板 chip 会溢出被裁 */
@Composable
private fun LabelChip(selected: Boolean, onClick: () -> Unit, text: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        },
    )
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
    onRequad: () -> Unit,
    onReshoot: () -> Unit,
    version: String,
    onCopyZpl: () -> Unit,
    onSaveZpl: () -> Unit,
    onPrint: () -> Unit,
    printerUi: PrinterUi,
    onOpenPrinter: () -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
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
            // 调四角：识别结果偏了（四边形标歪/漏边）时回标定页重拖，不用重新拍照
            TextButton(onClick = onRequad, enabled = !busy) { Text("调四角") }
            TextButton(onClick = onReshoot, enabled = !busy) { Text("重拍") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)

        PhotoWithBoxes(bitmap, plan.elements, Modifier.fillMaxWidth().height(240.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("模板", style = MaterialTheme.typography.bodySmall)
            ZplGeometry.TEMPLATES.values.forEach { t ->
                // 标签只放 "模板1 60×40"，且不换行：让 chip 自己换行会把整行撑高、基线错位
                LabelChip(
                    selected = plan.template == t.id,
                    onClick = { onTemplate(t.id) },
                    text = "${t.label} ${t.wMm.toInt()}×${t.hMm.toInt()}",
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelChip(selected = useOcr, onClick = { onUseOcr(true) }, text = "含文字 OCR")
            LabelChip(selected = !useOcr, onClick = { onUseOcr(false) }, text = "仅条码")
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

        // ---- 打印（Zebra Link-OS：经典蓝牙 / 低功耗蓝牙 / 网口） ----
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("打印", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        // 这一栏只说"能不能打"，选设备/扫描/校准都在独立页（PrinterScreen）
        PrinterBar(ui = printerUi, enabled = !busy, onOpen = onOpenPrinter)
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
            labelW = ZplGeometry.dims(plan.template).first,
            onDismiss = { editing = null },
            onConfirm = { newText, newFormat, newFs, newN, newM ->
                // 顺序要紧：先改内容/码制，再改字号与缩放 —— applyEdit 每次都重排，
                // 反过来的话用户改的字号会被上一步的 copy 覆盖掉（少一个字段就静默丢）。
                if (newFormat != e.format) onEdit(ElementEdit.Format(idx, newFormat))
                if (newText != e.text) onEdit(ElementEdit.Text(idx, newText))
                if (newFs != e.fsOverride) onEdit(ElementEdit.TextSize(idx, newFs))
                if (newN != e.byN || newM != e.byM) onEdit(ElementEdit.BarcodeScale(idx, newN, newM))
                editing = null
            },
        )
    }
}

/** 照片上按要素框叠加描边，让「识别到哪、框到哪」一眼可见（坐标是照片像素） */
@Composable
private fun PhotoWithBoxes(bitmap: Bitmap, elements: List<LabelElement>, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // 颜色要在组合期读出来：Canvas 的 draw lambda 是 DrawScope，不是 @Composable 上下文
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.background(Color.Black)) {
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
                // 双描边：先画一圈黑底再压彩色线。白纸上的亮绿单线几乎看不见，
                // 黑底给轮廓、彩线给语义（条码=品牌绿，文字=白）。
                val box = androidx.compose.ui.geometry.Rect(
                    offset = Offset(ox + e.px[0] * s, oy + e.px[1] * s),
                    size = Size((e.px[2] - e.px[0]) * s, (e.px[3] - e.px[1]) * s),
                )
                drawRect(Color.Black, box.topLeft, box.size, style = Stroke(width = 5.5f))
                drawRect(
                    color = when (e.kind) {
                        ElementKind.TEXT -> Color.White
                        // 框线用琥珀色：它常常和条码框重叠，同色会看不出哪个是检出的线
                        ElementKind.BOX -> Color(0xFFFFC107)
                        else -> accent
                    },
                    topLeft = box.topLeft,
                    size = box.size,
                    style = Stroke(width = 2.5f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(
    element: LabelElement,
    labelW: Int,
    onDismiss: () -> Unit,
    /** text, format, 字号(0=自动), ^BY 的 n(0=自动), m(0=默认) */
    onConfirm: (String, String, Int, Float, Float) -> Unit,
) {
    var text by remember { mutableStateOf(element.text) }
    var format by remember { mutableStateOf(element.format) }
    // 自动值由 ZplGeometry 算 —— 和生成器同一份公式，弹窗里的「自动 24」就是实际会打出来的字号
    val autoFs = remember(element) { ZplGeometry.autoFontSize(element, labelW) }
    val autoN = remember(element) { ZplGeometry.autoModuleWidth(element) }
    var fs by remember(element) { mutableStateOf(element.fsOverride) }        // 0 = 自动
    var byN by remember(element) { mutableStateOf(element.byN) }             // 0 = 自动
    var byM by remember(element) { mutableStateOf(element.byM) }             // 0 = 默认 2.5
    val formats = formatsFor(element.kind)
    val isText = element.kind == ElementKind.TEXT
    val isBar1d = element.kind == ElementKind.BARCODE_1D

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isText) "编辑文字" else if (isBar1d) "编辑条码" else "编辑要素") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (formats.isNotEmpty()) {
                    Text("码制", style = MaterialTheme.typography.bodySmall)
                    // 一维码有 6 种码制，Row 一行放不下（实测 EAN-8 被裁在弹窗外）→ 自动换行
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        formats.forEach { f ->
                            LabelChip(selected = format == f, onClick = { format = f }, text = f)
                        }
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )

                if (isText) {
                    // 字号：自动值上下加减，2 点一档（203dpi 下 1 点 ≈ 0.125mm）
                    StepRow(
                        label = "字号",
                        value = if (fs > 0) "$fs 点" else "自动 $autoFs 点",
                        manual = fs > 0,
                        onMinus = { fs = maxOf(6, (if (fs > 0) fs else autoFs) - 2) },
                        onPlus = { fs = minOf(120, (if (fs > 0) fs else autoFs) + 2) },
                        onAuto = { fs = 0 },
                    )
                    Text(
                        "字号越大字越高，超宽时打印机会把字挤着叠印；超宽会被自动压回可印尺寸。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isBar1d) {
                    // ^BY n,m：n = 模块宽（点），m = 条宽比。缩放整条条码就靠这两个数
                    StepRow(
                        label = "模块宽 ^BY",
                        value = if (byN > 0) "${fmtHalf(byN.toDouble())} 点" else "自动 ${fmtHalf(autoN)} 点",
                        manual = byN > 0,
                        onMinus = { byN = maxOf(1f, (if (byN > 0) byN else autoN.toFloat()) - 0.5f) },
                        onPlus = { byN = minOf(10f, (if (byN > 0) byN else autoN.toFloat()) + 0.5f) },
                        onAuto = { byN = 0f },
                    )
                    Text("条宽比 m", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2.0f, 2.5f, 3.0f).forEach { v ->
                            val cur = if (byM > 0) byM else ZplGeometry.DEFAULT_BY_RATIO.toFloat()
                            LabelChip(
                                selected = Math.abs(cur - v) < 0.01f,
                                onClick = { byM = if (Math.abs(v - ZplGeometry.DEFAULT_BY_RATIO.toFloat()) < 0.01f) 0f else v },
                                text = fmtHalf(v.toDouble()),
                            )
                        }
                    }
                    Text(
                        "n 越大条码越宽；EAN-13 / UPC / ITF 的模块数由码制固定，只有 n 起作用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text, format, fs, byN, byM) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 「− 值 +」加一个「恢复自动」。
 *
 * 排成两行而不是一行：`标签 + − + 值 + + + 恢复自动` 塞进一行时，最后那个中文按钮
 * 会被挤到只剩一个字宽，渲染成竖排的「自／动」（实测截图）。上行放标签与复位入口，
 * 下行只放步进器，宽度就永远够。
 */
@Composable
private fun StepRow(
    label: String,
    value: String,
    manual: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onAuto: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            if (manual) {
                TextButton(onClick = onAuto, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("恢复自动", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("−", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (manual) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("+", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** 2.5 → "2.5"，3.0 → "3"：ZPL 里 ^BY3,3 和 ^BY3,3.0 都合法，但界面别显示多余小数 */
private fun fmtHalf(v: Double): String =
    if (Math.abs(v - Math.round(v)) < 0.01) Math.round(v).toString() else "%.1f".format(v)

/** ZPL 只实现了 ^BC(128)/^B3(39)/^BQ(QR)，码制选项就限定在这三种，不让用户选到打不出的 */
private fun formatsFor(kind: ElementKind): List<String> = when (kind) {
    ElementKind.BARCODE_1D -> listOf("Code 128", "Code 39", "EAN-13", "EAN-8", "UPC-A", "ITF")
    ElementKind.BARCODE_2D -> listOf("QR Code")
    ElementKind.TEXT -> emptyList()
    ElementKind.BOX -> emptyList()
}

private fun kindLabel(kind: ElementKind): String = when (kind) {
    ElementKind.BARCODE_1D -> "一维条码"
    ElementKind.BARCODE_2D -> "二维码"
    ElementKind.TEXT -> "文字"
    ElementKind.BOX -> "框线"
}
