package dev.liuyouwu.familiar.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.theme.WoodBorder

/**
 * 设置屏共享行组件（审计 A6——SettingsDialog 拆分的收口件）：
 * - [SettingsSection]：分节标题（titleSmall/primary，带上/下间距参数——现场各节
 *   16/8、联网搜索 16/4、首节无上间距的实态收敛）；
 * - [SettingsSwitchRow]：标题+说明+Switch 行（设置弹窗 8 处 + MCP 屏「启用 MCP」
 *   共 9 处同构「Row{ Column(标题/说明) Switch }」收口）；
 * - [SettingsTextFieldState]/[rememberSettingsTextFieldState]/[SettingsDirtyTextField]/
 *   [SettingsTextFieldRow]：「外部真值到达填一次 / 用户键入置脏」dirty 编辑态及其
 *   输入框/带保存钮行——原 SettingsDialog 三处复制（帧数/上下文的脏标记跟随、两套
 *   称呼的真值到达填一次锁存）收进一处。
 *
 * Switch 行不做 dirty 封装：开关是受控组件直读直写流（无本地编辑态可脏，null 占位
 * 由「checked = 流值 ?: 默认」展示），现场三处 dirty 复制全是文本框。
 * 表单场景（编辑实体初值、无外部真值流）也可直接 [SettingsTextFieldState](initial)
 * 播种 + [SettingsDirtyTextField] 复用同一输入框形态（Preset 模板编辑器先例）。
 */

/** 设置分节标题（原「Spacer(16) + Text(titleSmall/primary) + Spacer(8)」三行收口） */
@Composable
internal fun SettingsSection(
    title: String,
    topSpacing: Dp = 16.dp,
    bottomSpacing: Dp = 8.dp,
) {
    if (topSpacing > 0.dp) {
        Spacer(modifier = Modifier.size(topSpacing))
    }
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.size(bottomSpacing))
}

/**
 * 设置开关行：左标题+说明、右 Switch（原设置弹窗/MCP 屏 9 处同构行收口）。
 * [checked] 由调用方传流现值（含 null 占位取默认的语义），[onCheckedChange] 直写
 * 业务接线——组件不碰数据。
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    titleFontWeight: FontWeight? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = titleFontWeight
            )
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 「外部真值到达填一次 / 用户键入置脏」编辑态（原 SettingsDialog 三处复制的 dirty
 * 模式收口，QA 发现 11 口径）：
 * - [value] null = 真值未回（不落输入框）或未初始化；调用方以 `value ?: ""` 展示；
 * - [onValueChange] 用户键入：置脏，此后不再跟随外部真值；
 * - [set] 程序性回写（保存时 coerce/trim 的纠正值）：不改脏态——与原「保存按钮直接
 *   写 editing 变量、不动 dirty」一致；
 * - [followIfClean] 未脏时跟随真值：原「LaunchedEffect(external) { if (!dirty) … }」
 *   纯逻辑面，供单测。原两套收口口径（脏标记跟随 / 填一次后锁存）在此统一为
 *   「未脏时跟随」——现场唯一写路径是弹窗自身保存钮（保存即 dismiss 或显式回写），
 *   两口径行为不可分辨。
 */
@Stable
class SettingsTextFieldState(initial: String? = null) {
    var value: String? by mutableStateOf(initial)
        private set
    var dirty: Boolean by mutableStateOf(false)
        private set

    /** 用户键入：置脏，停止跟随外部真值 */
    fun onValueChange(text: String) {
        value = text
        dirty = true
    }

    /** 程序性回写（保存时 coerce/trim 纠正值），不改脏态 */
    fun set(text: String) {
        value = text
    }

    /** 未脏时跟随外部真值（null 真值不落输入框） */
    fun followIfClean(external: String?) {
        if (external != null && !dirty) {
            value = external
        }
    }
}

/**
 * [SettingsTextFieldState] 的组合装配：真值每次变化经 [SettingsTextFieldState.followIfClean]
 * 跟随（未脏时）。须放在弹窗/面板级（勿放 LazyColumn item 内）——编辑态要活过
 * item 滚出视口的回收，与原「editing 状态在对话框层声明」一致。
 */
@Composable
internal fun rememberSettingsTextFieldState(external: String?): SettingsTextFieldState {
    val state = remember { SettingsTextFieldState() }
    LaunchedEffect(external) {
        state.followIfClean(external)
    }
    return state
}

/**
 * 绑定 [SettingsTextFieldState] 的输入框（设置行与表单共用形态）。
 * [state.value] null 展示为空串（真值未回不落框）；键入经 [onValueChange] 置脏。
 * [singleLine]/[keyboardType] 收口现场实态：设置行单行 + 数字键盘可选，表单多行
 * （Preset 模板内容域）可关。
 */
@Composable
internal fun SettingsDirtyTextField(
    state: SettingsTextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType? = null,
) {
    OutlinedTextField(
        value = state.value ?: "",
        onValueChange = state::onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType ?: KeyboardType.Text)
    )
}

/**
 * 设置文本行：输入框 + 行内「保存」钮（RectangleShape+WoodBorder 像素样式）+ 可选
 * 说明行——帧数/最大上下文/闲聊群称呼三处同构行收口。
 * [onCommit] 收到编辑态本体：读 [SettingsTextFieldState.value] 做 coerce/trim 后写
 * 业务接线，纠正值经 [SettingsTextFieldState.set] 回写输入框（与原保存钮一致）。
 * 保存钮默认「真值未回（value==null）禁存」——抢在 DataStore 初值前保存会把默认写回
 * （原两套称呼行的禁存判据）；帧数/上下文行真值恒非空，不受影响。
 */
@Composable
internal fun SettingsTextFieldRow(
    state: SettingsTextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType? = null,
    caption: String? = null,
    saveEnabled: (String?) -> Boolean = { it != null },
    onCommit: (SettingsTextFieldState) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsDirtyTextField(
            state = state,
            label = label,
            modifier = Modifier.weight(1f),
            keyboardType = keyboardType
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            shape = RectangleShape,
            border = BorderStroke(1.dp, WoodBorder),
            enabled = saveEnabled(state.value),
            onClick = { onCommit(state) }
        ) {
            Text("保存")
        }
    }
    caption?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}
