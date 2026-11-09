package dev.liuyouwu.familiar.ui.screen.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.liuyouwu.familiar.core.llm.model.MessageMeta
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.skills.TypeInferer
import dev.liuyouwu.familiar.ui.component.AttachmentPreview
import dev.liuyouwu.familiar.ui.component.ChatInputBar
import dev.liuyouwu.familiar.ui.component.MessageBubble
import dev.liuyouwu.familiar.ui.component.TypingDots
import dev.liuyouwu.familiar.ui.model.MessageRenderModel
import dev.liuyouwu.familiar.ui.model.UiMessage
import dev.liuyouwu.familiar.pet.Kind
import dev.liuyouwu.familiar.pet.PetSkinStore
import dev.liuyouwu.familiar.pet.rememberSkinBitmap
import dev.liuyouwu.familiar.ui.theme.PaperLight
import dev.liuyouwu.familiar.ui.theme.pixelEdgeFade
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.PixelPanel
import dev.liuyouwu.familiar.ui.theme.PixelTopBar
import dev.liuyouwu.familiar.ui.theme.pixelSecondaryAlpha
import dev.liuyouwu.familiar.ui.theme.StarGold
import dev.liuyouwu.familiar.util.parseColorHex
import dev.liuyouwu.familiar.util.persona.DefaultPersonaCards
import dev.liuyouwu.familiar.viewmodel.ChatViewModel
import dev.liuyouwu.familiar.viewmodel.VoiceState
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    conversationId: Long,
    onBack: () -> Unit,
    onEditChatRoom: ((Long) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    // 消息列表（Agent 内核 P3 T9）：块渲染模型流——getAgentMessages（含读时规范化，
    // 聊天室旧链行同经此读出）× segments 列，工具卡跨行配对在流内完成
    val uiMessages by remember(conversationId) {
        viewModel.getUiMessages(conversationId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // Check if this is a chat room
    val isChatRoom = remember(conversationId) { mutableStateOf(false) }
    // 会话类型判定源（P3 T9 统一）：skillId 列直取 + TypeInferer 兜底（VM.skillIdOf
    // 同口径）——FamiliarKind/Eli5Prompts/tutorConfig 判定全部退役
    val skillId = remember(conversationId) { mutableStateOf<String?>(null) }
    // 头像数据源：大肥鱼按皮肤 id 取位图（蓝/橘）；聊天室角色走首字色块（items 内按消息算）
    val petColor = viewModel.petColor.collectAsState(initial = "orange").value
    // 皮肤 head 链位图（head→avatar→idle 首帧；选肤失败回落 blue 链，链内闭合）
    val fishAvatarBitmap = rememberSkinBitmap(petColor, Kind.HEAD)
    // 占位底色：位图双源皆失时 MessageBubble 出 accent 底色框，不出空白（spec §5 P0-2 修订）
    val fishAccentColor = remember(petColor) {
        (PetSkinStore.get(petColor) ?: PetSkinStore.get("blue"))?.accent?.let { parseColorHex(it) }
    }
    // 鱼名单一来源：与 SkillHost.buildFamiliar 的缺省卡同名（DefaultPersonaCards）。
    // remember(petColor)：personaOf 现扫皮肤目录，裸调会在流式高频重组期间逐重组扫盘
    val fishName = remember(petColor) { DefaultPersonaCards.personaOf(PetSkinStore.get(petColor)).name }
    val isFamiliarConv = skillId.value == TypeInferer.FAMILIAR
    val isEli5Conv = skillId.value == TypeInferer.ELI5
    val isClassroomConv = skillId.value == TypeInferer.CLASSROOM
    // 顶栏主标题：会话名（模型名退居副标题）。
    // 改为 Room Flow 订阅：标题在 DB 里被首轮后的自动命名更新时，顶栏立即刷新，
    // 根治之前"消息数变化才回读一次"的时序问题。
    // remember(conversationId) 固定 Flow 实例：流式期间高频重组时避免反复取消/重建 Room 收集
    val conversation by remember(conversationId) {
        viewModel.observeConversation(conversationId)
    }.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(conversationId) {
        val conv = viewModel.getConversationById(conversationId)
        isChatRoom.value = conv?.isChatRoom == true
        skillId.value = conv?.let { viewModel.skillIdOf(it) }
    }

    LaunchedEffect(conversationId) {
        // 收桌切换缝：先记账（prev→补跑自动小结判定），再做瞬态复位
        viewModel.onConversationSwitched(conversationId)
        // 单实例化后瞬态状态跨会话共享，切换会话先复位（复核 Minor-4）
        viewModel.resetForConversationSwitch(conversationId)
        viewModel.loadConversationPrompt(conversationId)
        // fetchModels 不在这里重复调用：ChatViewModel 单实例化后 init 已拉取（审计 P3-7）
    }

    // 收桌退出挂点（spec §2）：系统返回键与顶栏返回同一 lambda——先判定自动小结再退出。
    // maybeAutoSummarize 内部自筛圆桌会话/节流/单飞，非圆桌零开销直通 onBack()。
    BackHandler {
        viewModel.maybeAutoSummarize(conversationId)
        onBack()
    }

    // 流式状态分组（审计 A3 → A5 下沉 VM）：isStreaming/text/reasoning/toolStatus
    // 四流合一的 StreamingState 由 ChatViewModel 聚合产出，本屏单流收集
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val attachments by viewModel.selectedAttachments.collectAsStateWithLifecycle()
    val replyingTo by viewModel.replyingTo.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsStateWithLifecycle()
    // 联网搜索进行中的文案（"正在搜索：xxx"）：非空时替换"生成中"指示，让学生知道课堂在查资料
    val searchStatus by viewModel.searchStatus.collectAsStateWithLifecycle()
    // 流式发送者桥（P4 T4）：SessionState.streamingSender——聊天室/圆桌角色 run 的
    // 盖章位。列表尾流式泡据此挂角色名/色徽章（旧链占位行+发送者徽章的恢复形态）
    val streamingSender by viewModel.streamingSender.collectAsStateWithLifecycle()
    // 容量口径同源桥（评审 P0-1；T5 起六类型统一）：SessionState.contextBreakdown
    // ——消费点已随对话设置框拆入 ChatDialogs.kt（对话框内部 collect）
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val autoReadEnabled by viewModel.autoReadEnabled.collectAsStateWithLifecycle()
    // 生成用量分组（审计 A3 → A5 下沉 VM）：tps/上下文占用/生成 token/缓存命中
    // 四流合一的 StreamStats 由 ChatViewModel 聚合产出，本屏单流收集；>0 判定
    // 在 VM 合并处转 nullable，与原各 UI 位的 `> 0` 判定同口径
    val streamStats by viewModel.streamStats.collectAsStateWithLifecycle()
    val currentModel by viewModel.currentModel.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    // videoFrameCount/maxContext/chatRoomMaxReplies 消费点已随对话设置框拆入 ChatDialogs.kt
    // 排队追问横幅数据源（P-steer UI 面）：此前排队纯落库零反馈
    val queuedFollowUp by viewModel.pendingFollowUpUi.collectAsStateWithLifecycle()
    // familiar 拟人节奏：打字指示（精灵名）与分段曝光态（id → 已曝光段数）
    val familiarTypingName by viewModel.familiarTypingName.collectAsStateWithLifecycle()
    val familiarReveal by viewModel.familiarReveal.collectAsStateWithLifecycle()
    // 大肥鱼窗口：菜单与人设相关项按此分流（编辑提示词/深度思考是普通窗口专属）
    val familiarHeader by viewModel.familiarHeader.collectAsStateWithLifecycle()
    val isFamiliarChat = familiarHeader.conversationId == conversationId
    var showMemoryDialog by rememberSaveable { mutableStateOf(false) }

    // 工具审批桥（审计 S2）：高危工具（http_get/剪贴板/open_url）执行前经
    // ToolGateKeeper 挂起等确认——本 collect 即"前台监听者在场"的判定源
    // （pendingApproval.subscriptionCount>0）；离开本屏即撤订，闸门自动落无前台拒绝
    val pendingToolApproval by viewModel.toolGate.pendingApproval.collectAsStateWithLifecycle()

    // Voice input state
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val voiceResult by viewModel.voiceResult.collectAsStateWithLifecycle()

    // 生成保屏（审计 P1-6，替代废弃亮屏唤醒锁的前台半边）：流式生成中
    // 且设置开（默认开）→ 窗口 keepScreenOn；随组合/开关翻转自动清理（onDispose 置 false），
    // 离屏或收线交还系统熄屏节奏。后台半边由 AgentSessionController 的 PARTIAL 锁负责
    val keepScreenOnEnabled by viewModel.keepScreenOnEnabled.collectAsStateWithLifecycle()
    val isStreaming = streamingState.isStreaming
    val keepView = LocalView.current   // LocalView.current 是 @Composable 读——effect 体外取
    DisposableEffect(isStreaming && keepScreenOnEnabled) {
        keepView.keepScreenOn = isStreaming && keepScreenOnEnabled
        onDispose { keepView.keepScreenOn = false }
    }

    // 草稿与对话框开关全部 rememberSaveable：旋转/进程重建不丢输入（审计 §1-5）
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPromptDialog by rememberSaveable { mutableStateOf(false) }
    var editingPrompt by rememberSaveable { mutableStateOf("") }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    // 换模型重试：像素模型单选框开关（列表与设置面板同一来源）
    var showRetryModelPicker by rememberSaveable { mutableStateOf(false) }
    // 顶栏模型名点击 → 直接切模型（最常见的意图就一个：换个模型，不该绕道设置弹窗）
    var showModelSwitcher by rememberSaveable { mutableStateOf(false) }
    // 导出对话：格式选择框（Markdown / JSON / 纯文本分享）开关
    var showExportChooser by rememberSaveable { mutableStateOf(false) }
    // 结构化导出 SAF 接线（审计 A3 拆出 → ChatExportActions.kt）：launcher×2 +
    // payload 暂存收拢于此，磁盘 IO 在 ExportController.writeExportPayload
    val exportActions = rememberChatExportActions(viewModel, conversationId, snackbarHostState)

    // F4: Camera capture temp URI
    // rememberSaveable（审计 P2-12）：拍照期间旋转/进程重建后 URI 不丢，照片不再静默消失
    var cameraImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { viewModel.addImageAttachment(it) }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && cameraImageUri != null) {
            // Permission granted, launch camera
            cameraImageUri?.let {
                try {
                    cameraLauncher.launch(it)
                } catch (_: ActivityNotFoundException) {
                    // 部分设备无相机应用，静默失败会让用户以为点按无效
                    coroutineScope.launch { snackbarHostState.showSnackbar("未找到可用相机应用") }
                }
            }
        } else if (!granted) {
            // 权限被拒要有反馈，不再静默
            coroutineScope.launch { snackbarHostState.showSnackbar("需要相机权限") }
        }
    }

    // Voice input: handle transcription result from server
    LaunchedEffect(voiceResult) {
        val result = voiceResult
        if (result != null) {
            if (result.isNotEmpty()) {
                inputText = result
            }
            // 空结果 = 服务端转写不可用，ViewModel 已弹出说明 snackbar，这里只切系统识别
            viewModel.clearVoiceResult()
        }
    }

    // Voice input: Android speech recognition via Intent (more reliable than SpeechRecognizer API)
    val speechResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS
            )?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                inputText = text
            }
        }
    }

    // Voice input: permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (viewModel.serverWhisperAvailable == false) {
                val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    speechResultLauncher.launch(intent)
                }
            } else {
                viewModel.startVoiceRecording()
            }
        } else {
            // 权限被拒要有反馈，不再静默
            coroutineScope.launch { snackbarHostState.showSnackbar("语音输入需要麦克风权限") }
        }
    }

    fun handleVoiceInput() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        when {
            voiceState == VoiceState.RECORDING -> {
                viewModel.stopVoiceRecordingAndTranscribe()
            }
            viewModel.serverWhisperAvailable == false -> {
                val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    speechResultLauncher.launch(intent)
                } else {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("设备没有语音识别应用，用不了语音输入")
                    }
                }
            }
            else -> {
                viewModel.startVoiceRecording()
            }
        }
    }

    // F6: Slash command popup
    var showSlashMenu by rememberSaveable { mutableStateOf(false) }
    val slashCommands = remember {
        listOf(
            "/clear" to "清除当前对话所有消息",
            "/compact" to "压缩对话上下文"
        )
    }
    // /clear 二次确认（与删除会话同标准）
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    // Auto-scroll
    var autoScroll by remember { mutableStateOf(true) }
    var wasLoading by remember { mutableStateOf(false) }

    // 滚顶分页（审计 P1-4）：首可见项贴近列表头时触发 keyset 向上加载（loadMoreBefore
    // 内部防重/探底守卫，重复触发零副作用）；窗口不足 200 条的会话首次触发即探底
    val nearTop by remember { derivedStateOf { listState.firstVisibleItemIndex < 3 } }
    LaunchedEffect(conversationId) {
        snapshotFlow { nearTop }.collect { if (it) viewModel.loadMoreBefore(conversationId) }
    }

    // 附件惰性加载桥（审计 P1-4）：气泡渲染位回调 → VM → repo（LruCache 8 条）
    val loadAttachmentById: suspend (String) -> dev.liuyouwu.familiar.ui.model.AttachmentPayload? =
        remember { { id -> id.toLongOrNull()?.let { viewModel.loadAttachmentPayload(it) } } }

    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(isNearBottom) { if (isNearBottom) autoScroll = true }

    // 流式自动滚动让位用户：只有用户真的拖动过（区别于程序滚动），
    // 滚动停止时若已离底（canScrollForward）则暂停自动滚，否则恢复
    var userDragged by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                userDragged = true
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && userDragged) {
            userDragged = false
            autoScroll = !listState.canScrollForward
        }
    }

    // Track loading state changes - scroll to bottom when streaming completes
    LaunchedEffect(streamingState.isStreaming) {
        if (wasLoading && !streamingState.isStreaming && autoScroll) {
            // Streaming just finished, ensure we scroll to bottom（用户已上滑让位则不打扰）
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                kotlinx.coroutines.delay(100) // Small delay to ensure UI is updated
                listState.animateScrollToItem(total - 1)
            }
        }
        wasLoading = streamingState.isStreaming
    }

    var lastUserMsgCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(uiMessages.size) {
        val userCount = uiMessages.count { it.isUser }
        if (userCount > lastUserMsgCount) { lastUserMsgCount = userCount; autoScroll = true }
    }

    // 自动滚动（审计 P4）：layoutInfo 贴底跟随——流式文本/思考/分段曝光不再作为
    // key（协程不再每节流帧重启）。snapshotFlow 派生（贴底性, 总数, 末项底边）：
    // 流式增长只涨 ghost 气泡高度，index 与 totalItemsCount 均不变，须靠末项底边
    // 值变化驱动重发（Pair 版派生值会被 snapshotFlow 内置 distinctUntilChanged
    // 吞掉——贴底后首帧起即停跟，冒烟态 1 不成立）；userDragged 让位期间不滚，
    // 避免拖拽帧自身触发 emission 被逐帧拽回
    LaunchedEffect(autoScroll) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(
                last != null && last.index == info.totalItemsCount - 1,
                info.totalItemsCount,
                last?.let { it.offset + it.size } ?: 0
            )
        }.collect { (atBottom, total, _) ->
            if (autoScroll && atBottom && total > 0 && !userDragged) {
                listState.scrollToItem(total - 1)
            }
        }
    }

    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it); viewModel.clearError() }
    }

    // Settings dialog (model switch + video frames + max context)
    // 对话设置（审计 A3 拆出 → ChatDialogs.kt）：仅对话框消费的流由对话框内部
    // collect；与屏体共用的 state（模型列表/当前模型/缓存命中/会话类型）以现值传入
    if (showSettingsDialog) {
        ChatSettingsDialog(
            isChatRoom = isChatRoom.value,
            currentModel = currentModel,
            availableModels = availableModels,
            lastCacheHit = streamStats.cachedTokens,
            videoFrameCount = viewModel.videoFrameCount,
            maxContext = viewModel.maxContext,
            sessionContextBreakdown = viewModel.sessionContextBreakdown,
            chatRoomMaxReplies = viewModel.chatRoomMaxReplies,
            onDismiss = { showSettingsDialog = false },
            onModelSelected = { model ->
                viewModel.switchModel(model)
                showSettingsDialog = false  // Close dialog after switching
            },
            onVideoFrameCountSaved = { count -> viewModel.updateVideoFrameCount(count) },
            onMaxContextSaved = { ctx -> viewModel.updateMaxContext(ctx) },
            onChatRoomMaxRepliesChanged = { count -> viewModel.updateChatRoomMaxReplies(count) },
        )
    }

    Scaffold(
        topBar = {
            // 主标题=会话名，副标题=模型 + token 用量；生成中附带速率（思考段也计入），
            // 空闲显示上次请求的真实上下文占用（lastUsage 精确值，非估算）
            // （审计 A3：tokenCount/tokensPerSecond/lastContextTokens/lastCacheHit 四流
            // 已并入 streamStats——completionTokens=生成数、promptTokens=上下文占用）
            val stats = streamStats
            val tokenInfo = if (streamingState.isStreaming) {
                buildString {
                    stats.completionTokens?.let { append("$it tokens") }
                    stats.tps?.let { append(" · ${String.format("%.1f", it)} t/s") }
                }
            } else if (stats.promptTokens != null) "上下文 ${stats.promptTokens} tokens" else ""
            // 缓存命中率：llama.cpp timings / DeepSeek / OpenAI 系用量里带，本地与云端都有意义
            val cacheSuffix = stats.cachedTokens?.let { " · 缓存命中${it}%" } ?: ""
            // 模型名带 ⌄ 下拉暗示：可点直切模型（曾经毫无可见提示，开发者都发现不了）
            val subtitle = listOfNotNull(
                currentModel.takeIf { it.isNotBlank() }
                    ?.let { dev.liuyouwu.familiar.util.modelDisplayName(it) + " ⌄" },
                (tokenInfo + cacheSuffix).takeIf { it.isNotBlank() }
            ).joinToString("　")
            PixelTopBar(
                // 标题实时跟 DB：空态/未命名时回退"对话"
                title = conversation?.title.orEmpty().ifBlank { "对话" },
                subtitle = subtitle,
                // 顶栏模型名可点：直开模型选择器（选完立即落库生效，不重连不进设置）
                onSubtitleClick = { showModelSwitcher = true },
                navigationIcon = {
                    PixelIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = {
                            // 与 BackHandler 同一 lambda：先判定收桌小结再退出（spec §2）
                            viewModel.maybeAutoSummarize(conversationId)
                            onBack()
                        },
                        tint = PaperLight
                    )
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }

                    // Stop button always visible when loading
                    if (streamingState.isStreaming) {
                        PixelIconButton(
                            icon = Icons.Default.Stop,
                            contentDescription = "停止生成",
                            onClick = { viewModel.stopGeneration() },
                            tint = PaperLight
                        )
                    }

                    // 朗读/停止朗读最后一条 AI 回复
                    if (!streamingState.isStreaming && uiMessages.any { it.role == Role.ASSISTANT && it.fullText.isNotBlank() }) {
                        PixelIconButton(
                            icon = Icons.Default.VolumeUp,
                            contentDescription = if (isSpeaking) "停止朗读" else "朗读回复",
                            onClick = { viewModel.toggleSpeakLastReply(conversationId) },
                            tint = if (isSpeaking) StarGold else PaperLight
                        )
                    }

                    Box {
                        PixelIconButton(
                            icon = Icons.Default.MoreVert,
                            contentDescription = "更多",
                            onClick = { showMenu = true },
                            tint = PaperLight
                        )

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                        // Chat room edit
                        if (isChatRoom.value && onEditChatRoom != null) {
                            DropdownMenuItem(
                                text = { Text("聊天室设置") },
                                onClick = {
                                    showMenu = false
                                    onEditChatRoom(conversationId)
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                            )
                        }
                        // Regenerate（还需存在 user 消息：压缩后只剩 assistant 时是死按钮）
                        if (!streamingState.isStreaming && uiMessages.any { it.role == Role.ASSISTANT } &&
                            uiMessages.any { it.isUser }
                        ) {
                            DropdownMenuItem(
                                text = { Text("重新生成") },
                                onClick = {
                                    showMenu = false
                                    viewModel.regenerateLastResponse(conversationId)
                                },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                            )
                        }
                        // 大肥鱼的记忆（familiar 专属：记忆是她的命根子，随时可看可拷）
                        if (isFamiliarChat) {
                            DropdownMenuItem(
                                text = { Text("大肥鱼的记忆") },
                                onClick = {
                                    showMenu = false
                                    showMemoryDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null) }
                            )
                        }
                        // Thinking toggle（普通窗口专属：大肥鱼的思考由拟人引擎内部处理）
                        if (!isFamiliarChat) DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("深度思考")
                                    if (thinkingEnabled) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            onClick = {
                                showMenu = false
                                viewModel.toggleThinking()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = if (thinkingEnabled) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        )
                        // Auto read toggle
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("自动朗读回复")
                                    if (autoReadEnabled) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            onClick = {
                                showMenu = false
                                viewModel.toggleAutoRead()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = if (autoReadEnabled) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        )
                        // Edit prompt（普通窗口专属；大肥鱼的人设由拟人引擎固定，不开放编辑）
                        if (!isFamiliarChat) DropdownMenuItem(
                            text = { Text("编辑提示词") },
                            onClick = {
                                showMenu = false
                                editingPrompt = systemPrompt
                                showPromptDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        // Export：先弹像素格式选择框（Markdown / JSON / 纯文本分享）
                        DropdownMenuItem(
                            text = { Text("导出对话") },
                            onClick = {
                                showMenu = false
                                showExportChooser = true
                            },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                        )
                        // Settings
                        DropdownMenuItem(
                            text = { Text("对话设置") },
                            onClick = {
                                showMenu = false
                                showSettingsDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) }
                        )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (streamingState.isStreaming) {
                // 生成中指示行：会话工具进度（"🔍 正在搜索：xxx"，新引擎桥）优先，
                // 旧链搜索文案（searchStatus）次之，默认"…生成中"
                Text(
                    text = streamingState.toolStatus ?: searchStatus ?: "…生成中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 圆桌调度锚点（首条「（圆桌话题：…）」user 消息）只在库与模型上下文里存在，
            // 界面不渲染——用户没说过这句话，显示出来就是一条莫名指令
            //（旧链 DB 占位行过滤随 streamingMessageId 死机制一并删除：
            // T10 切新引擎后不再写占位行，familiar 分段曝光行不受影响）
            val visibleUiMessages = uiMessages.filterNot { msg ->
                msg.isUser && msg.fullText.startsWith("（圆桌话题：") && msg.fullText.endsWith("）")
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = visibleUiMessages, key = { it.id }) { message ->
                    val isLastAssistant = message.role == Role.ASSISTANT &&
                        uiMessages.lastOrNull()?.id == message.id && !streamingState.isStreaming

                    // familiar 拟人节奏：切段只影响渲染——segments != null 的消息按
                    // 已曝光段数逐段渲染气泡；活跃接收时由 familiarReveal 推进曝光，
                    // 其余（重进会话/进程重建/非动画路径）默认全曝光
                    val familiarSegments = message.segments
                    // 头像与名字：大肥鱼=皮肤位图+鱼名（调用方传兜底名）；聊天室=首字色块+角色名
                    // 头像三分类（大肥鱼/导师课堂/聊天室互不混用）：
                    // 大肥鱼=皮肤 head 链位图（head→avatar→idle 首帧，spec §4）；导师课堂老师=首字色块「老」；聊天室成员=首字色块（角色色）
                    val msgAvatarBitmap =
                        if (isFamiliarConv && message.role == Role.ASSISTANT) fishAvatarBitmap else null
                    val msgAvatarColor =
                        if (isFamiliarConv && message.role == Role.ASSISTANT) fishAccentColor else null
                    val msgFallbackName =
                        if (isFamiliarConv && message.role == Role.ASSISTANT) fishName else null
                    // 讲人话（eli5）旧行为同课堂（tutorConfig 判定期即带「老」），判定换源后保留
                    val msgAvatarLetter = when {
                        message.role != Role.ASSISTANT -> null
                        isFamiliarConv -> null
                        message.senderName != null -> message.senderName!!.first().toString()
                        isClassroomConv || isEli5Conv -> "老"
                        else -> null
                    }

                    if (familiarSegments != null) {
                        val reveal = familiarReveal
                        val revealed = reveal
                            ?.takeIf { it.first == message.id.toLongOrNull() }
                            ?.second ?: familiarSegments.size
                        Column {
                            familiarSegments.take(revealed).forEachIndexed { i, seg ->
                                val isFinalSegment = i == familiarSegments.size - 1
                                MessageBubble(
                                    // 每段复用 MessageBubble 渲染；token 用量小字只挂最后一段。
                                    // 首段思考头剥离（思考由 UiMessage.thinking 单独承载，
                                    // 段内不得再含标签字面量）；思考盒/工具卡只挂首段，
                                    // 不随段重复
                                    message = message.copy(
                                        textBlocks = listOf(
                                            if (i == 0) MessageRenderModel.stripThinkHead(seg) else seg
                                        ),
                                        thinking = if (i == 0) message.thinking else null,
                                        toolBlocks = if (i == 0) message.toolBlocks else emptyList(),
                                        segments = null,
                                        meta = if (isFinalSegment) message.meta else MessageMeta.NONE,
                                    ),
                                    showThinking = thinkingEnabled,
                                    isLastAssistant = isLastAssistant && isFinalSegment,
                                    avatarBitmap = msgAvatarBitmap,
                                    avatarLetter = msgAvatarLetter,
                                    onLoadAttachment = loadAttachmentById,
                                    // 占位色只在位图 null 时顶上（位图成功时 accent 不得透出立绘透明边，复审 P1-B）
                                    avatarColor = msgAvatarColor?.takeIf { msgAvatarBitmap == null } ?:
                                        message.senderColorHex?.let {
                                            dev.liuyouwu.familiar.util.parseColorHex(it)
                                    },
                                    fallbackSenderName = msgFallbackName,
                                    onCopy = { text ->
                                        val clipboard =
                                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
                                    },
                                    onRegenerate = if (isLastAssistant && isFinalSegment &&
                                        message.senderName == null
                                    ) {
                                        { viewModel.regenerateLastResponse(conversationId) }
                                    } else null,
                                    onContinue = if (isLastAssistant && isFinalSegment &&
                                        message.senderName == null
                                    ) {
                                        { viewModel.continueGeneration(conversationId) }
                                    } else null,
                                    onRetryWithModel = if (isLastAssistant && isFinalSegment &&
                                        message.senderName == null
                                    ) {
                                        { showRetryModelPicker = true }
                                    } else null,
                                    // 回复引用整条原文：分段只是渲染切分，不改变消息本体
                                    onReply = { viewModel.setReplyingTo(message) }
                                )
                            }
                            // 曝光未完（活跃接收的段间隙）：打字指示续上；
                            // 名字兜底与 SkillHost 缺省卡同一来源（DefaultPersonaCards，消除字面量重复）
                            if (revealed < familiarSegments.size) {
                                TypingDots(text = familiarTypingName ?: fishName)
                            }
                        }
                    } else {
                        MessageBubble(
                            message = message, showThinking = thinkingEnabled,
                            isLastAssistant = isLastAssistant,
                                    avatarBitmap = msgAvatarBitmap,
                                    avatarLetter = msgAvatarLetter,
                                    onLoadAttachment = loadAttachmentById,
                                    // 占位色只在位图 null 时顶上（位图成功时 accent 不得透出立绘透明边，复审 P1-B）
                                    avatarColor = msgAvatarColor?.takeIf { msgAvatarBitmap == null } ?:
                                        message.senderColorHex?.let {
                                            dev.liuyouwu.familiar.util.parseColorHex(it)
                                    },
                                    fallbackSenderName = msgFallbackName,
                            onEdit = if (message.isUser) { msgId, newContent ->
                                msgId.toLongOrNull()?.let { id ->
                                    viewModel.editMessageAndTruncate(conversationId, id, newContent)
                                }
                            } else null,
                            onCopy = { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
                            },
                            // AI 同学消息（带发送者名的 assistant）不提供重新生成/继续生成：
                            // 重新生成会让导师重答，出现两条连续导师回复
                            onRegenerate = if (isLastAssistant && message.senderName == null) {
                                { viewModel.regenerateLastResponse(conversationId) }
                            } else null,
                            onContinue = if (isLastAssistant && message.senderName == null) {
                                { viewModel.continueGeneration(conversationId) }
                            } else null,
                            // 换模型重试：只挂末条助手消息（与重新生成同门槛，聊天室 AI 同学除外），
                            // 点击后由下方 ChatRetryModelPickerDialog（ChatDialogs.kt）接管
                            onRetryWithModel = if (isLastAssistant && message.senderName == null) {
                                { showRetryModelPicker = true }
                            } else null,
                            onReply = { viewModel.setReplyingTo(it) }
                        )
                    }
                }

                // 幽灵气泡（P3 T9，key="ghost"）：非聊天室流式正文/思考的列表尾渲染位。
                // - familiar 例外（评审 P1-4）：流式抑制期由打字指示顶替（等价旧 :889-891），
                //   不渲染正文气泡；曝光相（占位行已带 segments）由段间隙的段内指示接管，
                //   此处不再重复出点（曝光宿主可见性旧查 streamingMessageId 占位行 id，
                //   该流恒 null 已死，随本批删除——打字指示恒出）
                // - 聊天室/圆桌（P4 T4 流式逐字泡恢复）：角色 run 流式（streamingSender
                //   非空）时同样走列表尾幽灵，带角色名/角色色徽章（旧链"占位行+发送者
                //   徽章流式泡"的恢复形态——T3 起 Orchestrator 回合不再写占位行，整回合
                //   仅指示行的回归由此修复）；等待/调度窗口（sender 空）仍只显示指示行，
                //   与旧链占位行插入前同相
                // - T9 期四类型仍走旧链：DB 占位行已在 visibleUiMessages 隐藏，
                //   幽灵即唯一流式泡（等待相正文为空时 MessageBubble 显示"思考中..."，与旧占位泡等价）
                if (streamingState.isStreaming && (!isChatRoom.value || streamingSender != null)) {
                    if (isFamiliarConv) {
                        val typingName = familiarTypingName
                        if (typingName != null) {
                            item(key = "ghost") {
                                TypingDots(text = typingName)
                            }
                        }
                    } else {
                        // 角色流式（聊天室/圆桌）：名字首字色块 + 角色名徽章（与成员气泡
                        // 同形）；四类型 sender 恒 null——课堂/讲人话保持「老」兜底
                        val ghostSenderName = streamingSender?.name?.takeIf { it.isNotBlank() }
                        item(key = "ghost") {
                            MessageBubble(
                                message = UiMessage(
                                    id = "ghost",
                                    role = Role.ASSISTANT,
                                    senderName = ghostSenderName,
                                    senderColorHex = streamingSender?.colorHex,
                                ),
                                isStreaming = true,
                                streamingText = streamingState.text,
                                streamingReasoning = streamingState.reasoning,
                                showThinking = thinkingEnabled,
                                avatarLetter = ghostSenderName?.let { it.first().toString() }
                                    ?: if (isClassroomConv || isEli5Conv) "老" else null,
                                avatarColor = streamingSender?.colorHex?.let {
                                    dev.liuyouwu.familiar.util.parseColorHex(it)
                                },
                            )
                        }
                    }
                }

                if (uiMessages.isEmpty() && !streamingState.isStreaming) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                                .padding(top = 80.dp)
                        ) {
                            PixelPanel(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "开始对话吧",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "在下方输入框敲下第一句话",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "bottom_anchor") { Box(modifier = Modifier.height(1.dp)) }
            }

            // 自动滚动被用户上滑暂停时，提供像素风"回到底部"出口
            if (!autoScroll) {
                PixelPanel(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    onClick = {
                        coroutineScope.launch {
                            autoScroll = true
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) listState.animateScrollToItem(total - 1)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null, // 装饰性：旁有"回到底部"文字
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "回到底部",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            }

            // Reply preview bar
            val currentReplyingTo = replyingTo
            if (currentReplyingTo != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Reply,
                            contentDescription = null, // 装饰性：旁有"回复…"文字
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "回复 ${if (currentReplyingTo.role == "user") "自己" else "AI"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val previewText = currentReplyingTo.content.take(50)
                            Text(
                                previewText + if (currentReplyingTo.content.length > 50) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { viewModel.setReplyingTo(null) }) {
                            Icon(Icons.Default.Close, contentDescription = "取消")
                        }
                    }
                }
            }

            AttachmentPreview(attachments = attachments, onRemove = { index ->
                val attachment = attachments.getOrNull(index)
                if (attachment != null) viewModel.removeAttachment(attachment)
            })

            // F6: Slash command popup
            if (showSlashMenu) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "命令",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        slashCommands.forEach { (cmd, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        inputText = cmd + " "
                                        showSlashMenu = false
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cmd,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            // 快捷指令条（P3 T9 换源）：skill JSON ui.quickCommands（VM.quickCommandsFor，
            // 与 SkillHost.quickCommands 同式；判定同源 skillId）——eli5/classroom 各自带
            // 命令，chat/familiar/chatroom/roundtable 为空即不显示（取代原
            // Eli5Prompts.COMMANDS / TutorPrompts.COMMANDS 两条硬编码行）
            val quickCommands = remember(
                conversation?.id, conversation?.skillId, conversation?.tutorConfig,
                conversation?.isChatRoom,
            ) {
                conversation?.let { viewModel.quickCommandsFor(it) }.orEmpty()
            }
            if (quickCommands.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()).pixelEdgeFade()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickCommands.forEach { cmd ->
                        androidx.compose.material3.SuggestionChip(
                            onClick = { viewModel.sendMessage(conversationId, cmd.command) },
                            enabled = !streamingState.isStreaming,
                            label = { Text(cmd.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // P-steer 双语义（spec §4.2/§5）：四类单体生成中提示插话/排队；聊天室/圆桌维持原样。
            // skillId != null 门（评审 P2-7）：判定加载前的 transient 真窗不亮双语义提示
            val fourTypeConv = !isChatRoom.value && skillId.value != null &&
                skillId.value != TypeInferer.ROUNDTABLE
            // 排队追问横幅：长按发送排队后的唯一可见反馈——此前排队只落库，界面毫无
            // 显示（2026-10-31 实测反馈「排队用户都看不到」）。仅本会话在排队时显示；
            // 「取消」只撤自动兜答意图，已发出的消息保留（与停止同语义）
            queuedFollowUp?.takeIf { it.conversationId == conversationId }?.let { q ->
                PixelPanel(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val preview = q.text.take(12) + if (q.text.length > 12) "…" else ""
                        Text(
                            text = "排队追问：$preview · 回复完自动发",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.cancelQueuedFollowUp() }
                        ) { Text("取消", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            ChatInputBar(
                value = inputText,
                onValueChange = { newText ->
                    inputText = newText
                    // F6: Show slash menu when input starts with /
                    showSlashMenu = newText.startsWith("/") && !newText.contains(" ") && newText.length <= 15
                },
                onSend = {
                    val text = inputText.trim()
                    if (text.isNotBlank() || attachments.isNotEmpty()) {
                        inputText = ""
                        showSlashMenu = false
                        keyboardController?.hide()

                        // F6: Handle slash commands（/clear 不可逆，先确认，与删除会话同标准）
                        when {
                            text == "/clear" -> showClearConfirm = true
                            text == "/compact" -> viewModel.compactConversation(conversationId)
                            else -> viewModel.sendMessage(conversationId, text)
                        }
                    }
                },
                // 生成中四类单体：placeholder 提示双语义；长按发送=排队追问
                // （queueFollowUp 空闲/聊天室/错配/未挂载自动降级普通发送）
                inputHint = if (streamingState.isStreaming && fourTypeConv) "生成中：发送=插话 · 长按发送=排队追问" else "输入消息…",
                onSendLongPress = if (fourTypeConv) { {
                    val t = inputText.trim()
                    if (t.isNotBlank()) {
                        inputText = ""
                        showSlashMenu = false   // "/"开头长按后残影菜单（评审 P2-6）
                        keyboardController?.hide()
                        viewModel.queueFollowUp(conversationId, t)
                    }
                    // 空文本长按静默无操作——与普通发送（可发附件-only）的分歧已知，登记接受
                } } else null,
                onImageSelected = { viewModel.addImageAttachment(it) },
                onFileSelected = { viewModel.addFileAttachment(it) },
                onVideoSelected = { viewModel.addVideoAttachment(it) },
                onCameraCapture = { _ ->
                    // F4: Camera capture - request permission first
                    val photoFile = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photoFile
                    )
                    cameraImageUri = uri
                    // Request camera permission, which will launch camera if granted
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                },
                // 生成中也允许发送 = 插话打断：取消当前流，用户消息入库后
                // 导师/角色读上下文回应（课堂插话提问的核心入口）
                isSendEnabled = true,
                hasAttachments = attachments.isNotEmpty(),
                isVoiceRecording = voiceState == VoiceState.RECORDING,
                isTranscribing = voiceState == VoiceState.TRANSCRIBING,
                onCancelVoice = { viewModel.cancelVoiceRecording() },
                onVoiceInput = { handleVoiceInput() }
            )
        }
    }

    if (showPromptDialog) {
        ChatPromptEditDialog(
            prompt = editingPrompt,
            onPromptChange = { editingPrompt = it },
            onDismiss = { showPromptDialog = false },
            onSave = { prompt ->
                viewModel.updateSystemPrompt(conversationId, prompt)
                showPromptDialog = false
            }
        )
    }

    // /clear 二次确认：样式与删除会话确认一致
    if (showClearConfirm) {
        ChatClearConfirmDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                showClearConfirm = false
                viewModel.clearConversation(conversationId)
            }
        )
    }

    // 工具审批对话框（审计 S2 → ChatDialogs.kt）：三键=拒绝/允许一次/总是允许。
    // pendingApproval 流的常驻订阅仍在上方（订阅计数=前台监听者在场判定源），
    // 此处只把解析后的请求值传给对话框
    val approval = pendingToolApproval
    if (approval != null) {
        ChatToolApprovalDialog(
            approval = approval,
            onRespond = { viewModel.toolGate.respond(it) }
        )
    }

    // 换模型重试：像素单选框，数据源与设置面板一致（ChatDialogs.kt 内剔除 embedding，空则回退全量）
    if (showRetryModelPicker) {
        ChatRetryModelPickerDialog(
            availableModels = availableModels,
            currentModel = currentModel,
            onDismiss = { showRetryModelPicker = false },
            onSelected = { model ->
                showRetryModelPicker = false
                // 重试用：删除末条助手回复并以所选模型重新生成
                viewModel.regenerateLastResponse(conversationId, modelOverride = model)
                coroutineScope.launch { snackbarHostState.showSnackbar("已用 $model 重试") }
            }
        )
    }

    // 顶栏切模型：选完立即落库生效（本机所有对话通用），并给明确反馈——
    // "换模型"是一等操作，不能藏在设置里让用户换不对（2026-10-27 设计复盘）
    if (showModelSwitcher) {
        ChatModelSwitcherDialog(
            availableModels = availableModels,
            currentModel = currentModel,
            onDismiss = { showModelSwitcher = false },
            onSelected = { model ->
                showModelSwitcher = false
                viewModel.switchModel(model)
                coroutineScope.launch { snackbarHostState.showSnackbar("已切换到 $model，下一条消息即用新模型") }
            }
        )
    }

    // 大肥鱼的记忆：查看 + 复制（清空入口只留在首页设置，聊天页不给误触机会）。
    // 对话框本体在 ChatDialogs.kt；读取（挂起点）与复制动作留在调用位，时序同拆分前
    if (showMemoryDialog) {
        var memoryText by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { memoryText = viewModel.readFamiliarMemory() }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        ChatMemoryDialog(
            memoryText = memoryText,
            onDismiss = { showMemoryDialog = false },
            onCopy = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(memoryText.orEmpty()))
                coroutineScope.launch { snackbarHostState.showSnackbar("已复制") }
            }
        )
    }

    // 导出对话：格式选择框（Markdown / JSON 走 SAF 另存为；纯文本分享沿用原 HTML 分享）——
    // 面板本体在 ChatDialogs.kt，SAF 接线在 ChatExportActions.kt
    if (showExportChooser) {
        ChatExportChooserDialog(
            onDismiss = { showExportChooser = false },
            onExportMarkdown = {
                showExportChooser = false
                exportActions.startStructuredExport("md")
            },
            onExportJson = {
                showExportChooser = false
                exportActions.startStructuredExport("json")
            },
            onSharePlainText = {
                showExportChooser = false
                viewModel.exportConversationHtml(conversationId)
            }
        )
    }
}
