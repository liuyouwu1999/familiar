package dev.liuyouwu.familiar.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.liuyouwu.familiar.data.local.AppDatabase
import dev.liuyouwu.familiar.data.repository.ChatRepository
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.pet.PetSkinStore
import dev.liuyouwu.familiar.ui.screen.chat.ChatScreen
import dev.liuyouwu.familiar.ui.screen.chatlist.ChatListScreen
import dev.liuyouwu.familiar.ui.screen.chatroom.ChatRoomEditScreen
import dev.liuyouwu.familiar.ui.screen.chatroom.ChatRoomSetupScreen
import dev.liuyouwu.familiar.ui.screen.chatroom.ParticipantDraft
import dev.liuyouwu.familiar.ui.screen.connection.ConnectionScreen
import dev.liuyouwu.familiar.viewmodel.ChatListViewModel
import dev.liuyouwu.familiar.viewmodel.ChatViewModel
import dev.liuyouwu.familiar.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    // ChatViewModel 提升为 Activity 级单例（审计 P2-11）：聊天页/聊天室编辑页/创建页
    // 共享同一实例，"生成中"互斥（_isLoading）才能跨页面生效；fetchModels 也只拉一次
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    val chatViewModel: ChatViewModel = viewModel(viewModelStoreOwner = activity)
    // UpdateViewModel 同样必须 Activity 级（审核 P1）：NavHost 目的地内裸 viewModel() 拿到的是
    // 条目级实例，与 MainActivity 的 DialogHost/自动检查不同对象，"检查更新"菜单会变成死入口
    val updateViewModel: dev.liuyouwu.familiar.viewmodel.UpdateViewModel =
        viewModel(viewModelStoreOwner = activity)

    // ===== 会话服务直连（P5 T4b：creator 壳删除）=====
    // 原 ChatViewModel.createEli5Conversation/createFamiliarConversation/
    // createTutorConversation 是会话服务（ChatRepository.createConversationWithSkill）的
    // 薄壳，随壳删除，调用点在此直连。ChatRepository/SettingsRepository 均为无状态轻量
    // 门面（前者包 AppDatabase 单例的 DAO，后者包 Context.dataStore 单例委托；各
    // ViewModel/MainActivity 本就各自实例化），导航层就地装配一份。
    val chatRepository = remember(activity) {
        val db = AppDatabase.getInstance(activity)
        ChatRepository(
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            promptTemplateDao = db.promptTemplateDao(),
            db = db
        )
    }
    val settingsRepository = remember(activity) { SettingsRepository(activity.applicationContext) }
    // 入口防连点（原壳 creatingEli5/creatingFamiliar 标志随迁）：familiar 的两个入口
    // （桌宠 PetHub.onOpenChat / 列表页 onNewFamiliar）共用一个标志，双击/双入口不连建
    var creatingEli5 by remember { mutableStateOf(false) }
    var creatingFamiliar by remember { mutableStateOf(false) }

    // 创建/进入主精灵陪伴会话（原 createFamiliarConversation 函数体随迁，行为等价）——
    // 记忆会话复用：记住的会话仍存在则直接复用，不另建；设置播种：建会话时把卡的
    // address/selfName 写进设置（"设置覆盖卡值"体系的初值来源，否则卡的"阿宅/小鱼"
    // 自称会被设置默认"我"掩盖）；skillParams 只存 cardId，人设变量派生在 T6 会话装配时做；
    // 失败经 ChatViewModel.reportError 走原 _error 通道（列表页 snackbar/桌宠 ERROR 桥）。
    fun launchFamiliarCreation(cardId: Long, onCreated: (Long) -> Unit) {
        if (creatingFamiliar) return
        creatingFamiliar = true
        coroutineScope.launch {
            try {
                val remembered = settingsRepository.getFamiliarConversationId()
                if (remembered != null && chatRepository.getConversationById(remembered) != null) {
                    onCreated(remembered)
                    return@launch
                }
                // 人设随皮肤（personaOf 四分支；cardId 参数保留兼容旧调用，恒写 0）
                val preset = dev.liuyouwu.familiar.util.persona.DefaultPersonaCards
                    .personaOf(PetSkinStore.get(settingsRepository.getPetColor()))
                val id = chatRepository.createConversationWithSkill(
                    title = preset.name,
                    skillId = dev.liuyouwu.familiar.skills.TypeInferer.FAMILIAR,
                    skillParams = mapOf("cardId" to cardId.toString())
                )
                settingsRepository.setFamiliarConversationId(id)
                settingsRepository.setFamiliarAddress(preset.address)
                settingsRepository.setFamiliarSelfName(preset.selfName)
                onCreated(id)
            } catch (e: Exception) {
                android.util.Log.e("AppNavigation", "create familiar conversation failed", e)
                chatViewModel.reportError("创建失败：${(e.message ?: "未知错误").lineSequence().firstOrNull().orEmpty().take(60)}")
            } finally {
                creatingFamiliar = false
            }
        }
    }

    // 创建「讲人话」解释模式会话（原 createEli5Conversation 函数体随迁）：零参数 skill
    // （paramsSchema 空 → skillParams 空表），防连点，失败走同一 _error 通道。
    fun launchEli5Creation(onCreated: (Long) -> Unit) {
        if (creatingEli5) return
        creatingEli5 = true
        coroutineScope.launch {
            try {
                val id = chatRepository.createConversationWithSkill(
                    title = "讲人话问答",
                    skillId = dev.liuyouwu.familiar.skills.TypeInferer.ELI5,
                    skillParams = emptyMap()
                )
                onCreated(id)
            } catch (e: Exception) {
                android.util.Log.e("AppNavigation", "create eli5 conversation failed", e)
                chatViewModel.reportError("创建失败：${(e.message ?: "未知错误").lineSequence().firstOrNull().orEmpty().take(60)}")
            } finally {
                creatingEli5 = false
            }
        }
    }

    // 点桌宠「转到对话」→ 跳到主陪伴聊天框（入口去重=记忆会话复用，launchFamiliarCreation 负责）。
    // 钩子挂在导航根部（Activity 级）：挂在 chatlist 页内的话，离开该页面即被摘掉，
    // 用户停在聊天/连接/设置页时长按桌宠就只剩"拉起 app"不再跳对话。
    // onDispose 在 Activity 内容销毁时才执行，悬浮窗服务不会拿到已销毁的 navController
    androidx.compose.runtime.DisposableEffect(Unit) {
        dev.liuyouwu.familiar.pet.PetHub.onOpenChat = {
            launchFamiliarCreation(0L) { id ->
                navController.navigate("chat/$id") {
                    popUpTo("chatlist")
                }
            }
        }
        onDispose { dev.liuyouwu.familiar.pet.PetHub.onOpenChat = null }
    }

    NavHost(navController = navController, startDestination = "connection") {
        composable(
            route = "connection?autoConnect={autoConnect}",
            arguments = listOf(navArgument("autoConnect") { type = NavType.BoolType; defaultValue = true })
        ) { backStackEntry ->
            val autoConnect = backStackEntry.arguments?.getBoolean("autoConnect") ?: true
            val viewModel: ConnectionViewModel = viewModel()
            ConnectionScreen(
                viewModel = viewModel,
                onConnected = {
                    navController.navigate("chatlist") {
                        popUpTo("connection") { inclusive = true }
                        // 从设置进来时栈里本来就有一层 chatlist，launchSingleTop 去重
                        launchSingleTop = true
                    }
                },
                autoConnect = autoConnect,
                onBack = if (autoConnect) null else ({ navController.popBackStack() })
            )
        }

        composable("chatlist") {
            // 主页是栈底：不拦截返回键，让系统默认行为退出应用
            val chatListViewModel: ChatListViewModel = viewModel()
            ChatListScreen(
                viewModel = chatListViewModel,
                chatViewModel = chatViewModel,
                updateViewModel = updateViewModel,
                onChatClick = { conversationId ->
                    navController.navigate("chat/$conversationId")
                },
                onSettingsClick = {
                    // 保持 chatlist 在栈底：连接页系统返回键回列表（此前弹栈导致返回直接退到桌面）
                    navController.navigate("connection?autoConnect=false")
                },
                onNewChat = { conversationId ->
                    navController.navigate("chat/$conversationId")
                },
                onNewChatRoom = {
                    navController.navigate("chatroom_setup")
                },
                onNewTutor = {
                    navController.navigate("tutor_setup")
                },
                onNewEli5 = {
                    launchEli5Creation { id ->
                        navController.navigate("chat/$id") {
                            popUpTo("chatlist")
                        }
                    }
                },
                onNewFamiliar = {
                    // 首次用空卡（恒写 0，人设固定内置大肥鱼），换卡走 设置→大肥鱼→更换人设卡
                    launchFamiliarCreation(0L) { id ->
                        navController.navigate("chat/$id") {
                            popUpTo("chatlist")
                        }
                    }
                },
                onSkillsClick = {
                    navController.navigate("skills")
                },
                onOpenMcp = {
                    navController.navigate("mcp")
                }
            )
        }

        // 技能参数管理（P5 T6）：六类型 skill 一览 + 现存会话参数编辑（updateSkillParams）
        composable("skills") {
            val chatListViewModel: ChatListViewModel = viewModel()
            dev.liuyouwu.familiar.ui.screen.skills.SkillManagementScreen(
                listViewModel = chatListViewModel,
                // 聊天室成员编辑复用既有编辑页（成员在会话编辑页改）
                onEditRoom = { conversationId ->
                    navController.navigate("chatroom_edit/$conversationId")
                },
                onBack = { navController.popBackStack() }
            )
        }

        // MCP 设置子屏（T4）：总开关+服务器列表（编辑/添加/删除直连 SettingsRepository；
        // 状态流消费 McpToolManager.statusFlow，刷新链归 AgentSessionController 常驻收集）
        composable("mcp") {
            dev.liuyouwu.familiar.ui.screen.chatlist.SettingsMcpScreen(
                settingsRepository = settingsRepository,
                onBack = { navController.popBackStack() }
            )
        }

        composable("tutor_setup") {
            val scope = rememberCoroutineScope()
            dev.liuyouwu.familiar.ui.screen.tutor.ClassroomSetupScreen(
                // D6（T7）：表单六键直通会话服务签名（creator 壳 createTutorConversation
                // 已随 P5 T4b 删除，调用点直连 createConversationWithSkill）
                onCreate = { skillParams, title, onDone ->
                    scope.launch {
                        try {
                            val id = chatRepository.createConversationWithSkill(
                                title = title,
                                skillId = dev.liuyouwu.familiar.skills.TypeInferer.CLASSROOM,
                                skillParams = skillParams
                            )
                            navController.navigate("chat/$id") {
                                popUpTo("chatlist")
                            }
                            onDone(true)
                        } catch (e: Exception) {
                            android.util.Log.e("TutorSetup", "create conversation failed", e)
                            onDone(false)
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("chatroom_setup") {
            val availableModels by chatViewModel.availableModels.collectAsStateWithLifecycle()
            val currentModel by chatViewModel.currentModel.collectAsStateWithLifecycle()
            val customPresets by chatViewModel.personaPresets.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                chatViewModel.fetchModels()
            }

            ChatRoomSetupScreen(
                availableModels = availableModels,
                currentModelName = currentModel,
                customPresets = customPresets,
                // P4 T3：圆桌三角色预填换 skill JSON 预设（RoundtablePrompts 退役）
                roundtableRolePresets = { chatViewModel.roundtableRolePresets() },
                onCreateRoom = { result ->
                    coroutineScope.launch {
                        // P4 T3 新签名：模板分流 skillId/锚点开场/课题标题都在 VM 侧
                        // 推导（openingMessage 行为 + members JSON 契约）。P5 T4b 更名
                        // createChatRoomConversation（P4 Ruling 委托续命保留，非壳——
                        // participants 表+旧列双写）
                        val conversationId = chatViewModel.createChatRoomConversation(
                            template = result.template,
                            participants = result.participants,
                            topic = result.topic
                        )
                        navController.navigate("chat/$conversationId") {
                            popUpTo("chatlist")
                        }
                    }
                },
                onSavePreset = { preset -> chatViewModel.savePersonaPreset(preset) },
                onDeletePreset = { preset -> chatViewModel.deletePersonaPreset(preset) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "chat/{conversationId}",
            arguments = listOf(navArgument("conversationId") { type = NavType.LongType })
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getLong("conversationId") ?: return@composable
            val viewModel = chatViewModel
            ChatScreen(
                viewModel = viewModel,
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onEditChatRoom = { convId ->
                    navController.navigate("chatroom_edit/$convId")
                }
            )
        }

        composable(
            route = "chatroom_edit/{conversationId}",
            arguments = listOf(navArgument("conversationId") { type = NavType.LongType })
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getLong("conversationId") ?: return@composable
            val viewModel = chatViewModel
            val currentModel by viewModel.currentModel.collectAsStateWithLifecycle()
            val customPresets by viewModel.personaPresets.collectAsStateWithLifecycle()
            val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
            val participants by remember(conversationId) {
                viewModel.getChatRoomParticipants(conversationId)
            }.collectAsStateWithLifecycle()

            ChatRoomEditScreen(
                conversationId = conversationId,
                currentModelName = currentModel,
                availableModels = availableModels,
                existingParticipants = participants,
                customPresets = customPresets,
                onSave = { updatedParticipants, onResult ->
                    // updateChatRoomParticipants 为 suspend：null=成功，非 null=错误文案，
                    // 结果交给编辑页 snackbar 展示（成功后由编辑页自行返回）
                    coroutineScope.launch {
                        onResult(viewModel.updateChatRoomParticipants(conversationId, updatedParticipants))
                    }
                },
                onSavePreset = { preset -> viewModel.savePersonaPreset(preset) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
