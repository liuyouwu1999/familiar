package dev.liuyouwu.familiar.ui.screen.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.tools.mcp.McpServerConfig
import dev.liuyouwu.familiar.tools.mcp.McpToolManager
import dev.liuyouwu.familiar.ui.theme.GrassGreen
import dev.liuyouwu.familiar.ui.theme.PaperLight
import dev.liuyouwu.familiar.ui.theme.PixelDivider
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.PixelTopBar
import kotlinx.coroutines.launch

/**
 * MCP 设置子屏（T4，"mcp" 路由——设置弹窗大肥鱼分区后的「MCP 工具」入口进入）：
 * - 全局 Switch：[SettingsRepository.mcpEnabled] 总开关（关 = manager 快照清空且不发起
 *   任何连接——refresh(false) 口径；单服务器启停不各自建/断，全量重发现统一收口）；
 * - 服务器列表行：名称/URL/状态点（[McpToolManager.statusFlow] 驱动——就绪绿/错误红/
 *   未连接灰，颜色判据与其私有状态字面同源）+ 工具数 + 行内启停 Switch；
 * - 点行 = 编辑弹窗（名称/URL/token 三字段）；token 留空 = 不修改原 token
 *   （[SettingsRepository.saveMcpServer] tokenPlain=null 语义；无清空 token 的入口，
 *    重录 token 即替换）；
 * - "+" 添加 = 同一弹窗空表单；
 * - 长按行 = 删除确认（AlertDialog 两级确认）。
 * 校验五件套：名称空/URL 空/[URL http(s) 前缀]/与现存名撞（重名拒存——I-2）/requireLegalName
 * （拒存判据唯一权威，异常消息直显）；编辑态改名 = 真重命名（先删旧行再存——I-2(a)，
 * 防旧名残留致同 URL 双份发现）。[SettingsRepository.saveMcpServer] 返回 false =
 * Keystore 不可用已明文回退——关弹窗后 snackbar 明说（同「先关弹窗再弹提示」先例，
 * 防被对话框遮住）。
 * 读写全部直连 [SettingsRepository]（无状态轻量门面，AppNavigation 就地装配传入，
 * 与导航层既有直连先例同款）；刷新链在 AgentSessionController 常驻收集，本屏只展示。
 */
@Composable
fun SettingsMcpScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // DataStore 真值流：初值空/false 占位，真值到达自然纠正（SettingsScreens 先例同款）
    val servers by settingsRepository.mcpServers.collectAsStateWithLifecycle(initialValue = emptyList())
    val mcpEnabled by settingsRepository.mcpEnabled.collectAsStateWithLifecycle(initialValue = false)
    // 服务器状态流（manager 侧 refresh 同步更新；本屏是它的 UI 消费者）
    val statuses by McpToolManager.statusFlow().collectAsStateWithLifecycle()

    // 弹窗状态：editing 非空 = 编辑该服务器；showEditor = 添加空表单；deleting = 删除确认
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<McpServerConfig?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PixelTopBar(
                title = "MCP 工具",
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
                .padding(horizontal = 16.dp)
        ) {
            // 全局总开关
            item {
                Spacer(modifier = Modifier.size(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用 MCP", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "关闭后不连接任何 MCP 服务器，它的工具也不会出现在对话里",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Switch(
                        checked = mcpEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setMcpEnabled(it) } }
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
            }
            if (servers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "还没有服务器，点下方「添加服务器」",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            items(servers, key = { it.name }) { server ->
                val status = statuses.firstOrNull { it.name == server.name }
                McpServerRow(
                    server = server,
                    statusState = status?.state,
                    toolCount = status?.toolCount ?: 0,
                    onToggle = { on ->
                        // 行内启停：按 name upsert（tokenCipher 原值透传、tokenPlain=null 不动 token）
                        scope.launch { settingsRepository.saveMcpServer(server.copy(enabled = on), null) }
                    },
                    onEdit = { editing = server },
                    onDelete = { deleting = server },
                )
                PixelDivider()
            }
            item {
                Spacer(modifier = Modifier.size(8.dp))
                TextButton(onClick = { showEditor = true }) {
                    Text("＋ 添加服务器")
                }
                Spacer(modifier = Modifier.size(16.dp))
                Text(
                    "服务器连上后，它的工具出现在对话里；改动下一轮对话生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                Spacer(modifier = Modifier.size(16.dp))
            }
        }
    }

    // 编辑/添加弹窗（编辑 = 现值预填 + token 留空不改）
    if (editing != null || showEditor) {
        McpServerEditDialog(
            editing = editing,
            existingNames = servers.map { it.name },
            onDismiss = {
                editing = null
                showEditor = false
            },
            onSave = { config, tokenPlain, replaceName ->
                scope.launch {
                    // I-2(a)（评审 Important 2）：编辑改名 = 真重命名——先删旧行（幂等）
                    // 再 upsert 新行。不删则旧名残留 + 新名追加：同 URL 两台重复发现、
                    // 全部工具双份上 wire。
                    replaceName?.let { settingsRepository.deleteMcpServer(it) }
                    // false = Keystore 不可用已明文回退（Task 1 Boolean 透传口径）：
                    // 先关弹窗再弹提示（同设置弹窗先例——否则 snackbar 被对话框遮住）
                    val encryptedOk = settingsRepository.saveMcpServer(config, tokenPlain)
                    editing = null
                    showEditor = false
                    if (!encryptedOk) {
                        snackbarHostState.showSnackbar("设备安全模块不可用：token 已按明文本地保存")
                    }
                }
            },
        )
    }

    // 删除确认（长按行进入）
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除服务器") },
            text = { Text("删除「${target.name}」？它的工具也会从对话里移除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { settingsRepository.deleteMcpServer(target.name) }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 服务器列表行（名称/URL/状态点/工具数 + 行内启停 Switch；点行编辑、长按删除）。
 * 状态点颜色判据与 [McpToolManager] 私有状态字面同源（"就绪"/"错误:"前缀/其余=
 * 未连接灰）——manager 侧常量不外露，display 字面即契约（statusFlow KDoc 同口径）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun McpServerRow(
    server: McpServerConfig,
    statusState: String?,
    toolCount: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dotColor = when {
        statusState == "就绪" -> GrassGreen
        statusState?.startsWith("错误:") == true -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)   // 未连接/状态未回
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onEdit, onLongClick = onDelete)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(dotColor, RectangleShape)
        )
        Spacer(modifier = Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, style = MaterialTheme.typography.bodyMedium)
                if (statusState == "就绪") {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        "$toolCount 个工具",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            Text(
                server.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (statusState?.startsWith("错误:") == true) {
                Text(
                    statusState,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Switch(checked = server.enabled, onCheckedChange = onToggle)
    }
}

/**
 * 编辑/添加弹窗（名称/URL/token 三字段）。[editing] = null 时为添加空表单。
 * token 输入留空 = 不修改原 token（saveMcpServer tokenPlain=null；新增时 = 不配置）。
 * 保存校验五件套依次判：名称空 → URL 空 → URL 非 http(s) → 与现存名撞（I-2(b)：添加/
 * 改名后重名一律行内错误拒存——storage 按 name upsert，放行会顶掉旧行且 token/enabled
 * 静默清空）→ requireLegalName（IllegalArgumentException 消息直显为行内错误，不落库）。
 * 全过才回调 [onSave]：[replaceName] = 编辑态改了名时的原名（调用方先删旧行再存——
 * 真重命名，I-2(a)）；名字未改/添加时 = null。
 */
@Composable
private fun McpServerEditDialog(
    editing: McpServerConfig?,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (config: McpServerConfig, tokenPlain: String?, replaceName: String?) -> Unit,
) {
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var url by remember(editing) { mutableStateOf(editing?.url ?: "") }
    var token by remember(editing) { mutableStateOf("") }
    var error by remember(editing) { mutableStateOf<String?>(null) }

    fun submit() {
        val n = name.trim()
        val u = url.trim()
        when {
            n.isEmpty() -> error = "名称不能为空"
            u.isEmpty() -> error = "URL 不能为空"
            !u.startsWith("http://") && !u.startsWith("https://") ->
                error = "URL 需以 http:// 或 https:// 开头"
            // 撞名判据：与本表单编辑对象以外的现存行同名（编辑态名字未改不算撞——
            // upsert 自身语义；originalName 即 editing.name，弹窗生命周期内不变）
            n != editing?.name && n in existingNames ->
                error = "已存在同名服务器「$n」，请换一个名字"
            else -> try {
                // 拒存判据唯一权威（repository 与 UI 双调）——全中文/纯符号名在此拦截
                McpServerConfig.requireLegalName(n)
                onSave(
                    McpServerConfig(
                        name = n,
                        url = u,
                        tokenCipher = editing?.tokenCipher,   // token 留空 = 原值透传
                        enabled = editing?.enabled ?: false,
                    ),
                    token.takeIf { it.isNotEmpty() },
                    // I-2(a)：编辑态改了名 → 原名交调用方先删旧行（真重命名）
                    editing?.name?.takeIf { it != n },
                )
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "添加服务器" else "编辑服务器") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("名称（字母/数字/连字符）") },
                    singleLine = true,
                    isError = error != null,
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; error = null },
                    label = {
                        Text(if (editing?.tokenCipher.isNullOrEmpty()) "Bearer token（可选）" else "Bearer token（留空保持不变）")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
