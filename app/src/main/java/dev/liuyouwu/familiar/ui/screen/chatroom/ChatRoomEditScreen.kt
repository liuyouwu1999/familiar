package dev.liuyouwu.familiar.ui.screen.chatroom

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.model.ChatRoomParticipantUi
import dev.liuyouwu.familiar.ui.model.PersonaPresetUi
import dev.liuyouwu.familiar.core.llm.wire.ModelInfo
import dev.liuyouwu.familiar.util.parseColorHex
import dev.liuyouwu.familiar.util.persona.DefaultPersonaCards
import dev.liuyouwu.familiar.viewmodel.toUi
import kotlinx.coroutines.launch
import java.util.UUID

private val PRESET_COLORS = listOf(
    "#1976D2", "#D32F2F", "#388E3C", "#F57C00",
    "#7B1FA2", "#00838F", "#C2185B", "#455A64"
)

// 内置预设收编至 DefaultPersonaCards（两屏共用 + DB 种子同源）

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomEditScreen(
    conversationId: Long,
    currentModelName: String,
    availableModels: List<ModelInfo>,
    existingParticipants: List<ChatRoomParticipantUi>,
    customPresets: List<PersonaPresetUi>,
    // 保存改为协程回调：onResult(null)=成功（提示"已保存"），非 null=错误文案
    onSave: (List<ParticipantDraft>, (String?) -> Unit) -> Unit,
    onSavePreset: (PersonaPresetUi) -> Unit,
    onBack: () -> Unit
) {
    var participants by remember { mutableStateOf<List<ParticipantDraft>>(emptyList()) }
    var initialized by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Initialize from existing participants when they load
    LaunchedEffect(existingParticipants) {
        if (existingParticipants.isNotEmpty() && !initialized) {
            participants = existingParticipants.map {
                ParticipantDraft(
                    id = UUID.randomUUID().toString(), // Generate new ID for editing
                    modelName = it.modelName,
                    displayName = it.displayName,
                    personality = it.personality,
                    colorHex = it.colorHex,
                    // v2 卡字段随实体带入：编辑保存（先删后插）不丢 identity/traits
                    identity = it.identity,
                    traits = it.traits,
                    speechStyle = it.speechStyle,
                    catchphrase = it.catchphrase,
                    exampleDialogs = it.exampleDialogs
                )
            }
            initialized = true
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetToSave by remember { mutableStateOf<ParticipantDraft?>(null) }

    Scaffold(
        topBar = {
            dev.liuyouwu.familiar.ui.theme.PixelTopBar(
                title = "编辑角色",
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
                        contentDescription = "保存",
                        onClick = {
                            if (participants.size in 2..4 && !isSaving) {
                                isSaving = true
                                onSave(participants) { err ->
                                    scope.launch {
                                        isSaving = false
                                        val job = launch { snackbarHostState.showSnackbar(err ?: "已保存") }
                                        if (err == null) {
                                            // 成功：短暂提示后返回；失败：停留本页等待修改
                                            kotlinx.coroutines.delay(700)
                                            job.cancel()
                                            onBack()
                                        }
                                    }
                                }
                            }
                        },
                        enabled = participants.size in 2..4,
                        tint = dev.liuyouwu.familiar.ui.theme.PaperLight
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(participants.toList(), key = { it.id }) { participant ->
                EditableParticipantCard(
                    participant = participant,
                    customPresets = customPresets,
                    availableModels = availableModels.mapNotNull { it.id },
                    canRemove = participants.size > 2,
                    onRemove = {
                        participants = participants.filter { it.id != participant.id }
                    },
                    onUpdate = { updated ->
                        participants = participants.map { if (it.id == participant.id) updated else it }
                    },
                    onSaveAsPreset = { presetToSave = it; showSavePresetDialog = true }
                )
            }

            if (participants.size < 4) {
                item {
                    OutlinedButton(
                        shape = RectangleShape,
                        onClick = { showAddDialog = true },
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

    if (showAddDialog) {
        AddParticipantDialog(
            currentModelName = currentModelName,
            customPresets = customPresets,
            participantCount = participants.size,
            onAdd = { draft ->
                participants = participants + draft
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

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

@Composable
private fun EditableParticipantCard(
    participant: ParticipantDraft,
    customPresets: List<PersonaPresetUi>,
    availableModels: List<String>,
    canRemove: Boolean,
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
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                // Save as preset button
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
                // Delete button
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(24.dp),
                    enabled = canRemove
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = if (canRemove) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
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

            // 每个角色可绑定不同模型（主持人调度按角色模型调用）
            dev.liuyouwu.familiar.ui.component.ModelDropdownField(
                selected = participant.modelName,
                models = availableModels,
                onSelected = { onUpdate(participant.copy(modelName = it)) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Quick presets row
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

// parseColorHex 收编至 util/PixelColorUtil（审计 P3：三份相同实现合一）
