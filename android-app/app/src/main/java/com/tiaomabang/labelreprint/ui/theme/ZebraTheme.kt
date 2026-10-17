package com.tiaomabang.labelreprint.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Zebra 风格配色（对齐 https://www.zebra.com 的现行视觉）。
 *
 * 取值来源：zebra.com/us/en.html 的 2026-10-12 快照，直接读**渲染后的 computed style**
 * （站点有 WAF，抓 HTML/CSS 拿不到，必须让浏览器渲染后取色）：
 *
 * | 用途 | 站点实测 | 用在哪 |
 * |---|---|---|
 * | 强调绿 | `#A8F931`（`.btn--primary-green`，胶囊 20px 圆角、文字纯黑） | primary / 取景框 / 选中态 |
 * | 纯黑 | `#000000`（大区块背景、按钮底） | background |
 * | 站点正文黑 | `#202020` | 浅底上的文字 / 叠在照片上的深色描边 |
 * | 浅灰底 | `#F2F2F2`（body 背景） | 需要"纸感"时的浅色块 |
 * | 边框灰 | `#333333`（导航分隔线） | outline |
 * | 链接蓝 | `#003FBD`，hover `#0073E6` | secondary / 照片上的条码框（白纸上比绿更清楚） |
 *
 * 站点整体是**深色**：黑底 + 白字 + 荧光绿胶囊按钮。App 沿用同构：
 * 黑底（相机/结果页本来就偏暗）、绿色只给"要你动手的地方"（按钮、当前模板、取景框）。
 *
 * 字体没法对齐：站点用 ZebraSans（商用字体），App 用系统默认，不硬凑。
 */
object ZebraPalette {
    val Lime = Color(0xFFA8F931)          // 站点强调绿
    val OnLime = Color(0xFF000000)        // 绿底上永远是黑字
    val Black = Color(0xFF000000)         // 站点区块底色
    val Ink = Color(0xFF202020)           // 站点正文色
    val Paper = Color(0xFFF2F2F2)         // 站点浅底
    val Line = Color(0xFF333333)          // 站点边框
    val Blue = Color(0xFF003FBD)          // 站点链接蓝
    val BlueBright = Color(0xFF0073E6)    // 站点 hover 蓝（深底上更可读）
    val Muted = Color(0xFF9A9A9A)         // 次要文字（站点 #5C5F66 在深底上提亮）
    val Surface = Color(0xFF0A0A0A)
    val SurfaceLow = Color(0xFF121212)
    val Card = Color(0xFF161616)
    val CardHigh = Color(0xFF1F1F1F)
    val Danger = Color(0xFFFF6B6B)
}

private val ZebraDark = darkColorScheme(
    primary = ZebraPalette.Lime,
    onPrimary = ZebraPalette.OnLime,
    primaryContainer = Color(0xFF2A3D0E),      // 绿压暗当容器（选区/次级按钮）
    onPrimaryContainer = Color(0xFFD6FF9E),
    inversePrimary = Color(0xFF3F5B10),

    secondary = ZebraPalette.BlueBright,
    onSecondary = Color.White,
    // 选中态（FilterChip/选中胶囊）就用站点的绿胶囊：绿底黑字，和"打印"按钮同一套语言。
    secondaryContainer = ZebraPalette.Lime,
    onSecondaryContainer = ZebraPalette.OnLime,

    tertiary = ZebraPalette.Muted,
    onTertiary = Color.Black,

    background = ZebraPalette.Black,
    onBackground = Color.White,
    surface = ZebraPalette.Surface,
    onSurface = ZebraPalette.Paper,
    surfaceVariant = ZebraPalette.CardHigh,
    onSurfaceVariant = ZebraPalette.Muted,
    surfaceTint = ZebraPalette.Lime,

    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = ZebraPalette.SurfaceLow,
    surfaceContainer = ZebraPalette.Card,
    surfaceContainerHigh = ZebraPalette.CardHigh,
    surfaceContainerHighest = Color(0xFF262626),

    outline = Color(0xFF4A4A4A),      // 站点 #333 在纯黑上太灰，提到 #4A4A4A 才看得见按钮描边
    outlineVariant = Color(0xFF242424),

    error = ZebraPalette.Danger,
    onError = Color.Black,
    errorContainer = Color(0xFF3A1414),
    onErrorContainer = Color(0xFFFFD6D6),
)

/** 站点按钮是 20px 圆角的胶囊，这里让 M3 的 large 跟上 */
private val ZebraShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ZebraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZebraDark, shapes = ZebraShapes, content = content)
}
