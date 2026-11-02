package com.tiaomabang.labelreprint.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.opencv.core.Point
import kotlin.math.hypot

/**
 * 四点标定页（移植自 doc-scan-demo 已验证的手动平面矫正方案）。
 *
 * 为什么拍照流程要引入这一步：自动四边形检测在低对比度场景（白纸贴浅色背景、
 * 透明托盘、反光）上不可靠 —— doc-scan-demo 实测：手动四点 + getPerspectiveTransform
 * 一次就能把倾斜标签拉成正视图。所以主入口改成**用户亲手点四个角**，
 * 矫正后的图再进条码/OCR/框体识别，最后生成 ZPL。
 *
 * 几何约定与 doc-scan-demo 完全一致：角点始终以**图像像素坐标**存在 List<Point> 里，
 * 显示坐标 = 图像坐标 × scalePx（图像位图 → 显示矩形的等比缩放）。
 * 四个点的点按顺序任意 —— LabelFrame.prepareManual 内部用 orderQuad（x+y / x−y）归位，
 * 与两端（OpenCV / PC 参考实现）同一套判据。
 */

/** 四边形的四条线：橙色线 + 青色手柄（沿用 doc-scan-demo 真机验证过的对比度组合） */
private val COL_LINE = Color(0xFFF59E0B)
private val COL_HANDLE = Color(0xFF22D3EE)

/** 初始四边形：四边内缩 8% 的默认框，用户在此基础上拖到标签角上 */
fun defaultQuad(w: Int, h: Int): List<Point> {
    val ix = w * 0.08
    val iy = h * 0.08
    return listOf(
        Point(ix, iy),
        Point(w - ix, iy),
        Point(w - ix, h - iy),
        Point(ix, h - iy),
    )
}

@Composable
fun QuadCropPane(
    photo: Bitmap,
    quad: MutableList<Point>,
    status: String,
    busy: Boolean,
    onDrag: (Int, Point) -> Unit,
    onTapPoint: (Point) -> Unit,
    onAutoPrefill: () -> Unit,
    onReset: () -> Unit,
    onWarp: () -> Unit,
    onRetake: () -> Unit,
) {
    val imgBmp = remember(photo) { photo.asImageBitmap() }
    val dragIdx = remember { mutableStateOf(-1) }
    // 最后一只手指还在动的时间戳。放大镜的显示只依赖 dragIdx，而 dragIdx 靠
    // onDragEnd/onDragCancel 收尾 —— 这两者在"拖动中切 tab / 系统手势抢焦点 /
    // 幽灵指针"时会漏掉（实测：拖住角点切到打印机页再回来，放大镜一直挂在屏幕上）。
    // 所以再加一道看门狗：3 秒没有新的拖动采样就强制收尾。
    val lastDragAt = remember { mutableStateOf(0L) }
    LaunchedEffect(dragIdx.value) {
        if (dragIdx.value < 0) return@LaunchedEffect
        while (isActive) {
            delay(300)
            if (System.currentTimeMillis() - lastDragAt.value > 3000L) {
                dragIdx.value = -1
                break
            }
        }
    }
    // 序号用原生 Canvas 画：Compose 的 DrawScope.drawText 在 2025.09 的 BOM 上已 @Deprecated
    // 且要 TextMeasurer，原生 Paint 一行搞定，也不引入实验 API。
    val numPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "第 2 步 · 四点标定标签（点图片放角点 ①→④，拖圆点可微调）",
            style = MaterialTheme.typography.titleSmall,
        )
        // 容器约束到"照片长宽比能放下的最大矩形"，Image 用 FillBounds 铺满它 ——
        // 于是 scalePx = 显示宽/图像宽 对横纵向都成立（doc-scan-demo 同款公式，实测角点跟手）。
        // 必须用 BoxWithConstraints：Box 作用域里拿不到自身尺寸（读 size 编不过）。
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = photo.width.toFloat() / photo.height
            // maxWidth/maxHeight 是 Dp，Dp/Dp 得 Float：取能完整放下照片的比例框（等价 ContentScale.Fit）
            val boxW = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
            val scalePx = with(LocalDensity.current) { boxW.toPx() } / photo.width

            Box(Modifier.width(boxW).aspectRatio(ratio)) {
                Image(
                    bitmap = imgBmp,
                    contentDescription = "待矫正标签照片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        // 点按 = 把最近的角点直接放到手指处（用户习惯"点四个角"，不是"精确拖住小圆点"）；
                        // 与拖动并存：tap 只在按下-抬起无位移时触发，拖动由下面的 detectDragGestures 接。
                        .pointerInput(scalePx, photo) {
                            detectTapGestures { pos ->
                                onTapPoint(
                                    Point(
                                        (pos.x / scalePx).toDouble()
                                            .coerceIn(0.0, photo.width.toDouble()),
                                        (pos.y / scalePx).toDouble()
                                            .coerceIn(0.0, photo.height.toDouble()),
                                    )
                                )
                            }
                        }
                        .pointerInput(scalePx, photo) {
                            val grabR = 44.dp.toPx()
                            detectDragGestures(
                                onDragStart = { pos ->
                                    // 按下时吸附最近的角点（44dp 内），之后拖动只动这一个点
                                    var best = -1
                                    var bestD = Float.MAX_VALUE
                                    quad.forEachIndexed { i, p ->
                                        val d = hypot(
                                            pos.x - (p.x * scalePx).toFloat(),
                                            pos.y - (p.y * scalePx).toFloat(),
                                        )
                                        if (d < bestD) { bestD = d; best = i }
                                    }
                                    dragIdx.value = if (bestD <= grabR) best else -1
                                    lastDragAt.value = System.currentTimeMillis()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    lastDragAt.value = System.currentTimeMillis()
                                    val i = dragIdx.value
                                    if (i >= 0 && i < quad.size) {
                                        val p = quad[i]
                                        val nx = (p.x + amount.x / scalePx)
                                            .coerceIn(0.0..photo.width.toDouble())
                                        val ny = (p.y + amount.y / scalePx)
                                            .coerceIn(0.0..photo.height.toDouble())
                                        onDrag(i, Point(nx, ny))
                                    }
                                },
                                onDragEnd = { dragIdx.value = -1 },
                                onDragCancel = { dragIdx.value = -1 },
                                // 有些厂商 ROM 在指针流被打断时两回调都不给，靠上面的看门狗兜
                            )
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (quad.size != 4) return@Canvas
                        val pts = quad.map {
                            Offset((it.x * scalePx).toFloat(), (it.y * scalePx).toFloat())
                        }
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            lineTo(pts[1].x, pts[1].y)
                            lineTo(pts[2].x, pts[2].y)
                            lineTo(pts[3].x, pts[3].y)
                            close()
                        }
                        // 框内压暗 18%：一眼看出"矫正后留下的就是这一块"
                        drawPath(path, Color.Black.copy(alpha = 0.18f))
                        // 黑描边 + 橙线：白纸上单根橙线对比度不够（站点取景框同款约定）
                        val active = dragIdx.value >= 0
                        drawPath(path, Color.Black, style = Stroke(width = if (active) 8f else 6f))
                        drawPath(path, COL_LINE, style = Stroke(width = if (active) 5f else 3f))
                        pts.forEachIndexed { i, c ->
                            drawCircle(COL_HANDLE, radius = 17f, center = c)
                            drawCircle(Color.White, radius = 17f, center = c, style = Stroke(width = 2f))
                            // 序号画在手柄上方：核对哪角被拖着（顺序无关，只是可视化）
                            drawContext.canvas.nativeCanvas.drawText(
                                "${i + 1}", c.x - 9f, c.y - 24f, numPaint,
                            )
                        }

                        // ---- 拖动放大镜：手指压住手柄时看不见底下那一小块，标签角压没压住全靠猜 ----
                        // 在点旁边开一块 3 倍放大的视窗（取照片局部），十字交点就是该点的真实位置。
                        val di = dragIdx.value
                        if (di in 0 until quad.size) {
                            val p = quad[di]
                            val zoom = 3f
                            val panel = 132.dp.toPx()
                            // 面板里显示的照片区域：显示边长 ÷（显示缩放 × 放大倍数）
                            val srcW = (panel / (scalePx * zoom)).toInt().coerceAtLeast(8)
                            val srcH = srcW
                            val sx = (p.x - srcW / 2.0).toInt()
                                .coerceIn(0, (photo.width - srcW).coerceAtLeast(0))
                            val sy = (p.y - srcH / 2.0).toInt()
                                .coerceIn(0, (photo.height - srcH).coerceAtLeast(0))
                            // 默认浮在手柄上方；上方放不下就翻到下方；水平不越界
                            val gap = 40.dp.toPx()
                            val dx = (pts[di].x - panel / 2f)
                                .coerceIn(0f, (size.width - panel).coerceAtLeast(0f))
                            val above = pts[di].y - gap - panel
                            val dy = if (above >= 0f) above
                            else (pts[di].y + gap).coerceAtMost((size.height - panel).coerceAtLeast(0f))

                            drawImage(
                                image = imgBmp,
                                srcOffset = IntOffset(sx, sy),
                                srcSize = IntSize(srcW, srcH),
                                dstOffset = IntOffset(dx.toInt(), dy.toInt()),
                                dstSize = IntSize(panel.toInt(), panel.toInt()),
                                filterQuality = FilterQuality.Low,
                            )
                            // 黑底 + 白边：压在任何照片上都分得清面板边界
                            drawRect(
                                Color.Black, topLeft = Offset(dx - 3f, dy - 3f),
                                size = Size(panel + 6f, panel + 6f), style = Stroke(width = 6f),
                            )
                            drawRect(
                                Color.White, topLeft = Offset(dx, dy),
                                size = Size(panel, panel), style = Stroke(width = 2f),
                            )
                            // 十字线：交点 = 该点真实位置（照片像素 → 面板内坐标）
                            val cx = dx + ((p.x - sx) * scalePx * zoom).toFloat()
                            val cy = dy + ((p.y - sy) * scalePx * zoom).toFloat()
                            drawLine(COL_HANDLE, Offset(cx, dy), Offset(cx, dy + panel), strokeWidth = 2f)
                            drawLine(COL_HANDLE, Offset(dx, cy), Offset(dx + panel, cy), strokeWidth = 2f)
                            drawCircle(
                                Color.White, radius = 7f, center = Offset(cx, cy),
                                style = Stroke(width = 3f),
                            )
                        }
                    }
                }
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onAutoPrefill, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("自动预填")
            }
            OutlinedButton(onClick = onReset, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("重置四角")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetake, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("重拍")
            }
            Button(onClick = onWarp, enabled = !busy, modifier = Modifier.weight(2f)) {
                Text(if (busy) "矫正识别中…" else "矫正并识别")
            }
        }
    }
}
