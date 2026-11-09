package dev.liuyouwu.familiar.ui.screen.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liuyouwu.familiar.ui.model.PersonaPresetUi
import dev.liuyouwu.familiar.ui.model.PromptTemplateUi
import dev.liuyouwu.familiar.ui.component.SettingsDirtyTextField
import dev.liuyouwu.familiar.ui.component.SettingsTextFieldState
import dev.liuyouwu.familiar.ui.theme.PixelBottomPanel
import dev.liuyouwu.familiar.ui.theme.PixelButton
import dev.liuyouwu.familiar.ui.theme.pixelSecondaryAlpha
import dev.liuyouwu.familiar.util.parseColorHex
import dev.liuyouwu.familiar.viewmodel.ChatListViewModel

/**
 * 预设管理弹窗群（P5 T6 自 ChatListScreen 抽出，预设域）：模板选择/变量填充/
 * 预设管理（系统提示词+角色预设双 Tab）/模板与角色预设的增删改。
 * 弹窗开关与跨弹窗共享的编辑态收拢在 [PresetDialogState]（组合壳 remember 一份传入）；
 * 数据流经 [ChatListViewModel]，创建会话回调整壳导航（onCreated）。
 */
internal class PresetDialogState {
    var showSelector by mutableStateOf(false)
    var showManager by mutableStateOf(false)
    var showCreateTemplate by mutableStateOf(false)
    var editingTemplate by mutableStateOf<PromptTemplateUi?>(null)
    var deleteTemplateTarget by mutableStateOf<PromptTemplateUi?>(null)
    var deletePersonaTarget by mutableStateOf<PersonaPresetUi?>(null)

    // 变量模板填充：选中的模板含 {{变量}} 占位符时弹出像素风填充面板
    var templateFillTarget by mutableStateOf<PromptTemplateUi?>(null)
    var templateVarValues by mutableStateOf<Map<String, String>>(emptyMap())

    // Persona preset editing（人设卡 v2 五输入域 + 隐藏保留的 selfName/address，
    // 保存时原样回传防抹掉）
    var editingPersona by mutableStateOf<PersonaPresetUi?>(null)
    var showCreatePersona by mutableStateOf(false)
    var personaName by mutableStateOf("")
    var personaPersonality by mutableStateOf("")
    var personaColor by mutableStateOf("#1976D2")
    var personaIdentity by mutableStateOf("")
    var personaTraits by mutableStateOf("")
    var personaSpeechStyle by mutableStateOf("")
    var personaCatchphrase by mutableStateOf("")
    var personaExampleDialogs by mutableStateOf("")
    var personaSelfName by mutableStateOf("我")
    var personaAddress by mutableStateOf("主人")

    /** 管理列表点编辑：编辑弹窗自持表单态，此处只装目标实体 */
    fun beginEditTemplate(template: PromptTemplateUi) {
        editingTemplate = template
    }

    /** 管理列表点编辑：人设卡全字段装载（selfName/address 空值回默认，保存原样回传） */
    fun beginEditPersona(preset: PersonaPresetUi) {
        editingPersona = preset
        personaName = preset.name
        personaPersonality = preset.personality
        personaColor = preset.colorHex
        personaIdentity = preset.identity
        personaTraits = preset.traits
        personaSpeechStyle = preset.speechStyle
        personaCatchphrase = preset.catchphrase
        personaExampleDialogs = preset.exampleDialogs
        personaSelfName = preset.selfName.ifBlank { "我" }
        personaAddress = preset.address.ifBlank { "主人" }
    }

    /** 管理列表「新建」（角色预设 Tab）：字段复位 + 打开新建弹窗 */
    fun beginCreatePersona() {
        personaName = ""
        personaPersonality = ""
        personaColor = "#1976D2"
        personaIdentity = ""
        personaTraits = ""
        personaSpeechStyle = ""
        personaCatchphrase = ""
        personaExampleDialogs = ""
        personaSelfName = "我"
        personaAddress = "主人"
        showCreatePersona = true
    }
}

/** 预设弹窗群入口（组合壳挂载点；各弹窗自判开关，全部关闭时零组合开销） */
@Composable
internal fun PresetDialogs(
    state: PresetDialogState,
    viewModel: ChatListViewModel,
    onStartFromTemplate: (PromptTemplateUi) -> Unit,
    onCreated: (Long) -> Unit
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val personaPresets by viewModel.personaPresets.collectAsStateWithLifecycle()

    TemplateSelectorDialog(
        visible = state.showSelector,
        templates = templates,
        onSelect = {
            // 含 {{变量}} 的模板会先弹填充面板，确认后创建
            onStartFromTemplate(it)
        },
        onNoPrompt = {
            viewModel.createConversation("") { id -> onCreated(id) }
            state.showSelector = false
        },
        onDismiss = { state.showSelector = false }
    )

    // 变量模板填充面板：每个 {{变量}} 一个输入框，确认后全部替换再创建；取消则不创建
    state.templateFillTarget?.let { fillTemplate ->
        TemplateFillPanel(
            template = fillTemplate,
            values = state.templateVarValues,
            onValueChange = { name, v -> state.templateVarValues = state.templateVarValues + (name to v) },
            onCancel = {
                // 取消：不创建会话；模板选择面板保持打开，可另选模板
                state.templateFillTarget = null
            },
            onConfirm = { filled ->
                state.templateFillTarget = null
                state.showSelector = false
                viewModel.createConversation(filled) { id -> onCreated(id) }
            }
        )
    }

    TemplateManagerDialog(
        visible = state.showManager,
        templates = templates,
        personaPresets = personaPresets,
        onEditTemplate = state::beginEditTemplate,
        onDeleteTemplate = { state.deleteTemplateTarget = it },
        onEditPersona = state::beginEditPersona,
        onDeletePersona = { state.deletePersonaTarget = it },
        onCreateTemplate = {
            state.showManager = false
            state.showCreateTemplate = true
        },
        onCreatePersona = {
            state.showManager = false
            state.beginCreatePersona()
        },
        onDismiss = { state.showManager = false }
    )

    TemplateEditorDialog(
        visible = state.editingTemplate != null,
        title = "编辑模板",
        initialName = state.editingTemplate?.name.orEmpty(),
        initialContent = state.editingTemplate?.content.orEmpty(),
        confirmLabel = "保存",
        onConfirm = { name, content ->
            state.editingTemplate?.let { viewModel.updateTemplate(it.id, name, content) }
            state.editingTemplate = null
        },
        onDismiss = { state.editingTemplate = null }
    )

    TemplateEditorDialog(
        visible = state.showCreateTemplate,
        title = "新建模板",
        initialName = "",
        initialContent = "",
        confirmLabel = "创建",
        onConfirm = { name, content ->
            viewModel.addTemplate(name, content)
            state.showCreateTemplate = false
        },
        onDismiss = { state.showCreateTemplate = false }
    )

    PersonaPresetDialog(
        title = "编辑角色预设",
        visible = state.editingPersona != null,
        state = state,
        confirmLabel = "保存",
        onConfirm = {
            state.editingPersona?.let { preset ->
                viewModel.updatePersonaPreset(
                    preset.id, state.personaName, state.personaPersonality, state.personaColor,
                    state.personaIdentity, state.personaTraits, state.personaSpeechStyle,
                    state.personaCatchphrase, state.personaExampleDialogs,
                    state.personaSelfName, state.personaAddress,
                )
            }
            state.editingPersona = null
        },
        onDismiss = { state.editingPersona = null }
    )

    PersonaPresetDialog(
        title = "新建角色预设",
        visible = state.showCreatePersona,
        state = state,
        confirmLabel = "创建",
        onConfirm = {
            if (state.personaName.isNotBlank() && state.personaPersonality.isNotBlank()) {
                viewModel.addPersonaPreset(
                    state.personaName, state.personaPersonality, state.personaColor,
                    state.personaIdentity, state.personaTraits, state.personaSpeechStyle,
                    state.personaCatchphrase, state.personaExampleDialogs,
                    state.personaSelfName, state.personaAddress,
                )
            }
            state.showCreatePersona = false
        },
        onDismiss = { state.showCreatePersona = false }
    )

    DeleteNamedConfirmDialog(
        target = state.deleteTemplateTarget,
        title = "删除模板",
        label = { "删除模板「${it.name}」？" },
        onConfirm = {
            state.deleteTemplateTarget?.let { viewModel.deleteTemplate(it) }
            state.deleteTemplateTarget = null
        },
        onDismiss = { state.deleteTemplateTarget = null }
    )

    DeleteNamedConfirmDialog(
        target = state.deletePersonaTarget,
        title = "删除角色预设",
        label = { "删除角色预设「${it.name}」？" },
        onConfirm = {
            state.deletePersonaTarget?.let { viewModel.deletePersonaPreset(it) }
            state.deletePersonaTarget = null
        },
        onDismiss = { state.deletePersonaTarget = null }
    )
}

// ===== 模板选择（新建会话时） =====

@Composable
private fun TemplateSelectorDialog(
    visible: Boolean,
    templates: List<PromptTemplateUi>,
    onSelect: (PromptTemplateUi) -> Unit,
    onNoPrompt: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择系统提示词") },
        text = {
            LazyColumn {
                items(templates) { template ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelect(template) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                if (template.isDefault) {
                                    Text(
                                        text = "默认",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = template.content.take(80) + if (template.content.length > 80) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha()),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // "No prompt" option
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onNoPrompt() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = "无系统提示词",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ===== 变量模板填充面板 =====

@Composable
private fun TemplateFillPanel(
    template: PromptTemplateUi,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val vars = remember(template) { templateVariables(template.content) }
    PixelBottomPanel(
        onDismiss = onCancel,
        title = "填写模板变量"
    ) {
        Text(
            text = "「${template.name}」包含 ${vars.size} 个变量，填写后创建对话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        vars.forEach { name ->
            OutlinedTextField(
                value = values[name] ?: "",
                onValueChange = { onValueChange(name, it) },
                label = { Text(name) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PixelButton(
                text = "取消",
                onClick = onCancel,
                container = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            PixelButton(
                text = "创建对话",
                onClick = { onConfirm(fillTemplateVars(template.content, values)) },
                enabled = values.values.all { it.isNotBlank() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ===== 预设管理（双 Tab：系统提示词 / 角色预设） =====

@Composable
private fun TemplateManagerDialog(
    visible: Boolean,
    templates: List<PromptTemplateUi>,
    personaPresets: List<PersonaPresetUi>,
    onEditTemplate: (PromptTemplateUi) -> Unit,
    onDeleteTemplate: (PromptTemplateUi) -> Unit,
    onEditPersona: (PersonaPresetUi) -> Unit,
    onDeletePersona: (PersonaPresetUi) -> Unit,
    onCreateTemplate: () -> Unit,
    onCreatePersona: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    var selectedTab by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预设管理") },
        text = {
            Column {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("系统提示词") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("角色预设") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                when (selectedTab) {
                    0 -> {
                        // System prompt templates
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(templates) { template ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = template.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = template.content.take(40) + if (template.content.length > 40) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                    IconButton(onClick = { onEditTemplate(template) }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    if (!template.isDefault) {
                                        IconButton(onClick = { onDeleteTemplate(template) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        // Persona presets
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(personaPresets) { preset ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
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
                                            text = preset.personality.take(30) + if (preset.personality.length > 30) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                    IconButton(onClick = { onEditPersona(preset) }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { onDeletePersona(preset) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    if (selectedTab == 0) onCreateTemplate() else onCreatePersona()
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新建")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}

// ===== 模板编辑/新建（同构双弹窗合一：标题/初值/确认动作参数化） =====

/**
 * 模板编辑器弹窗：编辑与新建共用一份表单体（原两份重复 AlertDialog 合一）。
 * 表单态随弹窗自持（进入组合时从初值装载——编辑经 state.editingTemplate 传入实体，
 * 新建传空），可用 {{变量}} 占位。表单态载体换用共享 SettingsTextFieldState+
 * SettingsDirtyTextField（审计 A6：无外部真值流跟随的表单播种形态——键入置脏无害，
 * 重开弹窗经 remember(key) 重播种，装载语义与原 var by remember(key) 一致）。
 */
@Composable
private fun TemplateEditorDialog(
    visible: Boolean,
    title: String,
    initialName: String,
    initialContent: String,
    confirmLabel: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val nameState = remember(initialName, initialContent) { SettingsTextFieldState(initialName) }
    val contentState = remember(initialName, initialContent) { SettingsTextFieldState(initialContent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SettingsDirtyTextField(nameState, "模板名称", Modifier.fillMaxWidth(), singleLine = false)
                Spacer(modifier = Modifier.height(8.dp))
                SettingsDirtyTextField(
                    contentState, "提示词内容",
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    singleLine = false
                )
                Text(
                    text = "可用 {{变量}} 占位，从模板新建时逐项填写",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(nameState.value.orEmpty(), contentState.value.orEmpty()) },
                enabled = !nameState.value.isNullOrBlank() && !contentState.value.isNullOrBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ===== 角色预设编辑/新建（表单域共用，确认动作分派） =====

/** 人设卡编辑表单（原编辑/新建弹窗两份重复的七输入域+色板合一；selfName/address 无输入框，随 state 隐性保留） */
@Composable
private fun PersonaPresetFields(state: PresetDialogState) {
    val presetColors = listOf("#1976D2", "#D32F2F", "#388E3C", "#F57C00", "#7B1FA2", "#00838F", "#C2185B", "#455A64")
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = state.personaName,
            onValueChange = { state.personaName = it },
            label = { Text("名称") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.personaPersonality,
            onValueChange = { state.personaPersonality = it },
            label = { Text("性格描述") },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.personaIdentity,
            onValueChange = { state.personaIdentity = it },
            label = { Text("身份") },
            placeholder = { Text("一句话：你是谁、从哪来") },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.personaTraits,
            onValueChange = { state.personaTraits = it },
            label = { Text("性格特质") },
            placeholder = { Text("3-5 个词条，用；分隔，留一处反差") },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.personaSpeechStyle,
            onValueChange = { state.personaSpeechStyle = it },
            label = { Text("说话方式") },
            placeholder = { Text("句式长短、语气、用词习惯") },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.personaCatchphrase,
            onValueChange = { state.personaCatchphrase = it },
            label = { Text("口头禅") },
            placeholder = { Text("一句标志性短句") },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.personaExampleDialogs,
            onValueChange = { state.personaExampleDialogs = it },
            label = { Text("示例对话") },
            placeholder = { Text("三组对话，用 <START> 分隔，每行\"主人：\"或\"角色名：\"开头") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("颜色", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presetColors.forEach { hex ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(parseColorHex(hex))
                        .clickable { state.personaColor = hex },
                    contentAlignment = Alignment.Center
                ) {
                    if (hex == state.personaColor) {
                        Icon(
                            Icons.Default.Edit,
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

@Composable
private fun PersonaPresetDialog(
    title: String,
    visible: Boolean,
    state: PresetDialogState,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { PersonaPresetFields(state) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.personaName.isNotBlank() && state.personaPersonality.isNotBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ===== 删除确认（模板/角色预设） =====

@Composable
private fun <T> DeleteNamedConfirmDialog(
    target: T?,
    title: String,
    label: (T) -> String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (target == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(label(target)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ===== 变量模板 =====

/** 模板正文里的 {{变量名}} 占位符（变量名不含花括号） */
private val TEMPLATE_VAR_REGEX = Regex("\\{\\{([^{}]+)\\}\\}")

/** 提取模板里的全部唯一变量名（保持出现顺序，名字去首尾空白） */
internal fun templateVariables(content: String): List<String> =
    TEMPLATE_VAR_REGEX.findAll(content)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

/** 用填好的值替换模板正文里对应的 {{变量}}，得到最终 systemPrompt */
internal fun fillTemplateVars(content: String, values: Map<String, String>): String =
    TEMPLATE_VAR_REGEX.replace(content) { match ->
        values[match.groupValues[1].trim()] ?: match.value
    }
