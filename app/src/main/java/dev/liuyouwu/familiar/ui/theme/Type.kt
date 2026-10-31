package dev.liuyouwu.familiar.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.liuyouwu.familiar.R

/** 方舟像素字体 16px（OFL 许可，见 app/src/main/font-licenses/），缺字回退系统字体。 */
val PixelFont = FontFamily(Font(R.font.ark_pixel_16_zh_cn))

private fun pixelStyle(
    fontSize: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal
) = TextStyle(
    fontFamily = PixelFont,
    fontWeight = weight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

/** useSystemFont=true 时全部回退系统默认字体（设置里可切，照顾不喜欢像素风的用户） */
fun appTypography(useSystemFont: Boolean): Typography = if (useSystemFont) systemTypography else pixelTypography

private val pixelTypography = Typography(
    displayLarge = pixelStyle(40, 52),
    displayMedium = pixelStyle(34, 44),
    displaySmall = pixelStyle(28, 38),
    headlineLarge = pixelStyle(26, 36),
    headlineMedium = pixelStyle(24, 34),
    headlineSmall = pixelStyle(21, 30),
    titleLarge = pixelStyle(19, 28, FontWeight.Bold),
    titleMedium = pixelStyle(17, 26, FontWeight.Bold),
    titleSmall = pixelStyle(15, 23, FontWeight.Bold),
    bodyLarge = pixelStyle(16, 24),
    bodyMedium = pixelStyle(15, 23),
    bodySmall = pixelStyle(13, 20),
    labelLarge = pixelStyle(14, 22, FontWeight.Bold),
    labelMedium = pixelStyle(13, 20),
    labelSmall = pixelStyle(11, 17)
)

private fun systemStyle(
    fontSize: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.02.sp
)

private val systemTypography = Typography(
    displayLarge = systemStyle(40, 52),
    displayMedium = systemStyle(34, 44),
    displaySmall = systemStyle(28, 38),
    headlineLarge = systemStyle(26, 36),
    headlineMedium = systemStyle(24, 34),
    headlineSmall = systemStyle(21, 30),
    titleLarge = systemStyle(19, 28, FontWeight.Bold),
    titleMedium = systemStyle(17, 26, FontWeight.Bold),
    titleSmall = systemStyle(15, 23, FontWeight.Bold),
    bodyLarge = systemStyle(16, 24),
    bodyMedium = systemStyle(15, 23),
    bodySmall = systemStyle(13, 20),
    labelLarge = systemStyle(14, 22, FontWeight.Bold),
    labelMedium = systemStyle(13, 20),
    labelSmall = systemStyle(11, 17)
)
