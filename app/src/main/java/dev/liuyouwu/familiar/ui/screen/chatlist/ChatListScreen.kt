package dev.liuyouwu.familiar.ui.screen.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import dev.liuyouwu.familiar.ui.theme.GrassGreen
import dev.liuyouwu.familiar.ui.theme.PaperLight
import dev.liuyouwu.familiar.ui.theme.PixelDivider
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.PixelModeRow
import dev.liuyouwu.familiar.ui.theme.PixelBottomPanel
import dev.liuyouwu.familiar.ui.theme.PixelTopBar
import dev.liuyouwu.familiar.ui.theme.SkyBlue
import dev.liuyouwu.familiar.ui.theme.StarGold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 会话列表组合壳（P5 T6 三文件拆分后的挂载点）：
 * - [ChatListContent]：列表本体 + 搜索 + 精灵首页卡 + 技能参数入口；
 * - [SettingsDialog]（主设置弹窗）/[FamiliarMemoryDialogs]（记忆隐私弹窗群）/
 *   [PresetDialogs]（模板与角色预设弹窗群，含 [PresetDialogState] 状态簇）。
 * 壳只留：弹窗开关与跨弹窗状态、备份/恢复 launcher 与 snackbar、顶栏收纳菜单、
 * 新建对话模式面板与模板新建统一入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: dev.liuyouwu.familiar.viewmodel.ChatListViewModel,
    // 共享 Activity 级 ChatViewModel 单例（复核 I-1：单实例化后这里自建实例会变成
    // 僵尸实例——error 观察不到讲人话创建失败，init 还会重复 fetchModels）
    chatViewModel: dev.liuyouwu.familiar.viewmodel.ChatViewModel,
    // 共享 Activity 级 UpdateViewModel（审核 P1：目的地内自建实例与 MainActivity 的
    // DialogHost 不同对象，弹窗不可达）；由 AppNavigation 以 activity owner 创建传入
    updateViewModel: dev.liuyouwu.familiar.viewmodel.UpdateViewModel,
    onChatClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onNewChat: (Long) -> Unit,
    onNewChatRoom: () -> Unit = {},
    onNewTutor: () -> Unit = {},
    onNewEli5: () -> Unit = {},
    onNewFamiliar: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    // MCP 设置子屏路由（T4）：设置弹窗「MCP 工具」入口承接（AppNavigation 传导航）
    onOpenMcp: () -> Unit = {}
) {
    // ===== 备份 / 恢复 =====
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importBackup(it) } }
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBackupMessage()
        }
    }
    // 讲人话等会话创建失败的提示（chatViewModel 为导航层传入的共享单例，复核 I-1）
    val chatError by chatViewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(chatError) {
        chatError?.let {
            snackbarHostState.showSnackbar(it)
            chatViewModel.clearError()
        }
    }

    // ===== 主设置弹窗 / 记忆弹窗 =====
    var showModelSelector by remember { mutableStateOf(false) }
    var showFamiliarMemory by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    // ===== 预设弹窗群状态（模板选择/管理/增删改 + 角色预设编辑）=====
    val presetState = remember { PresetDialogState() }

    // ===== 从模板新建（统一入口）=====
    // 模板正文含 {{变量}} 占位符时先弹填充面板；确认后全部替换再创建，取消则不创建。
    // 无占位符的模板行为与原来完全一致（直接创建）。
    fun startConversationFromTemplate(template: PromptTemplateEntity) {
        val vars = templateVariables(template.content)
        if (vars.isEmpty()) {
            presetState.showSelector = false
            viewModel.createConversation(template.content) { id ->
                onNewChat(id)
            }
        } else {
            presetState.templateVarValues = vars.associateWith { "" }
            presetState.templateFillTarget = template
        }
    }

    // F9: Chat room mode selection
    var showNewChatModeSelector by remember { mutableStateOf(false) }

    // Model data from ViewModel（顶栏副标题/切换按钮可用态）
    val currentModel by viewModel.currentModel.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 顶栏收纳：高频的模型切换保留在栏上，低频的
            // 预设管理 / 技能参数 / 备份 / 恢复 / 连接设置 收进溢出菜单
            var showTopMenu by remember { mutableStateOf(false) }
            PixelTopBar(
                title = "Familiar",
                subtitle = currentModel.takeIf { it.isNotBlank() },
                actions = {
                    PixelIconButton(
                        icon = Icons.Default.Tune,
                        contentDescription = "切换模型",
                        onClick = { showModelSelector = true },
                        tint = if (currentModel.isNotBlank()) PaperLight
                        else PaperLight.copy(alpha = 0.5f)
                    )
                    Box {
                        PixelIconButton(
                            icon = Icons.Default.MoreVert,
                            contentDescription = "更多",
                            onClick = { showTopMenu = true },
                            tint = PaperLight
                        )
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("预设管理") },
                                onClick = {
                                    showTopMenu = false
                                    presetState.showManager = true
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("技能参数") },
                                onClick = {
                                    showTopMenu = false
                                    onSkillsClick()
                                },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("备份全部会话") },
                                onClick = {
                                    showTopMenu = false
                                    val name = "familiar_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"
                                    backupLauncher.launch(name)
                                },
                                leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("从备份恢复") },
                                onClick = {
                                    showTopMenu = false
                                    restoreLauncher.launch(arrayOf("application/json"))
                                },
                                leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("连接设置") },
                                onClick = {
                                    showTopMenu = false
                                    onSettingsClick()
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("检查更新") },
                                onClick = {
                                    showTopMenu = false
                                    // 与 MainActivity 的 DialogHost 共用 Activity 级 ViewModel 实例
                                    updateViewModel.openAboutAndCheck()
                                },
                                leadingIcon = { Icon(Icons.Default.Update, contentDescription = null) }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // 像素风 "+"：橙色凹凸方块
            PixelIconButton(
                icon = Icons.Default.Add,
                contentDescription = "新建对话",
                onClick = { showNewChatModeSelector = true },
                filled = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(58.dp)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ChatListContent(
                viewModel = viewModel,
                chatViewModel = chatViewModel,
                onChatClick = onChatClick,
                onOpenFamiliar = onNewFamiliar,
                onOpenNewChatMenu = { showNewChatModeSelector = true },
                onStartFromTemplate = ::startConversationFromTemplate,
                onSkillsClick = onSkillsClick
            )
        }
    }

    // ===== 拆分后的弹窗群挂载 =====

    // 主「设置」弹窗（界面/模型/帧数/上下文/大肥鱼/联网搜索/称呼/隐私说明）
    SettingsDialog(
        visible = showModelSelector,
        viewModel = viewModel,
        chatViewModel = chatViewModel,
        uiScope = uiScope,
        snackbarHostState = snackbarHostState,
        currentModel = currentModel,
        onDismiss = { showModelSelector = false },
        onShowMemory = { showFamiliarMemory = true },
        onOpenMcp = onOpenMcp
    )

    // 大肥鱼记忆（隐私域：查看/复制/两级确认清空）
    FamiliarMemoryDialogs(
        visible = showFamiliarMemory,
        chatViewModel = chatViewModel,
        snackbarHostState = snackbarHostState,
        onDismiss = { showFamiliarMemory = false }
    )

    // 模板/角色预设弹窗群
    PresetDialogs(
        state = presetState,
        viewModel = viewModel,
        onStartFromTemplate = ::startConversationFromTemplate,
        onCreated = onNewChat
    )

    // F9: New chat mode selector —— 底部像素面板。
    // 大肥鱼是独立类型，不进"新建对话"菜单：入口只有首页陪伴卡/点桌宠。
    if (showNewChatModeSelector) {
        PixelBottomPanel(
            onDismiss = { showNewChatModeSelector = false },
            title = "新建对话"
        ) {
            PixelDivider()
            PixelModeRow(
                icon = Icons.Default.ChatBubble,
                title = "普通对话",
                subtitle = "直接开聊，无预设人设",
                iconBox = MaterialTheme.colorScheme.primary,
                onClick = {
                    showNewChatModeSelector = false
                    viewModel.createConversation { id -> onNewChat(id) }
                }
            )
            PixelDivider()
            PixelModeRow(
                icon = Icons.Default.Group,
                title = "闲聊群",
                subtitle = "多模型聚在一起轻松闲聊",
                iconBox = SkyBlue,
                onClick = {
                    showNewChatModeSelector = false
                    onNewChatRoom()
                }
            )
            PixelDivider()
            PixelModeRow(
                icon = Icons.Default.Forum,
                title = "学习圆桌",
                subtitle = "多模型圆桌共学",
                iconBox = GrassGreen,
                onClick = {
                    showNewChatModeSelector = false
                    onNewChatRoom()
                }
            )
            PixelDivider()
            PixelModeRow(
                icon = Icons.Default.Lightbulb,
                title = "讲人话模式",
                subtitle = "代码/报错/概念讲成大白话",
                iconBox = StarGold,
                onClick = {
                    showNewChatModeSelector = false
                    onNewEli5()
                }
            )
            PixelDivider()
            PixelModeRow(
                icon = Icons.Default.School,
                title = "学习课程",
                subtitle = "AI 导师引导式教学",
                iconBox = GrassGreen,
                onClick = {
                    showNewChatModeSelector = false
                    onNewTutor()
                }
            )
            PixelDivider()
            PixelModeRow(
                icon = Icons.Default.Edit,
                title = "从模板新建…",
                subtitle = "先挑选系统提示词再开始",
                iconBox = MaterialTheme.colorScheme.secondary,
                onClick = {
                    showNewChatModeSelector = false
                    presetState.showSelector = true
                }
            )
        }
    }
}
