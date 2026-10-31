package dev.liuyouwu.familiar.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 「垦牧小筑」像素组件库：
 * 直角 + 木框凹凸（bevel）语言。外圈 1dp 深棕描边，框体上/左受光、下/右背光，内芯纸面。
 */

/** 一整套凹凸框配色。 */
data class PixelScheme(
    val container: Color,
    val frame: Color,
    val highlight: Color,
    val shadow: Color,
    val border: Color
)

/** 中性面板（AI 气泡/卡片内芯）的像素配色，自动跟随明暗主题。 */
@Composable
fun pixelPanelColors(): PixelScheme =
    if (LocalPixelDark.current) {
        PixelScheme(NightPaper, NightWood, NightWoodLight, NightWoodDark, NightBorder)
    } else {
        PixelScheme(PaperLight, Wood, WoodLight, WoodDark, WoodBorder)
    }

/**
 * 次要辅助文字透明度：深色（夜垦）主题下 50% 透明文字的对比度贴着 WCAG 3:1 下限，
 * 统一提到 0.72 提亮；浅色主题维持原 [lightAlpha]，像素观感不变。
 * 用法：MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
 */
@Composable
fun pixelSecondaryAlpha(lightAlpha: Float = 0.5f): Float =
    if (LocalPixelDark.current) 0.72f else lightAlpha

/** 用户气泡的天蓝像素配色。 */
@Composable
fun pixelUserColors(): PixelScheme = PixelScheme(
    SkyBlue, SkyBlue, SkyBlueLight, SkyBlueDark, WoodBorder
)

/** 画一个星露谷式的凹凸面板框（不裁剪内容，内芯由 container 填充）。 */
fun Modifier.pixelFrame(
    container: Color,
    frame: Color,
    highlight: Color,
    shadow: Color,
    border: Color,
    bevel: Dp = 4.dp,
    pressed: Boolean = false
): Modifier = drawBehind {
    val b = 1.dp.toPx()
    val f = bevel.toPx()
    val w = size.width
    val h = size.height
    val hi = if (pressed) shadow else highlight
    val sh = if (pressed) highlight else shadow

    // 框体底色
    drawRect(frame)
    // 受光面：上/左
    drawRect(hi, Offset(b, b), Size(w - 2 * b, f.coerceAtMost(h / 2f)))
    drawRect(hi, Offset(b, b), Size(f.coerceAtMost(w / 2f), h - 2 * b))
    // 背光面：下/右
    drawRect(sh, Offset(b, (h - b - f).coerceAtLeast(0f)), Size(w - 2 * b, f.coerceAtMost(h / 2f)))
    drawRect(sh, Offset((w - b - f).coerceAtLeast(0f), b), Size(f.coerceAtMost(w / 2f), h - 2 * b))
    // 内芯
    val ix = b + f
    drawRect(
        container,
        Offset(ix, ix),
        Size((w - 2 * ix).coerceAtLeast(0f), (h - 2 * ix).coerceAtLeast(0f))
    )
    // 最外圈描边
    drawRect(border, style = Stroke(b))
}

/** 星露谷对话面板：木框 + 纸内芯。 */
@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colors: PixelScheme = pixelPanelColors(),
    bevel: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .pixelFrame(
                container = colors.container,
                frame = colors.frame,
                highlight = colors.highlight,
                shadow = colors.shadow,
                border = colors.border,
                bevel = bevel,
                pressed = pressed
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(contentPadding)
    ) {
        Column(content = content)
    }
}

/** 像素按钮：橙色凹凸块，按下时凹进（bevel 反转）。 */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val body = if (enabled) container else MaterialTheme.colorScheme.surfaceVariant
    val label = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .pixelFrame(
                container = body,
                frame = body,
                highlight = if (pressed) CarrotOrangeDark else CarrotOrangeLight,
                shadow = if (pressed) CarrotOrangeLight else CarrotOrangeDark,
                border = WoodBorder,
                bevel = 3.dp,
                pressed = pressed
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .defaultMinSize(minWidth = 64.dp, minHeight = 40.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        ProvideTextStyle(textStyle.copy(color = label)) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 方形像素图标按钮：默认透明底（按下发亮）；filled 非空时为实心行动按钮（如发送）。
 *  onLongClick 非空时启用长按：combinedClickable 沿用同一 interactionSource/indication，
 *  像素按压态不被默认涟漪替换；null（默认）走原 clickable 路径，既有调用点零改动。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PixelIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    filled: Color? = null,
    onLongClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val frameModifier = when {
        filled != null -> Modifier.pixelFrame(
            container = filled,
            frame = filled,
            highlight = if (pressed) CarrotOrangeDark else CarrotOrangeLight,
            shadow = if (pressed) CarrotOrangeLight else CarrotOrangeDark,
            border = WoodBorder,
            bevel = 2.dp,
            pressed = pressed
        )
        pressed -> Modifier.pixelFrame(
            container = MaterialTheme.colorScheme.surfaceVariant,
            frame = MaterialTheme.colorScheme.secondary,
            highlight = WoodLight,
            shadow = MaterialTheme.colorScheme.inverseSurface,
            border = WoodBorder,
            bevel = 2.dp,
            pressed = true
        )
        else -> Modifier
    }

    // 长按非空时换 combinedClickable：interactionSource/indication 原样沿用，
    // 按压像素态（pressed → frameModifier）不受默认涟漪影响
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick
        )
    } else {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
    }

    Box(
        modifier = modifier
            .size(42.dp)
            .then(frameModifier)
            .then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                filled != null -> if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
                enabled -> tint
                else -> tint.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 木条顶栏：木框底、奶油字、底部深棕压边。置于 Scaffold topBar 槽内。 */
@Composable
fun PixelTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val dark = LocalPixelDark.current
    val frameColor = MaterialTheme.colorScheme.secondary
    val topLight = if (dark) NightWoodLight else WoodLight
    val edgeColor = if (dark) NightBorder else WoodDark
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .drawBehind {
                drawRect(frameColor)
                // 顶部受光细条
                drawRect(topLight, Offset.Zero, Size(size.width, 2.dp.toPx()))
                // 底部深色压边
                val e = 3.dp.toPx()
                drawRect(edgeColor, Offset(0f, size.height - e), Size(size.width, e))
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationIcon?.invoke()
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        shadow = Shadow(color = WoodBorder, offset = Offset(1f, 1f))
                    ),
                    color = PaperLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    // onSubtitleClick 非空时副标题可点（如聊天页点模型名换模型），带下拉小箭头
                    Row(
                        modifier = if (onSubtitleClick != null) {
                            Modifier.clickable(onClick = onSubtitleClick)
                        } else Modifier,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = PaperLight.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (onSubtitleClick != null) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "选择模型",
                                tint = PaperLight.copy(alpha = 0.75f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
            actions()
        }
    }
}

/** 像素风选择 chip：选中=主题色填充+反白文字（同"开始对话"橙色做法），未选中=纸面+木框描边。 */
@Composable
fun PixelChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 选中态用主行动色填充 + 反白字；未选中保持纸面 + 描边的低调外观
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val frame = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val highlight = if (selected) CarrotOrangeLight else WoodLight
    val shadow = if (selected) CarrotOrangeDark else WoodDark

    Box(
        modifier = modifier
            .pixelFrame(
                container = container,
                frame = frame,
                highlight = highlight,
                shadow = shadow,
                border = WoodBorder,
                bevel = 3.dp,
                pressed = pressed
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 横滑行右缘渐隐：消除“内容被屏幕切断”的观感。 */
@Composable
fun Modifier.pixelEdgeFade(): Modifier {
    val bg = MaterialTheme.colorScheme.background
    return this.drawWithContent {
        drawContent()
        val w = 28.dp.toPx()
        drawRect(
            Brush.horizontalGradient(
                colors = listOf(Color.Transparent, bg),
                startX = size.width - w,
                endX = size.width
            ),
            topLeft = Offset(size.width - w, 0f),
            size = Size(w, size.height)
        )
    }
}

/** 面板间分隔：2dp 暗纸线。 */
@Composable
fun PixelDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** 底部弹出的像素面板（替代级联 AlertDialog 做模式选择）。内容超高时内部滚动。 */
@Composable
fun PixelBottomPanel(
    onDismiss: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            PixelPanel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** 底部面板里的整行模式入口：彩色像素小方块图标 + 标题 + 副标题。 */
@Composable
fun PixelModeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconBox: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .pixelFrame(
                    container = iconBox,
                    frame = iconBox,
                    highlight = PaperLight.copy(alpha = 0.45f),
                    shadow = WoodBorder.copy(alpha = 0.45f),
                    border = WoodBorder,
                    bevel = 3.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // 装饰性：右侧标题/副标题文字已表意
                tint = PaperLight,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
