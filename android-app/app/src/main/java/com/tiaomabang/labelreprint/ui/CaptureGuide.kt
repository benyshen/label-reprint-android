package com.tiaomabang.labelreprint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tiaomabang.labelreprint.label.CaptureFrame

/**
 * 拍照取景指示框。
 *
 * 为什么框的长宽比要跟着模板走：标签的纸面尺寸由模板决定，用户在取景时最需要知道的
 * 就是"该拍成什么形状、该占多大"。方形框去拍长条形标签、或横框去拍纵向标签（如 70×100），
 * 人会不自觉地把标签拍歪、
 * 或拍得太远（内容只占画面一小块），后续按标签映射时精度就白丢了。
 *
 * 三条视觉约定：
 *  - 框外压 38% 黑遮罩，只留框内亮 —— 一眼看出该放多近（标签要占满框）；
 *  - 四角 L 形主色粗线是"对齐点"，让用户拿标签的四个角去贴；
 *  - 中心十字给单条码/单行文字的标签一个对中点。
 */
@Composable
fun CaptureGuide(aspect: Float, hint: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // 框的几何只有一份公式（label/CaptureFrame）：流水线在"没检出标签边框"时
            // 就是按同一个矩形裁剪照片的，两边必须同源，否则框里框外跟实际处理范围对不上
            val r = CaptureFrame.rect(aspect, size.width, size.height)
            val l = r[0]
            val t = r[1]
            val fw = r[2] - r[0]
            val fh = r[3] - r[1]

            // 框外遮罩：四条，把框内"抠亮"
            val scrim = Color.Black.copy(alpha = 0.38f)
            drawRect(scrim, Offset.Zero, Size(size.width, t))
            drawRect(scrim, Offset(0f, t + fh), Size(size.width, size.height - t - fh))
            drawRect(scrim, Offset(0f, t), Size(l, fh))
            drawRect(scrim, Offset(l + fw, t), Size(size.width - l - fw, fh))

            // 细边框
            drawRect(
                Color.White.copy(alpha = 0.5f), Offset(l, t), Size(fw, fh),
                style = Stroke(width = 1.dp.toPx()),
            )

            // 四角 L 形粗线
            val arm = minOf(fw, fh) * 0.16f
            val sw = 3.dp.toPx()
            val corners = listOf(
                Triple(Offset(l, t), Offset(l + arm, t), Offset(l, t + arm)),
                Triple(Offset(l + fw, t), Offset(l + fw - arm, t), Offset(l + fw, t + arm)),
                Triple(Offset(l, t + fh), Offset(l + arm, t + fh), Offset(l, t + fh - arm)),
                Triple(Offset(l + fw, t + fh), Offset(l + fw - arm, t + fh), Offset(l + fw, t + fh - arm)),
            )
            corners.forEach { (c, a, b) ->
                drawLine(accent, c, a, sw)
                drawLine(accent, c, b, sw)
            }

            // 中心十字
            val cx = l + fw / 2f
            val cy = t + fh / 2f
            val cr = minOf(fw, fh) * 0.05f
            val thin = 1.5.dp.toPx()
            val cross = Color.White.copy(alpha = 0.75f)
            drawLine(cross, Offset(cx - cr, cy), Offset(cx + cr, cy), thin)
            drawLine(cross, Offset(cx, cy - cr), Offset(cx, cy + cr), thin)
        }

        Text(
            hint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
