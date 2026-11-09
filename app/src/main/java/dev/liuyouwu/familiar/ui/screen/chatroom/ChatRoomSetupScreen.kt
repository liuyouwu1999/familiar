package dev.liuyouwu.familiar.ui.screen.chatroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.model.PersonaPresetUi
import dev.liuyouwu.familiar.core.llm.wire.ModelInfo
import dev.liuyouwu.familiar.skills.profile.MaterialSection
import dev.liuyouwu.familiar.util.parseColorHex
import dev.liuyouwu.familiar.util.persona.DefaultPersonaCards
import dev.liuyouwu.familiar.viewmodel.toUi

import java.util.UUID

data class ParticipantDraft(
    val id: String = UUID.randomUUID().toString(),
    val modelName: String,
    val displayName: String,
    val personality: String = "",
    val colorHex: String = "#1976D2",
    // ===== 人设卡 v2 字段（空串=未选卡/旧数据，system 拼装回落 personality 路径）=====
    val identity: String = "",
    val traits: String = "",
    val speechStyle: String = "",
    val catchphrase: String = "",
    val exampleDialogs: String = ""
)

/** 预设卡 → 成员草稿：v2 字段随卡贯通（选卡即整卡替换，不残留上一张卡的痕迹） */
internal fun PersonaPresetUi.toParticipantDraft(modelName: String): ParticipantDraft =
    ParticipantDraft(
        modelName = modelName,
        displayName = name,
        personality = personality,
        colorHex = colorHex,
        identity = identity,
        traits = traits,
        speechStyle = speechStyle,
        catchphrase = catchphrase,
        exampleDialogs = exampleDialogs
    )

// ===== 聊天室模板（Task 9）=====
/** 模板标识：闲聊群 = 现状自由群聊；学习圆桌 = 课题 + 三固定角色 */
object ChatRoomTemplates {
    const val CASUAL = "casual"
    const val ROUNDTABLE = "roundtable"
}

/**
 * 学习圆桌 skill JSON 预设角色（P4 T3：[ChatViewModel.roundtableRolePresets] 注入，
 * RoundtablePrompts 引用退役）——名称/提示词与运行期 ChatRoomHooks.presetRoles 同源
 * （圆桌角色固定自 assets/skills/roundtable.json behaviors.multiAgent.roles）。
 */
data class RoundtableRolePreset(
    val name: String,
    val prompt: String,
)

/** 建室结果：模板 + 圆桌课题（闲聊群课题为空串）+ 成员草稿 + 圆桌可选学习材料
 *  （Task 5；闲聊群恒 null，trim 推导在 VM 侧——本结果只承载原始表单值）。
 *  开场锚点与标题推导已随 P4 T3 迁 VM createChatRoomConversation（P5 T4b 前名
 *  createChatRoom；openingMessage 行为渲染 skill JSON 模板 / 课题即标题）。 */
data class ChatRoomSetupResult(
    val template: String,
    val topic: String,
    val participants: List<ParticipantDraft>,
    val material: String? = null
) {
    val isRoundtable: Boolean get() = template == ChatRoomTemplates.ROUNDTABLE
}

// 屏内展示常量（skill JSON 不携带颜色/短描述；顺序与 roundtable.json roles 一致，
// 超出预设数时回落通用色轮/预设名）
private val ROUNDTABLE_ROLE_COLORS = listOf("#1976D2", "#F57C00", "#388E3C")
private val ROUNDTABLE_ROLE_LABELS = listOf(
    "学习圆桌的讲师", "圆桌里爱追问的同学", "圆桌里的完全外行",
)

/** 学习圆桌三角色预填（P4 T3）：名称/提示词来自 skill JSON 预设（AppNavigation 注入），
 *  颜色/描述为屏内常量；提示词本体进 traits（v2 卡字段 verbatim 落位），identity 保证
 *  走卡路径。运行期圆桌角色由 skill JSON 预设解析（ChatRoomHooks.presetRoles）——
 *  草稿随 members/表双写，供存量回退与旧版本。 */
private fun roundtableRoleDrafts(
    modelName: String,
    presets: List<RoundtableRolePreset>,
): List<ParticipantDraft> = presets.mapIndexed { i, p ->
    ParticipantDraft(
        modelName = modelName,
        displayName = p.name,
        personality = ROUNDTABLE_ROLE_LABELS.getOrElse(i) { p.name },
        colorHex = ROUNDTABLE_ROLE_COLORS.getOrElse(i) { PRESET_COLORS[i % PRESET_COLORS.size] },
        identity = ROUNDTABLE_ROLE_LABELS.getOrElse(i) { p.name },
        traits = p.prompt
    )
}

// ===== 进程重建恢复：ParticipantDraft 用 Gson 序列化为 JSON 字符串存入 SavedState =====
private val draftGson = com.google.gson.Gson()

/** 单个角色草稿的 Saver（可空：null 存空串） */
private val ParticipantDraftSaver: Saver<ParticipantDraft?, String> = Saver(
    save = { if (it != null) draftGson.toJson(it) else "" },
    restore = {
        if (it.isNullOrEmpty()) null
        else runCatching { draftGson.fromJson(it, ParticipantDraft::class.java) }.getOrNull()
    }
)

/** 角色草稿列表的 Saver */
private val ParticipantListSaver: Saver<List<ParticipantDraft>, String> = Saver(
    save = { drafts -> draftGson.toJson(drafts) },
    restore = {
        runCatching {
            draftGson.fromJson(it, Array<ParticipantDraft>::class.java).toList()
        }.getOrDefault(emptyList())
    }
)

private val PRESET_COLORS = listOf(
    "#1976D2", "#D32F2F", "#388E3C", "#F57C00",
    "#7B1FA2", "#00838F", "#C2185B", "#455A64"
)

// 内置预设收编至 DefaultPersonaCards（两屏共用 + DB 种子同源）
private fun generateDisplayName(modelName: String): String {
    val names = listOf("小明", "小红", "小华", "小李", "小张", "小王", "小陈", "小赵")
    return names.random()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomSetupScreen(
    availableModels: List<ModelInfo>,
    currentModelName: String,
    customPresets: List<PersonaPresetUi>,
    /** 学习圆桌 skill JSON 预设（AppNavigation 注入 ChatViewModel.roundtableRolePresets） */
    roundtableRolePresets: () -> List<RoundtableRolePreset>,
    onCreateRoom: (ChatRoomSetupResult) -> Unit,
    onSavePreset: (PersonaPresetUi) -> Unit,
    onDeletePreset: (PersonaPresetUi) -> Unit,
    onBack: () -> Unit
) {
    // 模板选择（null = 未选，展示两张模板卡）；模板与会话课题一起跨进程重建保留
    var template by rememberSaveable { mutableStateOf<String?>(null) }
    var topic by rememberSaveable { mutableStateOf("") }
    // 圆桌可选学习材料（Task 5）：原始输入全程持有（超限门槛在 canCreate，trim 在 VM 侧）
    var material by rememberSaveable { mutableStateOf("") }
    // 表单已填角色用 rememberSaveable：旋转/进程重建不丢（审计 §1-5）
    var participants by rememberSaveable(stateSaver = ParticipantListSaver) {
        mutableStateOf(emptyList<ParticipantDraft>())
    }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showSavePresetDialog by rememberSaveable { mutableStateOf(false) }
    var presetToSave by rememberSaveable(stateSaver = ParticipantDraftSaver) {
        mutableStateOf<ParticipantDraft?>(null)
    }

    fun selectTemplate(t: String) {
        template = t
        participants = if (t == ChatRoomTemplates.ROUNDTABLE) {
            roundtableRoleDrafts(currentModelName, roundtableRolePresets())
        } else {
            emptyList()
        }
    }

    // 建室门槛：闲聊群 2-4 人；圆桌课题必填 + 三角色显示名非空 + 材料不超限（Task 5）
    val canCreate = when (template) {
        ChatRoomTemplates.CASUAL -> participants.size in 2..4
        ChatRoomTemplates.ROUNDTABLE ->
            topic.isNotBlank() && participants.isNotEmpty() &&
                participants.all { it.displayName.isNotBlank() } &&
                material.length <= MaterialSection.MAX_LEN
        else -> false
    }

    Scaffold(
        topBar = {
            dev.liuyouwu.familiar.ui.theme.PixelTopBar(
                title = when (template) {
                    ChatRoomTemplates.ROUNDTABLE -> "创建学习圆桌"
                    else -> "创建聊天室"
                },
                navigationIcon = {
                    dev.liuyouwu.familiar.ui.theme.PixelIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                        tint = dev.liuyouwu.familiar.ui.theme.PaperLight
                    )
                },
                actions = {
                    dev.liuyouwu.familiar.ui.theme.PixelIconButton(
                        icon = Icons.Default.Check,
                        contentDescription = "创建",
                        onClick = {
                            val t = template
                            if (t != null && canCreate) {
                                onCreateRoom(
                                    ChatRoomSetupResult(
                                        template = t,
                                        topic = topic,
                                        participants = participants,
                                        material = material.ifBlank { null }
                                    )
                                )
                            }
                        },
                        enabled = canCreate,
                        tint = dev.liuyouwu.familiar.ui.theme.PaperLight
                    )
                }
            )
        }
    ) { padding ->
        when (template) {
            // 第一步：模板选择（闲聊群 / 学习圆桌）
            null -> TemplatePicker(
                modifier = Modifier.padding(padding),
                onSelectCasual = { selectTemplate(ChatRoomTemplates.CASUAL) },
                onSelectRoundtable = { selectTemplate(ChatRoomTemplates.ROUNDTABLE) }
            )

            // 模板 B：学习圆桌（课题 + 三固定角色，名称/模型可改）
            ChatRoomTemplates.ROUNDTABLE -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = topic,
                        onValueChange = { topic = it },
                        label = { Text("学习课题") },
                        placeholder = { Text("例如：为什么天空是蓝色的") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    // 可选学习材料（Task 5）：讨论以此为准；超限时创建按钮（顶栏✓）禁用，
                    // 就在输入框下方直说差什么（门槛可见化，同"至少加 2 个角色"的模式）
                    OutlinedTextField(
                        value = material,
                        onValueChange = { material = it },
                        label = { Text("学习材料（可选，≤2000 字）") },
                        placeholder = { Text("粘贴一段要围绕讨论的资料，留空则自由讨论") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    if (material.length > MaterialSection.MAX_LEN) {
                        Text(
                            "学习材料超限（${material.length}/${MaterialSection.MAX_LEN} 字），精简后才能创建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "讲师先开场，怀疑者追问，新手帮大家暴露盲区。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    participants.forEach { participant ->
                        RoundtableRoleCard(
                            participant = participant,
                            availableModels = availableModels.mapNotNull { it.id },
                            onUpdate = { updated ->
                                participants = participants.map { if (it.id == participant.id) updated else it }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // 模板 A：闲聊群（现状流程：从预设/自定义卡选 2-4 个成员）
            else -> {
                if (participants.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "加 2-4 个角色就能开群",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(shape = RectangleShape, border = BorderStroke(1.dp, dev.liuyouwu.familiar.ui.theme.WoodBorder), onClick = { showModelPicker = true }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加角色")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(participants.toList(), key = { it.id }) { participant ->
                            ParticipantCard(
                                participant = participant,
                                index = participants.indexOf(participant),
                                customPresets = customPresets,
                                availableModels = availableModels.mapNotNull { it.id },
                                onRemove = {
                                    participants = participants.filter { it.id != participant.id }
                                },
                                onUpdate = { updated ->
                                    participants = participants.map { if (it.id == participant.id) updated else it }
                                },
                                onSaveAsPreset = { presetToSave = it; showSavePresetDialog = true }
                            )
                        }

                        if (participants.size < 2) {
                            item {
                                // 门槛可见化（QA 2026-10-31 发现 10）：✓ 禁用时只有变暗，
                                // 不说明差什么——成员不足就在列表里直说
                                Text(
                                    "至少加 2 个角色才能开群",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (participants.size < 4) {
                            item {
                                OutlinedButton(
                                    shape = RectangleShape,
                                    onClick = { showModelPicker = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("添加角色")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Model picker dialog
    if (showModelPicker) {
        AddParticipantDialog(
            currentModelName = currentModelName,
            customPresets = customPresets,
            participantCount = participants.size,
            onAdd = { draft ->
                participants = participants + draft
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }

    // Save preset dialog
    if (showSavePresetDialog && presetToSave != null) {
        SavePresetDialog(
            draft = presetToSave!!,
            onSave = { name ->
                val d = presetToSave!!
                onSavePreset(PersonaPresetUi(
                    name = name,
                    personality = d.personality,
                    colorHex = d.colorHex,
                    identity = d.identity,
                    traits = d.traits,
                    speechStyle = d.speechStyle,
                    catchphrase = d.catchphrase,
                    exampleDialogs = d.exampleDialogs
                ))
                showSavePresetDialog = false
                presetToSave = null
            },
            onDismiss = {
                showSavePresetDialog = false
                presetToSave = null
            }
        )
    }
}

/** 第一步模板选择：两张模板卡 */
@Composable
private fun TemplatePicker(
    modifier: Modifier = Modifier,
    onSelectCasual: () -> Unit,
    onSelectRoundtable: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "选择聊天室类型",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clickable(onClick = onSelectCasual),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("闲聊群", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "2-4 个 AI 角色自由群聊，人设从预设卡或自定义卡里选。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelectRoundtable),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("学习圆桌", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "定一个课题，讲师、爱追问、外行提问者三角色带你讨论。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** 学习圆桌角色卡：只开放改名/换模型，提示词与颜色固定（不暴露预设快捷行，防整卡被覆盖） */
@Composable
private fun RoundtableRoleCard(
    participant: ParticipantDraft,
    availableModels: List<String>,
    onUpdate: (ParticipantDraft) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = parseColorHex(participant.colorHex).copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(parseColorHex(participant.colorHex)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = participant.displayName.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = participant.personality,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = participant.displayName,
                onValueChange = { onUpdate(participant.copy(displayName = it)) },
                label = { Text("显示名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(4.dp))

            dev.liuyouwu.familiar.ui.component.ModelDropdownField(
                selected = participant.modelName,
                models = availableModels,
                onSelected = { onUpdate(participant.copy(modelName = it)) }
            )
        }
    }
}

@Composable
internal fun AddParticipantDialog(
    currentModelName: String,
    customPresets: List<PersonaPresetUi>,
    participantCount: Int,
    onAdd: (ParticipantDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var showCustomInput by remember { mutableStateOf(false) }

    if (showCustomInput) {
        CustomPresetDialog(
            initialName = "",
            initialPersonality = "",
            initialColor = PRESET_COLORS[participantCount % PRESET_COLORS.size],
            onSave = { name, personality, color ->
                onAdd(ParticipantDraft(
                    modelName = currentModelName,
                    displayName = name,
                    personality = personality,
                    colorHex = color
                ))
            },
            onDismiss = { showCustomInput = false }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("添加角色") },
            text = {
                Column {
                    Text(
                        "每个角色可以绑不同模型、按自己的人设说话，主持人安排轮流发言",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 内置卡为 Entity 形态，折 Ui 投影后与自定义卡同列（审计 A1）
                    val defaultPresetsUi = DefaultPersonaCards.PRESETS.map { it.toUi() }

                    // Custom presets section
                    if (customPresets.isNotEmpty()) {
                        Text("我的预设:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        customPresets.forEach { preset ->
                            PresetCard(
                                preset = preset,
                                onClick = { onAdd(preset.toParticipantDraft(currentModelName)) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // DB 种子与内置卡同名去重：新装用户（库已含六卡）不重复展示
                        val dedupedDefaults = defaultPresetsUi
                            .filter { builtin -> customPresets.none { it.name == builtin.name } }
                        if (dedupedDefaults.isNotEmpty()) {
                            Text("默认预设:", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            dedupedDefaults.forEach { preset ->
                                PresetCard(
                                    preset = preset,
                                    onClick = { onAdd(preset.toParticipantDraft(currentModelName)) }
                                )
                            }
                        }
                    }

                    // Default presets（无自定义预设时直接展示内置卡）
                    if (customPresets.isEmpty()) {
                        defaultPresetsUi.forEach { preset ->
                            PresetCard(
                                preset = preset,
                                onClick = { onAdd(preset.toParticipantDraft(currentModelName)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCustomInput = true }) {
                    Text("自定义")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PresetCard(
    preset: PersonaPresetUi,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                                        .background(parseColorHex(preset.colorHex))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = preset.personality,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ParticipantCard(
    participant: ParticipantDraft,
    index: Int,
    customPresets: List<PersonaPresetUi>,
    availableModels: List<String>,
    onRemove: () -> Unit,
    onUpdate: (ParticipantDraft) -> Unit,
    onSaveAsPreset: (ParticipantDraft) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
            containerColor = parseColorHex(participant.colorHex).copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                                                .background(parseColorHex(participant.colorHex)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = participant.displayName.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = participant.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { onSaveAsPreset(participant) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = "保存为预设",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = participant.displayName,
                onValueChange = { onUpdate(participant.copy(displayName = it)) },
                label = { Text("显示名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = participant.personality,
                onValueChange = { onUpdate(participant.copy(personality = it)) },
                label = { Text("性格/角色") },
                modifier = Modifier.fillMaxWidth().height(80.dp),
                placeholder = { Text("例如：活泼开朗的大学生，喜欢社交") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 每个角色可绑定不同模型（批次3：主持人调度按角色模型调用）
            dev.liuyouwu.familiar.ui.component.ModelDropdownField(
                selected = participant.modelName,
                models = availableModels,
                onSelected = { onUpdate(participant.copy(modelName = it)) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 快捷换卡行补标签（QA 发现 10 伴生）：无说明时看起来像成员列表，
            // 点了以为加人、实际是替换本卡
            Text(
                "快捷换卡：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            // Quick presets row - show custom presets first, then defaults
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 内置卡为 Entity 形态，折 Ui 投影后与自定义卡同列（审计 A1）
                val allPresets = customPresets.take(3).ifEmpty {
                    DefaultPersonaCards.PRESETS.take(3).map { it.toUi() }
                }
                allPresets.forEach { preset ->
                    Card(
                        modifier = Modifier
                            .clickable {
                                // 快捷换卡 = 整卡替换：v2 字段一并覆盖，不残留旧卡痕迹
                                onUpdate(participant.copy(
                                    personality = preset.personality,
                                    displayName = preset.name,
                                    identity = preset.identity,
                                    traits = preset.traits,
                                    speechStyle = preset.speechStyle,
                                    catchphrase = preset.catchphrase,
                                    exampleDialogs = preset.exampleDialogs
                                ))
                            }
                            .padding(2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Color picker
            Text("颜色", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESET_COLORS.forEach { hex ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                                                        .background(parseColorHex(hex))
                            .clickable { onUpdate(participant.copy(colorHex = hex)) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (hex == participant.colorHex) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    draft: ParticipantDraft,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(draft.displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存为预设") },
        text = {
            Column {
                Text("将此角色保存为可复用的预设：", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("预设名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name) },
                enabled = name.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CustomPresetDialog(
    initialName: String,
    initialPersonality: String,
    initialColor: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var personality by remember { mutableStateOf(initialPersonality) }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义角色") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    label = { Text("性格/角色") },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    placeholder = { Text("例如：活泼开朗的大学生，喜欢社交") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("颜色", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESET_COLORS.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                                                .background(parseColorHex(hex))
                                .clickable { color = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (hex == color) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name, personality, color) },
                enabled = name.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// parseColorHex 收编至 util/PixelColorUtil（审计 P3：三份相同实现合一）
