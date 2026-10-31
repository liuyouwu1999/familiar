package dev.liuyouwu.familiar.ui.screen.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import dev.liuyouwu.familiar.ui.theme.GrassGreen
import dev.liuyouwu.familiar.ui.theme.PaperLight
import dev.liuyouwu.familiar.ui.theme.PixelDivider
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.PixelPanel
import dev.liuyouwu.familiar.ui.theme.SkyBlue
import dev.liuyouwu.familiar.ui.theme.StarGold
import dev.liuyouwu.familiar.ui.theme.WoodBorder
import dev.liuyouwu.familiar.ui.theme.pixelFrame
import dev.liuyouwu.familiar.ui.theme.pixelPanelColors
import dev.liuyouwu.familiar.pet.FattyFishAvatar
import dev.liuyouwu.familiar.util.parseColorHex
import dev.liuyouwu.familiar.viewmodel.ChatListViewModel
import dev.liuyouwu.familiar.viewmodel.ChatViewModel
import dev.liuyouwu.familiar.viewmodel.ConversationWithPreview
import dev.liuyouwu.familiar.viewmodel.FamiliarHeaderState
import dev.liuyouwu.familiar.viewmodel.MessageSearchHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 会话列表本体（P5 T6 自 ChatListScreen 抽出）：主精灵常驻卡 + 搜索框（含技能参数入口）
 * + 空态引导 + 会话列表 + 跨会话消息内容搜索分区 + 会话删除确认。
 * 搜索状态（300ms 防抖）与删除确认态自持；新建/模板流向经回调交组合壳。
 */
@Composable
internal fun ChatListContent(
    viewModel: ChatListViewModel,
    chatViewModel: ChatViewModel,
    onChatClick: (Long) -> Unit,
    onOpenFamiliar: () -> Unit,
    onStartFromTemplate: (PromptTemplateEntity) -> Unit,  // 批次二：空态三格已删，参数保留防接线破坏
    onOpenNewChatMenu: () -> Unit = {},   // 空态「开始新对话」面板 = FAB 同效（用户实测反馈 2026-10-31）
    onSkillsClick: () -> Unit
) {

    // ===== 主精灵（familiar）：首页卡 =====
    val familiarHeader by chatViewModel.familiarHeader.collectAsStateWithLifecycle()
    val petColor by chatViewModel.petColor.collectAsStateWithLifecycle()

    // Get conversations with preview data
    val conversationsWithPreview by viewModel.conversationsWithPreview.collectAsStateWithLifecycle()
    val allMessages by viewModel.allMessages.collectAsStateWithLifecycle()

    // ===== 搜索（标题过滤 + 跨会话消息内容）=====
    var searchQuery by remember { mutableStateOf("") }
    // 跨会话消息内容搜索（输入 ≥2 字符后追加"消息内容"分区）
    val messageHits by viewModel.messageSearchResults.collectAsStateWithLifecycle()
    val isSearchingMessages by viewModel.isSearchingMessages.collectAsStateWithLifecycle()
    val isMessageSearch = searchQuery.trim().length >= 2

    // 消息内容搜索 300ms 防抖：每次输入重启 LaunchedEffect，停顿满 300ms 才真正发起查询
    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length >= 2) {
            delay(300)
            viewModel.searchMessages(q)
        } else {
            viewModel.clearMessageSearch()
        }
    }

    // Delete confirmation
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }

    // Filter conversations by search query (searches title and message content)
    val filteredConversations = remember(conversationsWithPreview, searchQuery, allMessages) {
        if (searchQuery.isBlank()) conversationsWithPreview
        else {
            val matchingConvIds = allMessages
                .filter { it.content.contains(searchQuery, ignoreCase = true) }
                .map { it.conversationId }
                .toSet()

            conversationsWithPreview.filter { conv ->
                conv.title.contains(searchQuery, ignoreCase = true) || conv.id in matchingConvIds
            }
        }
    }

    // Delete confirmation dialog
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("删除对话「${deleteTarget?.title}」？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget?.let { viewModel.deleteConversation(it.id) }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 主精灵常驻卡（LazyColumn 之上固定）：点击进入 familiar 会话，长按换卡
        // 换卡入口在 设置→大肥鱼→更换人设卡（首页长按容易误触且看不懂，已撤）
        FamiliarHeaderCard(
            state = familiarHeader,
            petColor = petColor,
            onOpen = onOpenFamiliar,
        )

        // Search bar（常驻显示，避免空态/有数据切换时顶栏跳变）；尾部 Tune = 技能参数入口
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("搜索对话…") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            trailingIcon = {
                PixelIconButton(
                    icon = Icons.Default.Tune,
                    contentDescription = "技能参数",
                    onClick = onSkillsClick
                )
            }
        )

        if (conversationsWithPreview.isEmpty()) {
            // 像素风空状态：星星 + 面板引导
            // 底部留出 FAB 高度+间距：Arrangement.Center 以"搜索框↔FAB"之间的区域为参照，
            // 否则光学重心偏下（用户实测反馈）
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 88.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row {
                    repeat(3) { index ->
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = StarGold,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (index == 1) 34.dp else 24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                PixelPanel(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenNewChatMenu
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "开始新对话",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "点击开始新对话；右下角 + 可选类型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (filteredConversations.isEmpty() && !isMessageSearch) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "无搜索结果",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else if (isMessageSearch && filteredConversations.isEmpty() &&
            messageHits.isEmpty() && !isSearchingMessages
        ) {
            // 会话标题与消息正文都没命中
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "无匹配消息",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = filteredConversations,
                    key = { it.id }
                ) { conv ->
                    ConversationItem(
                        conversation = conv,
                        onClick = { onChatClick(conv.id) },
                        onDelete = { deleteTarget = ConversationEntity(
                            id = conv.id,
                            title = conv.title,
                            systemPrompt = "",
                            createdAt = conv.updatedAt,
                            updatedAt = conv.updatedAt,
                            isChatRoom = conv.isChatRoom
                        )},
                        onTogglePin = { viewModel.togglePinned(conv.id) }
                    )
                }

                // 消息内容分区：输入 ≥2 字符后追加，展示跨会话的正文命中
                if (isMessageSearch) {
                    item(key = "message_section_header") {
                        Column {
                            PixelDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "消息内容",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSearchingMessages) "搜索中…"
                                    else if (messageHits.isEmpty()) "无匹配消息"
                                    else "${messageHits.size} 条命中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (!isSearchingMessages && messageHits.isNotEmpty()) {
                        items(
                            items = messageHits,
                            key = { hit -> "msg_${hit.conversationId}_${hit.timestamp}_${hit.snippet.hashCode()}" }
                        ) { hit ->
                            MessageSearchResultItem(
                                hit = hit,
                                onClick = { onChatClick(hit.conversationId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 消息内容搜索结果项：会话标题 + 命中片段 + 时间，点击跳转到该会话。
 */
@Composable
private fun MessageSearchResultItem(
    hit: MessageSearchHit,
    onClick: () -> Unit
) {
    PixelPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        onClick = onClick,
        contentPadding = PaddingValues(
            start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = GrassGreen
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = hit.conversationTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatDate(hit.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = hit.snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 主陪伴首页卡（LazyColumn 之上固定）：默认陪伴按当前皮肤立绘，上古选卡残留走卡色方块+卡名首字，
 * 副标题「{address}，在呢」。
 * 点击→进入/新建 familiar 会话；长按→换卡弹窗（生成中禁换，守卫在调用方）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FamiliarHeaderCard(
    state: FamiliarHeaderState,
    petColor: String,
    onOpen: () -> Unit
) {
    val avatarColor = parseColorHex(state.colorHex)
    // 当前皮肤的人设名（personaOf 四分支产物，与 ChatViewModel 的 cardName 同源同链）：
    // remember(petColor) 记忆，避免 recomposition 重复现扫皮肤目录
    val petPersonaName = remember(petColor) {
        dev.liuyouwu.familiar.util.persona.DefaultPersonaCards.personaOf(
            dev.liuyouwu.familiar.pet.PetSkinStore.get(petColor)
        ).name
    }

    PixelPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onOpen),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像：默认陪伴按当前皮肤立绘（门控比对当前皮肤的人设名，自定义皮肤名也落立绘分支）；
            // 上古选卡残留走方块（防护保留）。换卡入口已撤（:155），陪伴卡恒为这条鱼
            if (state.cardName == petPersonaName) {
                FattyFishAvatar(skinId = petColor, modifier = Modifier.size(44.dp))
            } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pixelFrame(
                        container = avatarColor,
                        frame = avatarColor,
                        highlight = PaperLight.copy(alpha = 0.45f),
                        shadow = WoodBorder.copy(alpha = 0.45f),
                        border = WoodBorder,
                        bevel = 3.dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.cardName.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = PaperLight
                )
            }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.cardName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${state.address}，在呢",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = StarGold,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: ConversationWithPreview,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    PixelPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = { showMenu = true }
            ),
        colors = if (conversation.isPinned)
            pixelPanelColors().copy(container = MaterialTheme.colorScheme.primaryContainer)
        else pixelPanelColors(),
        contentPadding = PaddingValues(
            start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (conversation.isPinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(14.dp),
                    tint = StarGold
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (conversation.isChatRoom) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Default.Group,
                    contentDescription = "聊天室",
                    modifier = Modifier.size(16.dp),
                    tint = SkyBlue
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Last message preview
        if (!conversation.lastMessage.isNullOrBlank()) {
            Text(
                text = conversation.lastMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDate(conversation.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "· ${conversation.messageCount} 条消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }

    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text(if (conversation.isPinned) "取消置顶" else "置顶对话") },
            onClick = {
                showMenu = false
                onTogglePin()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = null,
                    tint = if (conversation.isPinned)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        )
        DropdownMenuItem(
            text = { Text("删除对话") },
            onClick = {
                showMenu = false
                onDelete()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
