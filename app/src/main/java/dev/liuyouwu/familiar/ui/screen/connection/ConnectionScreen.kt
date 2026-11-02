package dev.liuyouwu.familiar.ui.screen.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import dev.liuyouwu.familiar.core.llm.probe.ToolCapability
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.theme.PaperLight
import dev.liuyouwu.familiar.ui.theme.LocalPixelDark
import dev.liuyouwu.familiar.ui.theme.PixelBottomPanel
import dev.liuyouwu.familiar.ui.theme.PixelButton
import dev.liuyouwu.familiar.ui.theme.PixelChip
import dev.liuyouwu.familiar.ui.theme.PixelDivider
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.PixelPanel
import dev.liuyouwu.familiar.ui.theme.PixelTopBar
import dev.liuyouwu.familiar.ui.theme.StarGold
import dev.liuyouwu.familiar.ui.theme.WarnAmber
import dev.liuyouwu.familiar.ui.theme.WoodBorder
import dev.liuyouwu.familiar.ui.theme.GrassGreen
import dev.liuyouwu.familiar.ui.theme.pixelEdgeFade
import dev.liuyouwu.familiar.ui.theme.pixelSecondaryAlpha
import dev.liuyouwu.familiar.util.isEmbeddingModel
import dev.liuyouwu.familiar.util.modelDisplayName
import dev.liuyouwu.familiar.util.BackendProvider
import dev.liuyouwu.familiar.util.LanScanner
import dev.liuyouwu.familiar.viewmodel.ConnectionResult
import dev.liuyouwu.familiar.viewmodel.DiagStatus
import dev.liuyouwu.familiar.viewmodel.DiagStep
import dev.liuyouwu.familiar.viewmodel.LanScanState
import dev.liuyouwu.familiar.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onConnected: () -> Unit,
    autoConnect: Boolean = true,
    onBack: (() -> Unit)? = null
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val apiPath by viewModel.apiPath.collectAsStateWithLifecycle()
    val serverContextInfo by viewModel.serverContextInfo.collectAsStateWithLifecycle()
    val provider by viewModel.provider.collectAsStateWithLifecycle()
    val cloudVendorId by viewModel.cloudVendor.collectAsStateWithLifecycle()
    val cloudVendor = dev.liuyouwu.familiar.util.CloudVendor.fromId(cloudVendorId)
    val isCloud = provider == dev.liuyouwu.familiar.util.BackendProvider.CLOUD
    val model by viewModel.model.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val keyStorageWarning by viewModel.keyStorageWarning.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val connectionResult by viewModel.connectionResult.collectAsStateWithLifecycle()
    val lanScan by viewModel.lanScan.collectAsStateWithLifecycle()
    // 连接体检（四步红绿灯）
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val isDiagnosing by viewModel.isDiagnosing.collectAsStateWithLifecycle()

    var selectedModel by rememberSaveable { mutableStateOf(model) }
    // 已从设置加载过后再同步一次选中模型
    LaunchedEffect(model) {
        if (selectedModel.isBlank()) selectedModel = model
    }
    val scope = rememberCoroutineScope()

    // 已成功拉取的模型列表：连接结果被编辑清除后仍保留，供重开模型面板
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    // 工具能力标记表（P5 T2，spec §13.6：模型条目显示能力标记——目录先验+缓存，探测后台渐进更新）
    val capabilityBadges by viewModel.capabilityBadges.collectAsStateWithLifecycle()
    // 过滤嵌入模型：误选对话必报错；过滤后为空则显示全部
    val selectableModels = availableModels.filter { !isEmbeddingModel(it) }.ifEmpty { availableModels }

    // 模型选择弹窗：连接成功（自动或手动）后自动弹出；
    // 之后可随时通过表单里的常驻入口重开
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connectionResult) {
        val result = connectionResult
        if (result is ConnectionResult.Success && result.models.isNotEmpty()) {
            // 桌宠出现条件：连上过模型（大肥鱼只说她能确定的事）
            dev.liuyouwu.familiar.pet.PetHub.notifyConnected()
            // 久别问候判定（gap≥2 天置 pending，ambient 下一拍开口；当日仅一次）
            dev.liuyouwu.familiar.pet.PetHub.maybeGreet(dev.liuyouwu.familiar.pet.PetBondStore)
            if (selectedModel !in result.models) {
                // 上次选的模型不在列表（换了服务/卸了模型）：自动选第一个非嵌入模型
                // （与 VM 兜底落盘同口径）并弹窗确认
                selectedModel = selectableModels.firstOrNull() ?: result.models[0]
                viewModel.updateModel(selectedModel)
                showModelDialog = true
            } else {
                // 上次选的模型还在：直接继续，不再每次打开都打断重选
                onConnected()
            }
        }
    }

    // 自动连接：只在首次进入且已保存过地址时触发
    var hasAutoConnected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(serverUrl) {
        if (autoConnect && !hasAutoConnected && serverUrl.isNotBlank()) {
            hasAutoConnected = true
            // 启动期静默重连：不触发上下文自动对齐（只在用户手动点连接时对齐）
            viewModel.testConnection(isAuto = true)
        }
    }

    // 模型选择弹窗：底部像素面板
    if (showModelDialog && availableModels.isNotEmpty()) {
        PixelBottomPanel(
            onDismiss = { showModelDialog = false },
            title = "选择模型"
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(selectableModels) { modelId ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                selectedModel = modelId
                                viewModel.updateModel(modelId)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 像素方点选中指示
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(
                                    if (modelId == selectedModel) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                                )
                                .border(1.dp, WoodBorder)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(modelId, style = MaterialTheme.typography.bodyMedium)
                        // 工具能力标记（P5 T2，spec §13.6）：UNKNOWN（未探测/无先验）不显示
                        capabilityBadgeLabel(capabilityBadges[modelId])?.let { label ->
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.primary).padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    PixelDivider()
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            PixelButton(
                text = "开始对话",
                onClick = {
                    showModelDialog = false
                    scope.launch {
                        viewModel.saveSettings()
                        onConnected()
                    }
                },
                enabled = selectedModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 首帧不把"还没填"当"填错"：只有输入过/尝试连接后才显示红字，否则显示帮助文案
    var urlTouched by rememberSaveable { mutableStateOf(false) }
    val urlError = viewModel.urlError(serverUrl)
    val showUrlError = urlError != null && urlTouched

    // 双层折叠（spec §4.1）：进页默认收起；连接失败自动展开两层（诊断面板在
    // 内层——外层不同步展开则内层展开不可见，spec §4.1 审查 P1-4 补丁）；
    // 连接成功时诊断收起（D4"成功时隐藏"）
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showDiag by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connectionResult) {
        when (connectionResult) {
            is ConnectionResult.Error -> { showAdvanced = true; showDiag = true }
            is ConnectionResult.Success -> showDiag = false
            null -> {}
        }
    }
    val isScanRunning = lanScan is LanScanState.Scanning

    Scaffold(
        topBar = {
            PixelTopBar(
                title = "连接设置",
                navigationIcon = {
                    if (onBack != null) {
                        PixelIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            onClick = onBack,
                            tint = PaperLight
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Familiar", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "连接本地或云端 LLM",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha(lightAlpha = 0.6f))
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 后端预设
            item {
                // FlowRow 换行：五颗 chip 全部可见，不用滚动找（云端厂商/自定义端点曾挤出屏幕）
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BackendProvider.entries.forEach { p ->
                        PixelChip(
                            text = p.label,
                            selected = p == provider,
                            onClick = { viewModel.updateProvider(p) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    provider.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
                )
                // 云端厂商：选厂商自动填官方地址和示例模型（cc-switch 式预设）
                if (isCloud) {
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dev.liuyouwu.familiar.util.CloudVendor.entries.forEach { v ->
                            PixelChip(
                                text = v.label,
                                selected = v.id == cloudVendorId,
                                onClick = {
                                    if (v.id != cloudVendorId) {
                                        viewModel.updateCloudVendor(v)
                                        urlTouched = true
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "API Key 在 ${cloudVendor.keyHint} 控制台获取",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        viewModel.updateServerUrl(it)
                        // 地址变了，过期的连接结果（红字/找到 N 个模型）不再可信，清掉；
                        // 已拉到的 availableModels 保留在 VM 里供重开面板
                        viewModel.clearConnectionResult()
                        urlTouched = true
                    },
                    label = { Text(if (isCloud) "接口地址" else "服务器地址") },
                    placeholder = { Text(if (isCloud) "选厂商自动填官方地址，可改成中转站" else "192.168.1.100 或 192.168.1.100:8080") },
                    supportingText = {
                        if (showUrlError) {
                            Text(urlError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        } else if (!isCloud) {
                            Text("只填 IP 也行，端口会自动找")
                        }
                    },
                    isError = showUrlError,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                // 疑似裸 IPv6 时提示方括号写法（审计 P3）
                dev.liuyouwu.familiar.util.ServerUrl.ipv6Hint(serverUrl)?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (LocalPixelDark.current) StarGold else WarnAmber
                    )
                }
                // 非内网 http 明文提醒：公网地址走明文 http 时 API Key 可能被嗅探，
                // 纯本地字符串判断（私网=10.x / 192.168.x / 172.16-31.x / localhost / 127.x）
                if (isPublicHttpUrl(serverUrl)) {
                    Text(
                        text = "公网走 http 会暴露 API Key，建议换 https",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (LocalPixelDark.current) StarGold else WarnAmber,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                // 云端类型：API Key 必填，上提为主路径②末尾（spec §4.1；本地类型在高级区）
                if (isCloud) {
                    // API Key 默认遮蔽（审计 P2-18），提供显隐切换；
                    // Keystore 不可用明文降级时提示（审计 P2-19），不再让用户误以为 Key 已加密
                    if (keyStorageWarning) {
                        Text(
                            text = "此设备安全芯片不可用，API Key 以明文保存在本机",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (LocalPixelDark.current) StarGold else WarnAmber
                        )
                    }
                    var showApiKey by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { viewModel.updateApiKey(it) },
                        label = { Text(if (isCloud) "API Key（云端必填，厂商控制台获取）" else "API Key（本地服务留空）") },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showApiKey) "隐藏 API Key" else "显示 API Key"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // 主路径③：连接按钮通栏（spec §4.1）
            item {
                Button(
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, WoodBorder),
                    onClick = {
                        urlTouched = true
                        scope.launch { viewModel.testConnection() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isTesting && urlError == null
                ) {
                    Text(if (isTesting) "连接中..." else "连接")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 主路径④：连接结果区——成功✓ / 失败红字 + 「当前模型」重开入口 + 上下文信息
            // （重开入口与 serverContextInfo 是修"Activity 恢复后面板关闭后无路可走"bug 的产物，不得丢失）
            item {
                when (val result = connectionResult) {
                    is ConnectionResult.Success -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "连接成功", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("找到 ${result.models.size} 个模型", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    is ConnectionResult.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Error, contentDescription = "连接失败", tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = result.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    null -> {}
                }

                // 模型面板常驻重开入口：只要拉到过模型就始终可点，
                // Activity 恢复/面板关闭后不再无路可走
                if (availableModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    PixelPanel(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showModelDialog = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "当前模型：${
                                selectedModel.takeIf { it.isNotBlank() }
                                    ?.let(::modelDisplayName) ?: "未选择"
                            }",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = "选择模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val ctxInfo = serverContextInfo
                if (ctxInfo != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ctxInfo,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 高级选项折叠头（spec §4.1 item⑤）：扫描进行中禁折叠——扫描进度
            // "扫描中… (n/N)"不可被折叠吞掉（PixelPanel 无 enabled，onClick 内守卫即禁用语义）
            item {
                PixelPanel(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (!isScanRunning) showAdvanced = !showAdvanced },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = if (showAdvanced) "▾ 高级选项" else "▸ 高级选项",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 高级区（spec §4.1）：API Key（本地）/ 接口路径（本地）/ 仅保存 /
            // 局域网扫描 / 连接诊断（内层折叠，失败两层同开、成功收起）
            if (showAdvanced) {
                item {
                    if (!isCloud) {
                        // API Key 默认遮蔽（审计 P2-18），提供显隐切换；
                        // Keystore 不可用明文降级时提示（审计 P2-19），不再让用户误以为 Key 已加密
                        if (keyStorageWarning) {
                            Text(
                                text = "此设备安全芯片不可用，API Key 以明文保存在本机",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (LocalPixelDark.current) StarGold else WarnAmber
                            )
                        }
                        var showApiKey by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { viewModel.updateApiKey(it) },
                            label = { Text(if (isCloud) "API Key（云端必填，厂商控制台获取）" else "API Key（本地服务留空）") },
                            placeholder = { Text("sk-...") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showApiKey) "隐藏 API Key" else "显示 API Key"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    // 接口路径：对云端无意义（!isCloud 条件原样保留）
                    if (!isCloud) OutlinedTextField(
                        value = apiPath,
                        onValueChange = { viewModel.updateApiPath(it) },
                        label = { Text("接口路径（可选）") },
                        placeholder = { Text("留空 = 标准 /v1") },
                        supportingText = { Text("服务端地址带目录时才填，如 /api") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 仅保存（主路径只留「连接」，save-only 收进高级区）
                    OutlinedButton(
                        shape = RectangleShape,
                        onClick = { viewModel.saveOnly() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = urlError == null
                    ) {
                        Text("仅保存")
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // 局域网自动发现
                    OutlinedButton(
                        shape = RectangleShape,
                        onClick = { viewModel.startLanScan() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = lanScan !is LanScanState.Scanning
                    ) {
                        Text(
                            when (val st = lanScan) {
                                is LanScanState.Scanning -> "扫描中… (${st.done}/${st.total})"
                                is LanScanState.Done -> "重新扫描局域网"
                                is LanScanState.Failed -> "重新扫描（上次失败）"
                                else -> "扫描局域网，自动找服务器"
                            }
                        )
                    }
                    if (lanScan is LanScanState.Failed) {
                        Text(
                            (lanScan as LanScanState.Failed).reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (lanScan is LanScanState.Done) {
                        val results = (lanScan as LanScanState.Done).results
                        if (results.isEmpty()) {
                            Text(
                                "没找到。确认服务已启动、手机连的是同一个网络",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
                            )
                        } else {
                            results.forEach { server ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                        viewModel.pickScannedServer(server.url)
                                        scope.launch { viewModel.testConnection() }
                                    }
                                ) {
                                    Text(
                                        "${server.url}（${server.modelCount} 个模型）",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 内层连接诊断折叠头（失败自动展开见 LaunchedEffect；诊断仍手动触发）
                    PixelPanel(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showDiag = !showDiag },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (showDiag) "▾ 连接诊断" else "▸ 连接诊断",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (showDiag) {
                        // 连接诊断：四步红绿灯（地址可达 / 认证有效 / 模型列表 / 回复测试）
                        Spacer(modifier = Modifier.height(16.dp))
                        PixelPanel(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "连接诊断",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "哪步红了修哪步",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            PixelButton(
                                text = if (isDiagnosing) "诊断中…" else "开始诊断",
                                onClick = { viewModel.runDiagnostics() },
                                enabled = !isDiagnosing && urlError == null,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (diagnostics.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                diagnostics.forEachIndexed { index, step ->
                                    if (index > 0) Spacer(modifier = Modifier.height(2.dp))
                                    DiagStepRow(step)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * 体检单行：像素符号（✓ 通过 / ✗ 失败 / … 进行中 / · 等待）+ 步骤名 + 一句话结果。
 */
@Composable
private fun DiagStepRow(step: DiagStep) {
    val symbol = when (step.status) {
        DiagStatus.PASS -> "✓"
        DiagStatus.FAIL -> "✗"
        DiagStatus.RUNNING -> "…"
        DiagStatus.PENDING -> "·"
    }
    val symbolColor = when (step.status) {
        DiagStatus.PASS -> GrassGreen
        DiagStatus.FAIL -> MaterialTheme.colorScheme.error
        DiagStatus.RUNNING -> StarGold
        DiagStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleSmall,
            color = symbolColor,
            modifier = Modifier.width(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step.status == DiagStatus.FAIL) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 判断服务器地址是否为"明文 http 且指向非私网主机"：
 * 私网 = 10.x / 192.168.x / 172.16-31.x / localhost / 127.x，公网地址才提醒上 https。
 * 与 ViewModel 的补全行为一致：用户没写协议前缀时按 http:// 处理。
 */
private fun isPublicHttpUrl(raw: String): Boolean {
    val url = raw.trim()
    if (url.isEmpty()) return false
    val schemeAndRest = url.split("://", limit = 2)
    val scheme = if (schemeAndRest.size == 2) schemeAndRest[0].lowercase() else "http"
    if (scheme != "http") return false
    // 取主机名：去路径 → 去 user info@ → IPv6 取方括号内 / 其余去端口
    val authority = schemeAndRest.last().substringBefore('/').substringBefore('@')
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }.lowercase()
    if (host.isEmpty()) return false
    // 精确 "::1" 或回环段前缀（"::1:"），不再把 "::10"/"::1234" 等非回环 IPv6 一并豁免（审计 P3）
    if (host == "localhost" || host == "::1" || host.startsWith("::1:")) return false
    // 仅当主机确实是合法点分十进制 IPv4 时才按私网段判定；
    // 域名一律视为公网（"192.168.evil.com" 这类以私网前缀开头的域名不能靠 startsWith 放过）
    val octets = host.split('.')
    val isIpv4 = octets.size == 4 &&
        octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
    val isPrivate = isIpv4 && run {
        val o1 = octets[0].toInt()
        val o2 = octets[1].toInt()
        when (o1) {
            10, 127 -> true
            192 -> o2 == 168
            172 -> o2 in 16..31
            // 100.64.0.0/10 CGNAT 运营商内网（家庭宽带常见管理段）与 169.254 链路本地：
            // 明文 http 警告只该吓唬真公网（QA 2026-10-31 发现 3：100.64.x 常驻误报）
            100 -> o2 in 64..127
            169 -> o2 == 254
            else -> false
        }
    }
    return !isPrivate
}

/**
 * 工具能力标记的显示文案（P5 T2，spec §13.6）：NATIVE="原生工具"、
 * MARKER="文本协议"、OFF="已关闭"；UNKNOWN（未探测/无先验的瞬时态）→ null
 * 不显示。目录先验在连接成功即有值，探测完成后经缓存渐进更新（背景预热逐模型刷新）。
 */
private fun capabilityBadgeLabel(capability: ToolCapability?): String? = when (capability) {
    ToolCapability.NATIVE -> "原生工具"
    ToolCapability.MARKER -> "文本协议"
    ToolCapability.OFF -> "已关闭"
    ToolCapability.UNKNOWN, null -> null
}
