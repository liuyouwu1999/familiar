package dev.liuyouwu.familiar.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** 当前是否为夜垦（深色）像素主题，组件库据此取木框高光色。 */
val LocalPixelDark = staticCompositionLocalOf { false }

private val LightColorScheme = lightColorScheme(
    primary = CarrotOrange,
    onPrimary = OnCarrot,
    primaryContainer = PaperShade,
    onPrimaryContainer = Ink,
    inversePrimary = CarrotOrangeDark,
    secondary = Wood,
    onSecondary = PaperLight,
    secondaryContainer = PaperShade,
    onSecondaryContainer = Ink,
    tertiary = SkyBlue,
    onTertiary = PaperLight,
    tertiaryContainer = SkyBlueLight,
    onTertiaryContainer = Ink,
    background = ParchmentBg,
    onBackground = Ink,
    surface = PaperLight,
    onSurface = Ink,
    surfaceVariant = PaperShade,
    onSurfaceVariant = InkSoft,
    inverseSurface = WoodDark,
    inverseOnSurface = PaperLight,
    error = BerryRed,
    onError = PaperLight,
    errorContainer = Color(0xFFF3D3C9),
    onErrorContainer = BerryRed,
    outline = WoodDark,
    outlineVariant = PaperShade,
    scrim = WoodBorder,
    surfaceTint = Wood
)

private val DarkColorScheme = darkColorScheme(
    primary = CarrotOrange,
    onPrimary = NightBorder,
    primaryContainer = NightPaperShade,
    onPrimaryContainer = NightInk,
    inversePrimary = CarrotOrangeLight,
    secondary = NightWood,
    onSecondary = NightInk,
    secondaryContainer = NightPaperShade,
    onSecondaryContainer = NightInk,
    tertiary = SkyBlue,
    onTertiary = NightBorder,
    tertiaryContainer = SkyBlueDark,
    onTertiaryContainer = NightInk,
    background = NightBg,
    onBackground = NightInk,
    surface = NightPaper,
    onSurface = NightInk,
    surfaceVariant = NightPaperShade,
    onSurfaceVariant = NightInkSoft,
    inverseSurface = NightInk,
    inverseOnSurface = NightBorder,
    error = BerryRed,
    onError = NightInk,
    errorContainer = Color(0xFF4A221E),
    onErrorContainer = Color(0xFFF2B8AC),
    outline = NightWoodDark,
    outlineVariant = NightPaperShade,
    scrim = NightBorder,
    surfaceTint = NightWood
)

/** 像素风：所有 Material 组件一律直角（0dp 圆角 = 直角的 CornerBasedShape）。 */
private val PixelSquare = RoundedCornerShape(0.dp)
private val PixelShapes = Shapes(
    extraSmall = PixelSquare,
    small = PixelSquare,
    medium = PixelSquare,
    large = PixelSquare,
    extraLarge = PixelSquare
)

@Composable
fun FamiliarTheme(
    useSystemFont: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // 状态栏/导航栏跟随木框深色，系统图标用浅色。
    // context 是 Activity 才有 window 可染（悬浮窗宿主是 Service，跳过系统栏染色）
    val view = LocalView.current
    if (!view.isInEditMode && view.context is android.app.Activity) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = (if (darkTheme) NightBorder else WoodDark).toArgb()
            window.navigationBarColor = (if (darkTheme) NightBorder else WoodDark).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    CompositionLocalProvider(LocalPixelDark provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(useSystemFont),
            shapes = PixelShapes,
            content = content
        )
    }
}
