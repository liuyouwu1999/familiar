package dev.liuyouwu.familiar.ui.screen.skills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.skills.SkillDefinition
import dev.liuyouwu.familiar.skills.TypeInferer
import dev.liuyouwu.familiar.ui.screen.tutor.CLASSROOM_LANGUAGE_OPTIONS
import dev.liuyouwu.familiar.ui.screen.tutor.CLASSROOM_LEVEL_OPTIONS
import dev.liuyouwu.familiar.ui.screen.tutor.CLASSROOM_STYLE_OPTIONS
import dev.liuyouwu.familiar.ui.screen.tutor.CLASSROOM_TONE_OPTIONS
import dev.liuyouwu.familiar.ui.screen.tutor.ClassroomParamFields
import dev.liuyouwu.familiar.ui.theme.PaperLight
import dev.liuyouwu.familiar.ui.theme.PixelDivider
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.PixelPanel
import dev.liuyouwu.familiar.ui.theme.PixelTopBar
import dev.liuyouwu.familiar.ui.theme.pixelSecondaryAlpha
import dev.liuyouwu.familiar.viewmodel.ChatListViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 技能参数管理页（P5 T6，skills 路由）：六类型 skill 一览 + 现存会话的参数编辑入口。
 *
 * 表单为手写注册表（六类型各一组 composable），paramsSchema 数据源 = assets skills
 * 的 [dev.liuyouwu.familiar.skills.SkillRegistry]（与 SkillHost 装配同源同口径；
 * 独立实例沿 AgentEngine 建室/预填先例）：
 * - classroom：六键表单复用组件化的 [ClassroomParamFields]（与开课表单同源）；
 * - roundtable：topic 单字段；
 * - chatroom：成员编辑走既有 ChatRoomEditScreen（[onEditRoom]）；
 * - chat：模板选择沿用首页「从模板新建」的 prompt_templates 流程（建会话时定型，无会话级参数）；
 * - familiar：换卡已作废（人设固定内置大肥鱼），称呼/说话模式在 设置→大肥鱼（全局设置）；
 *   采样参数（temperature/frequencyPenalty/presencePenalty）走本页「编辑参数」弹窗，缺省
 *   与 familiar.json paramsSchema 同源（0.9/0.4/0.3），保存为字符串供 SkillParams.resolveFloats 消费；
 * - eli5：零参数。
 *
 * 编辑现存会话统一走 ChatListViewModel.updateSkillParams → ConversationDao.updateSkillParams
 * （JSON 对象整写 skillParams 列，值域全字符串）。searchBudget 各 skill 声明了
 * int=N 但运行时未从会话参数读取（预算走 §6.2 默认），表单一律不设——与
 * ClassroomSetupScreen 的既有约定相同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagementScreen(
    listViewModel: ChatListViewModel,
    onEditRoom: (Long) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val conversations by listViewModel.allConversations.collectAsStateWithLifecycle()

    // 编辑弹窗目标（classroom 六键 / roundtable topic / familiar 采样三键各一；state 随会话行点亮）
    var editingClassroom by remember { mutableStateOf<ConversationEntity?>(null) }
    var editingRoundtable by remember { mutableStateOf<ConversationEntity?>(null) }
    var editingFamiliar by remember { mutableStateOf<ConversationEntity?>(null) }

    // 注册表静态（assets 随包），remember 一次即可；展示按 TypeInferer 固定顺序。
    // 独立实例与 AgentEngine 建室/预填同款先例：skill JSON 打包在 APK 内不可变，
    // 与 SkillHost 的注册表同源同口径（AgentEngine.kt「独立实例仅服务建室/预填」）。
    val skills = remember {
        val registry = dev.liuyouwu.familiar.skills.SkillRegistry.fromAssets(
            context.assets, "skills"
        ) { msg -> android.util.Log.w("SkillRegistry", msg) }
        orderedSkills(registry.all())
    }
    val bySkill = remember(conversations) {
        conversations.filter { !it.skillId.isNullOrBlank() }.groupBy { it.skillId!! }
    }

    Scaffold(
        topBar = {
            PixelTopBar(
                title = "技能参数",
                navigationIcon = {
                    PixelIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                        tint = PaperLight
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(skills, key = { it.id }) { skill ->
                SkillCard(
                    skill = skill,
                    conversations = bySkill[skill.id].orEmpty(),
                    onEditClassroom = { editingClassroom = it },
                    onEditRoundtable = { editingRoundtable = it },
                    onEditFamiliar = { editingFamiliar = it },
                    onEditRoom = onEditRoom
                )
                PixelDivider()
            }
        }
    }

    // ===== classroom 参数编辑弹窗（六键，组件化字段复用） =====
    editingClassroom?.let { conv ->
        val current = remember(conv.id) { stringParams(conv) }
        var subject by rememberSaveable(conv.id) { mutableStateOf(current["subject"].orEmpty()) }
        var level by rememberSaveable(conv.id) { mutableStateOf(current["level"] ?: CLASSROOM_LEVEL_OPTIONS[1]) }
        var style by rememberSaveable(conv.id) { mutableStateOf(current["style"] ?: CLASSROOM_STYLE_OPTIONS[1]) }
        var tone by rememberSaveable(conv.id) { mutableStateOf(current["tone"] ?: CLASSROOM_TONE_OPTIONS[0]) }
        var language by rememberSaveable(conv.id) { mutableStateOf(current["language"] ?: "中文") }
        var classmateOn by rememberSaveable(conv.id) { mutableStateOf(current["classmate"] != "false") }
        AlertDialog(
            onDismissRequest = { editingClassroom = null },
            title = { Text("编辑课堂参数") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ClassroomParamFields(
                        subject = subject,
                        onSubjectChange = { subject = it },
                        level = level,
                        onLevelChange = { level = it },
                        style = style,
                        onStyleChange = { style = it },
                        tone = tone,
                        onToneChange = { tone = it },
                        language = language,
                        onLanguageChange = { language = it },
                        classmateOn = classmateOn,
                        onClassmateChange = { classmateOn = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        listViewModel.updateSkillParams(
                            conv.id,
                            mapOf(
                                "subject" to subject.trim(),
                                "level" to level,
                                "style" to style,
                                "tone" to tone,
                                "language" to language,
                                "classmate" to classmateOn.toString()
                            )
                        )
                        editingClassroom = null
                    },
                    enabled = subject.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingClassroom = null }) { Text("取消") }
            }
        )
    }

    // ===== roundtable 话题编辑弹窗 =====
    editingRoundtable?.let { conv ->
        val current = remember(conv.id) { stringParams(conv) }
        var topic by rememberSaveable(conv.id) { mutableStateOf(current["topic"].orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingRoundtable = null },
            title = { Text("编辑圆桌话题") },
            text = {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("话题") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        listViewModel.updateSkillParams(conv.id, mapOf("topic" to topic.trim()))
                        editingRoundtable = null
                    },
                    enabled = topic.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingRoundtable = null }) { Text("取消") }
            }
        )
    }

    // ===== familiar 采样参数编辑弹窗（temperature/frequencyPenalty/presencePenalty，值域全字符串） =====
    editingFamiliar?.let { conv ->
        val current = remember(conv.id) { stringParams(conv) }
        // 初值取已存字符串，缺省字面与 familiar.json paramsSchema 同源（float=0.9/0.4/0.3）
        var tempText by rememberSaveable(conv.id) { mutableStateOf(current["temperature"] ?: "0.9") }
        var freqText by rememberSaveable(conv.id) { mutableStateOf(current["frequencyPenalty"] ?: "0.4") }
        var presText by rememberSaveable(conv.id) { mutableStateOf(current["presencePenalty"] ?: "0.3") }
        val allValid = tempText.toDoubleOrNull() != null &&
            freqText.toDoubleOrNull() != null &&
            presText.toDoubleOrNull() != null
        AlertDialog(
            onDismissRequest = { editingFamiliar = null },
            title = { Text("编辑采样参数") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = tempText,
                        onValueChange = { tempText = it },
                        label = { Text("温度 temperature(0-1)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = freqText,
                        onValueChange = { freqText = it },
                        label = { Text("重复惩罚 frequency(0-2)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = presText,
                        onValueChange = { presText = it },
                        label = { Text("新词惩罚 presence(0-2)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        listViewModel.updateSkillParams(
                            conv.id,
                            mapOf(
                                "temperature" to tempText.trim(),
                                "frequencyPenalty" to freqText.trim(),
                                "presencePenalty" to presText.trim(),
                            )
                        )
                        editingFamiliar = null
                    },
                    enabled = allValid
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingFamiliar = null }) { Text("取消") }
            }
        )
    }
}

/** 展示顺序：TypeInferer 六序（chat/chatroom/classroom/eli5/familiar/roundtable），未知的排最后 */
private fun orderedSkills(defs: List<SkillDefinition>): List<SkillDefinition> {
    val order = listOf(
        TypeInferer.CHAT, TypeInferer.CHATROOM, TypeInferer.CLASSROOM,
        TypeInferer.ELI5, TypeInferer.FAMILIAR, TypeInferer.ROUNDTABLE
    )
    return defs.sortedBy { def -> order.indexOf(def.id).let { if (it < 0) order.size else it } }
}

/** skillParams 列 JSON → 字符串键值（与 SkillHost.chatroomParams 同口径：非字符串值滤除） */
private fun stringParams(conv: ConversationEntity): Map<String, String> {
    val source = conv.skillParams?.takeIf { it.isNotBlank() } ?: return emptyMap()
    val obj = try {
        com.google.gson.Gson().fromJson(source, Map::class.java)
    } catch (_: Exception) {
        null
    } ?: return emptyMap()
    return obj.entries.mapNotNull { (k, v) ->
        val key = k as? String ?: return@mapNotNull null
        (v as? String)?.let { key to it }
    }.toMap()
}

@Composable
private fun SkillCard(
    skill: SkillDefinition,
    conversations: List<ConversationEntity>,
    onEditClassroom: (ConversationEntity) -> Unit,
    onEditRoundtable: (ConversationEntity) -> Unit,
    onEditFamiliar: (ConversationEntity) -> Unit,
    onEditRoom: (Long) -> Unit
) {
    PixelPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp
        )
    ) {
        Text(
            text = skill.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = skill.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (conversations.isEmpty()) {
            Text(
                text = EDIT_HINTS[skill.id] ?: "暂无会话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
            )
        } else {
            conversations.forEach { conv ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = conv.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatDate(conv.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    when (skill.id) {
                        TypeInferer.CLASSROOM -> TextButton(onClick = { onEditClassroom(conv) }) {
                            Text("编辑参数", style = MaterialTheme.typography.labelMedium)
                        }
                        TypeInferer.ROUNDTABLE -> TextButton(onClick = { onEditRoundtable(conv) }) {
                            Text("编辑话题", style = MaterialTheme.typography.labelMedium)
                        }
                        TypeInferer.CHATROOM -> TextButton(onClick = { onEditRoom(conv.id) }) {
                            Text("编辑成员", style = MaterialTheme.typography.labelMedium)
                        }
                        TypeInferer.FAMILIAR -> TextButton(onClick = { onEditFamiliar(conv) }) {
                            Text("编辑参数", style = MaterialTheme.typography.labelMedium)
                        }
                        else -> {}
                    }
                }
            }
            // 有会话的类型也补一句说明（chat 的参数在别处；familiar 附采样入口指引）
            EDIT_HINTS[skill.id]?.let { hint ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
                )
            }
        }
    }
}

/** 无独立参数编辑器的类型的说明（有编辑器的类型在有会话时也可附注） */
private val EDIT_HINTS = mapOf(
    TypeInferer.CHAT to "系统提示词在建会话时从模板选择（首页 + → 从模板新建）",
    TypeInferer.ELI5 to "零参数技能",
    TypeInferer.FAMILIAR to "称呼/说话模式在 右上角滑杆图标→设置→大肥鱼；采样参数点上方「编辑参数」",
    TypeInferer.CLASSROOM to "课堂参数在有会话后点「编辑参数」改",
    TypeInferer.CHATROOM to "成员在会话内编辑（长按进入会话 → 成员编辑页）",
)

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
