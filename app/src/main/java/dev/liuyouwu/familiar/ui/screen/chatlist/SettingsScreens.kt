package dev.liuyouwu.familiar.ui.screen.chatlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.liuyouwu.familiar.data.repository.ThemeMode
import dev.liuyouwu.familiar.ui.theme.WoodBorder
import dev.liuyouwu.familiar.ui.theme.pixelFrame
import dev.liuyouwu.familiar.ui.theme.pixelPanelColors
import dev.liuyouwu.familiar.util.isEmbeddingModel
import dev.liuyouwu.familiar.util.parseColorHex
import dev.liuyouwu.familiar.viewmodel.ChatListViewModel
import dev.liuyouwu.familiar.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState

/**
 * 主「设置」弹窗（P5 T6 自 ChatListScreen 抽出，原「Model selector dialog」块整体迁移）：
 * 界面（字体/深浅色）、模型（列表/刷新）、视频帧数、最大上下文、大肥鱼（说话模式/
 * 拟人节奏/桌宠/配色/联网搜索/称呼）、查看记忆入口、隐私说明。
 * 会话级状态（弹窗开关、记忆弹窗开关、snackbar/scope）由组合壳 ChatListScreen 持有，
 * 经参数传入；弹窗内部的一次性编辑态（帧数/上下文输入、称呼锁存、悬浮窗授权回流）
 * 随本函数自持——与原 if 块内声明的作用域一致。
 */
@Composable
internal fun SettingsDialog(
    visible: Boolean,
    viewModel: ChatListViewModel,
    chatViewModel: ChatViewModel,
    uiScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    currentModel: String,
    onDismiss: () -> Unit,
    onShowMemory: () -> Unit,
    // MCP 工具子屏入口（T4）：回调照 onShowMemory 先例由组合壳接导航路由
    onOpenMcp: () -> Unit = {}
) {
    if (!visible) return
    // QA 发现 11：remember 首组播种会锁存 stateIn 占位值（真值 262144 后到，输入框
    // 停在占位 131072）——真值到达即跟随，用户键入后置脏停止跟随（DataStore 实证）
    var editingFrameCount by remember { mutableStateOf("") }
    var editingMaxContext by remember { mutableStateOf("") }
    var frameDirty by remember { mutableStateOf(false) }
    var contextDirty by remember { mutableStateOf(false) }

    // ===== 精灵分区状态（读写均经共享 chatViewModel 转发）=====
    // 四个流初值 null=DataStore 真值未回（fix1）：输入框只在非 null 真值到达时填一次，
    // 防止 stateIn 占位默认（主人/我）被当现值锁存、保存时静默重置用户自定义；
    // 模式三选/节奏开关直接读流，null 期无选中/取默认展示，真值到达自然纠正。
    val familiarMode by chatViewModel.familiarMode.collectAsStateWithLifecycle()
    val familiarPacing by chatViewModel.familiarPacing.collectAsStateWithLifecycle()
    val familiarAddress by chatViewModel.familiarAddress.collectAsStateWithLifecycle()
    val familiarSelfName by chatViewModel.familiarSelfName.collectAsStateWithLifecycle()
    val petEnabled by chatViewModel.petEnabled.collectAsStateWithLifecycle()
    val petShowInBackground by chatViewModel.petShowInBackground.collectAsStateWithLifecycle()
    val reviewReminder by chatViewModel.reviewReminder.collectAsStateWithLifecycle()
    val petColor by chatViewModel.petColor.collectAsStateWithLifecycle()
    // 桌宠相伴统计行（B1 Task 7）：PetBondStore 快照流驱动，载盘/打点后自动刷新
    val petBondLine by viewModel.petBondLine.collectAsStateWithLifecycle()
    val chatRoomAddress by chatViewModel.chatRoomAddress.collectAsStateWithLifecycle()
    var editingChatRoomAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chatRoomAddress) {
        // 真值到达且未锁存过才填一次（null 占位不落输入框）
        if (chatRoomAddress != null && editingChatRoomAddress == null) {
            editingChatRoomAddress = chatRoomAddress
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingPet by remember { mutableStateOf(false) }
    // 开关显示"有效状态"：桌宠唯一形态是悬浮窗，没授权时开关显示为关（她不会出现），
    // 免得"开关开着却见不到鱼"无从排查（审计 M4）
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    // 悬浮窗授权页回来：刷新授权状态；已授权且刚才想开 → 落库
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                if (pendingPet) {
                    pendingPet = false
                    if (overlayGranted) {
                        chatViewModel.setPetEnabled(true)
                        dev.liuyouwu.familiar.pet.PetOverlayService.start(context)
                    } else {
                        uiScope.launch { snackbarHostState.showSnackbar("没有悬浮窗权限，没开成") }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var editingFamiliarAddress by remember { mutableStateOf<String?>(null) }
    var editingFamiliarSelfName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(familiarAddress, familiarSelfName) {
        if (editingFamiliarAddress == null && familiarAddress != null) {
            editingFamiliarAddress = familiarAddress
        }
        if (editingFamiliarSelfName == null && familiarSelfName != null) {
            editingFamiliarSelfName = familiarSelfName
        }
    }

    val useSystemFont by chatViewModel.useSystemFont.collectAsStateWithLifecycle()
    // 深浅色模式：SettingsViewModel 直读直写 DataStore，MainActivity 按它即时切主题
    val settingsViewModel: dev.liuyouwu.familiar.viewmodel.SettingsViewModel = viewModel()
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()

    // 模型数据（原 shell 级收集随迁：仅本弹窗消费；currentModel 顶栏也用，经参数传入）
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val videoFrameCount by viewModel.videoFrameCount.collectAsStateWithLifecycle()
    val maxContext by viewModel.maxContext.collectAsStateWithLifecycle()
    val isFetchingModels by viewModel.isFetchingModels.collectAsStateWithLifecycle()
    // QA 发现 12：弹窗首开列表空即自动拉取（顶栏模型在用与"还没有模型"矛盾）；
    // 拉取失败走 modelFetchError 重试行，composition 期只跑一次不循环
    LaunchedEffect(Unit) {
        if (availableModels.isEmpty() && !isFetchingModels) viewModel.fetchModels()
    }
    LaunchedEffect(videoFrameCount, maxContext) {
        // QA 发现 11：真值到达即跟随；用户键入后置脏，不再被流覆盖
        if (!frameDirty) editingFrameCount = videoFrameCount.toString()
        if (!contextDirty) editingMaxContext = maxContext.toString()
    }
    val modelFetchError by viewModel.modelFetchError.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            LazyColumn {
                // 界面 section
                item {
                    Text("界面", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("使用系统字体", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "不喜欢像素风就换回系统默认字体",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = useSystemFont,
                            onCheckedChange = { chatViewModel.setUseSystemFont(it) }
                        )
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    // 深浅色三选：默认跟随系统，选定后不再随系统变
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            ThemeMode.SYSTEM to "跟随系统",
                            ThemeMode.LIGHT to "浅色",
                            ThemeMode.DARK to "深色",
                        ).forEach { (mode, label) ->
                            Row(
                                modifier = Modifier.clickable { settingsViewModel.setThemeMode(mode) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = themeMode == mode,
                                    onClick = { settingsViewModel.setThemeMode(mode) }
                                )
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                // Model section
                item {
                    Text("模型", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                // 过滤嵌入模型：误选对话必报错；过滤后为空则显示全部
                val selectableModels = availableModels
                    .filter { !isEmbeddingModel(it.id.orEmpty()) }
                    .ifEmpty { availableModels }
                // 刷新失败：显示原因 + 重试（旧列表保留时也可见）
                if (modelFetchError != null) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                modelFetchError.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            TextButton(onClick = { viewModel.fetchModels() }) {
                                Text("重试")
                            }
                        }
                    }
                }
                if (availableModels.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (isFetchingModels) "加载模型中…" else "还没有模型，点下方「刷新」拉取",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else {
                    items(selectableModels) { model ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .heightIn(min = 48.dp)
                                .clickable { viewModel.switchModel(model.id.orEmpty()) },
                            colors = CardDefaults.cardColors(
                                containerColor = if ((model.id ?: "") == currentModel) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (model.id ?: "") == currentModel,
                                    onClick = { viewModel.switchModel(model.id.orEmpty()) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = model.id.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Video frames section
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("视频帧数", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editingFrameCount,
                            onValueChange = { editingFrameCount = it; frameDirty = true },
                            label = { Text("帧数") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(shape = RectangleShape, border = BorderStroke(1.dp, WoodBorder), onClick = {
                            val count = editingFrameCount?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                            editingFrameCount = count.toString()
                            viewModel.updateVideoFrameCount(count)
                        }) {
                            Text("保存")
                        }
                    }
                    Text(
                        "采集帧数 1-50，当前 $videoFrameCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                // Max Context section
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("最大上下文 Token", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editingMaxContext,
                            onValueChange = { editingMaxContext = it; contextDirty = true },
                            label = { Text("Token 数") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(shape = RectangleShape, border = BorderStroke(1.dp, WoodBorder), onClick = {
                            val ctx = editingMaxContext?.toIntOrNull()?.coerceIn(512, dev.liuyouwu.familiar.data.repository.SettingsRepository.MAX_CONTEXT_LIMIT)
                                ?: dev.liuyouwu.familiar.data.repository.SettingsRepository.DEFAULT_MAX_CONTEXT
                            editingMaxContext = ctx.toString()
                            viewModel.updateMaxContext(ctx)
                        }) {
                            Text("保存")
                        }
                    }
                    Text(
                        "上下文上限 512-${dev.liuyouwu.familiar.data.repository.SettingsRepository.MAX_CONTEXT_LIMIT}，当前 $maxContext",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                // ===== 大肥鱼分区 =====
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("大肥鱼", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                // 说话模式三选（PersonaPromptBuilder.MODES：default/proactive/passive）
                item {
                    listOf(
                        "default" to "默认（均衡）",
                        "proactive" to "主动搭话",
                        "passive" to "被动应答",
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = familiarMode != mode) {
                                    chatViewModel.setFamiliarMode(mode)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                // null=真值未回：不选中任何项，到达后自然选中
                                selected = familiarMode == mode,
                                onClick = { chatViewModel.setFamiliarMode(mode) }
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        "改动对下一轮对话生效",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                // 拟人节奏开关（false = 即时回复，不分段延迟曝光）
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("拟人节奏", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "回复切段、延迟曝光，像真人打字",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            // null=真值未回：按默认开关展示，真值到达自然纠正（无写路径）
                            checked = familiarPacing ?: true,
                            onCheckedChange = { chatViewModel.setFamiliarPacing(it) }
                        )
                    }
                }
                // 桌宠总开关（全应用唯一）：开 = 常驻悬浮窗（应用内外同一只，需悬浮窗权限）；
                // 关（或长按她的菜单"躲起来"）= 全部收回。位置在拖动时自动记住
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("桌宠", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "连上模型后常驻屏幕，可拖动互动（需悬浮窗权限）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = (petEnabled ?: true) && overlayGranted,
                            onCheckedChange = { checked ->
                                if (!checked) {
                                    chatViewModel.setPetEnabled(false)
                                    dev.liuyouwu.familiar.pet.PetOverlayService.stop(context)
                                } else if (android.provider.Settings.canDrawOverlays(context)) {
                                    chatViewModel.setPetEnabled(true)
                                    dev.liuyouwu.familiar.pet.PetOverlayService.start(context)
                                } else {
                                    pendingPet = true
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:dev.liuyouwu.familiar")
                                        )
                                    )
                                }
                            }
                        )
                    }
                    // 退后台显示（P6 任务2，默认关=退后台收窗）：桌宠关着时无意义不显示
                    if (petEnabled == true) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("退到后台继续显示", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "退到后台后仍显示在其他应用上层",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Switch(
                                checked = petShowInBackground ?: false,
                                onCheckedChange = { chatViewModel.setPetShowInBackground(it) }
                            )
                        }
                        // 复习拍提醒（学习画像 spec §6，默认开）：有到期课题时她闲置拍主动催
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("复习提醒", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "有到期学习课题时，她会冒泡提醒你",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Switch(
                                checked = reviewReminder ?: true,
                                onCheckedChange = { chatViewModel.setReviewReminder(it) }
                            )
                        }
                    }
                    // 皮肤选择（P5 spec §5）：列表进页现扫，accent 色块 + name；选中写 setPetColor(skin.id)
                    val petSkins by chatViewModel.petSkins.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { chatViewModel.refreshPetSkins() }
                    // 皮肤选择：像素卡片，选中 = 主色描边 + ✓ 双信号（不只靠颜色）
                    val panel = pixelPanelColors()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        petSkins.forEach { skin ->
                            val selected = petColor == skin.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pixelFrame(
                                        container = panel.container,
                                        frame = panel.frame,
                                        highlight = panel.highlight,
                                        shadow = panel.shadow,
                                        border = if (selected) MaterialTheme.colorScheme.primary else panel.border
                                    )
                                    .clickable { chatViewModel.setPetColor(skin.id) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(parseColorHex(skin.accent ?: "#9E9E9E"))
                                        .border(1.dp, panel.border)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    skin.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                if (selected) {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    // 相伴统计（spec §5 体现点三）：首见当日算第 1 天，互动含摸摸头/单击等
                    Text(
                        petBondLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                // 角色卡：称呼体系两套配置——大肥鱼一套、闲聊群/圆桌一套，放在同一分区便于对照
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    // 测试结果在弹窗内显示（Snackbar 会被弹窗遮住，审核中-发现5）
                    var searchTestResult by remember { mutableStateOf("") }
                    Text("联网搜索", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("大肥鱼与聊天室联网搜索", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "课堂与圆桌始终开启；搜索词发给必应中国/360/搜狗/维基百科（非官方接口）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = chatViewModel.webSearchEnabled.collectAsStateWithLifecycle().value,
                            onCheckedChange = { chatViewModel.setWebSearchEnabled(it) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            uiScope.launch {
                                searchTestResult = "测试中…"
                                // 测的是生产同款多源聚合（单测必应会漏掉搜狗/360 挂掉），失败文案逐源透出
                                val r = dev.liuyouwu.familiar.search.MultiSourceSearchEngine(listOf(
                                    "必应" to dev.liuyouwu.familiar.search.BingCnEngine(),
                                    "搜狗" to dev.liuyouwu.familiar.search.SogouEngine(),
                                    "360" to dev.liuyouwu.familiar.search.So360Engine(),
                                    "维基百科" to dev.liuyouwu.familiar.search.WikipediaEngine(),
                                )).search("联网搜索测试")
                                searchTestResult = if (r.hits.isEmpty()) "失败：${r.diagnostics}"
                                else "正常，命中 ${r.hits.size} 条"
                            }
                        }) { Text("测试搜索") }
                    }
                    if (searchTestResult.isNotEmpty()) {
                        Text(
                            searchTestResult,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("角色卡", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        "大肥鱼",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editingFamiliarAddress ?: "",
                            onValueChange = { editingFamiliarAddress = it },
                            label = { Text("称呼你") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = editingFamiliarSelfName ?: "",
                            onValueChange = { editingFamiliarSelfName = it },
                            label = { Text("它的自称") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        // 真值未回（输入框空）前禁存：抢在 DataStore 初值前保存会把默认写回
                        Button(
                            shape = RectangleShape, border = BorderStroke(1.dp, WoodBorder),
                            enabled = editingFamiliarAddress != null && editingFamiliarSelfName != null,
                            onClick = {
                                chatViewModel.saveFamiliarNames(
                                    editingFamiliarAddress.orEmpty(),
                                    editingFamiliarSelfName.orEmpty()
                                ) {
                                    // 关掉设置弹窗再弹 snackbar：对话框会遮住宿主 SnackbarHost
                                    onDismiss()
                                    uiScope.launch { snackbarHostState.showSnackbar("已生效") }
                                }
                            }
                        ) {
                            Text("保存")
                        }
                    }
                    Text(
                        "下一轮对话生效；卡里的示例对话不会跟着改",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                // 闲聊侧称呼：群聊、圆桌里的角色卡统一用这一个称呼
                item {
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        "闲聊群、圆桌",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editingChatRoomAddress ?: "",
                            onValueChange = { editingChatRoomAddress = it },
                            label = { Text("角色对我的称呼") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            shape = RectangleShape, border = BorderStroke(1.dp, WoodBorder),
                            enabled = editingChatRoomAddress != null,
                            onClick = {
                                chatViewModel.setChatRoomAddress(editingChatRoomAddress.orEmpty().trim().ifBlank { "小刘鸭" })
                                // 同大肥鱼块：先关弹窗再弹 snackbar，否则被对话框遮住
                                onDismiss()
                                uiScope.launch { snackbarHostState.showSnackbar("已生效") }
                            }
                        ) { Text("保存") }
                    }
                    Text(
                        "群里所有角色对你的称呼，与大肥鱼的称呼互不影响",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                // 查看记忆（人设固定为内置大肥鱼，不提供换卡）
                item {
                    TextButton(onClick = onShowMemory) {
                        Text("查看记忆")
                    }
                }
                // MCP 工具入口（T4）：大肥鱼分区末尾（onShowMemory 先例同款 TextButton 项），
                // 子屏路由由组合壳 ChatListScreen 的 onOpenMcp 承接
                item {
                    TextButton(onClick = onOpenMcp) {
                        Text("MCP 工具")
                    }
                }
                // 请求诊断转储（审计 S4）：默认关——明文完整对话落盘属隐私敏感行为；
                // 开关改动自下一条消息生效（设置快照随链刷新，无需重启）
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    val diagDumpEnabled by chatViewModel.diagDumpEnabled.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("请求诊断转储", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "开启后每次请求把完整对话内容明文写入 last_request.json（诊断用）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = diagDumpEnabled,
                            onCheckedChange = { chatViewModel.setDiagDumpEnabled(it) }
                        )
                    }
                }
                // 生成保屏（审计 P1-6）：前台流式生成期间不熄屏（keepScreenOn，随组合
                // 自动清理），默认开；后台续流由 PARTIAL 唤醒锁负责、不受此开关影响
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    val keepScreenOnEnabled by chatViewModel.keepScreenOnEnabled.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("生成时保持屏幕常亮", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "前台生成期间不自动熄屏；关闭后交还系统熄屏节奏",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = keepScreenOnEnabled,
                            onCheckedChange = { chatViewModel.setKeepScreenOnEnabled(it) }
                        )
                    }
                }
                // 隐私说明
                item {
                    Text(
                        "隐私：所有对话与数据仅保存在本设备，应用不收集任何信息",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { viewModel.fetchModels() },
                    enabled = !isFetchingModels
                ) {
                    Text(if (isFetchingModels) "获取中…" else "刷新")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}
