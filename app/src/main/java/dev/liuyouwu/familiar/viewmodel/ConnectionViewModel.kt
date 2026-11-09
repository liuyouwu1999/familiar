package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.liuyouwu.familiar.data.remote.api.ConnectionProbe
import dev.liuyouwu.familiar.data.remote.api.ModelsApi
import dev.liuyouwu.familiar.data.remote.api.ProbeEvent
import dev.liuyouwu.familiar.core.llm.wire.MessageDto
import dev.liuyouwu.familiar.core.llm.wire.ModelsResponse
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.core.llm.probe.CapabilityProber
import dev.liuyouwu.familiar.core.llm.probe.HttpProbeTransport
import dev.liuyouwu.familiar.core.llm.probe.ToolCapability
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import dev.liuyouwu.familiar.util.BackendProvider
import dev.liuyouwu.familiar.util.CloudVendor
import dev.liuyouwu.familiar.util.ServerUrl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val modelsApi = ModelsApi()

    // 拒绝 stream_options 的后端（如 Mistral 官方 API、严格 schema 网关）：
    // 命中一次后记住该地址，后续请求不再携带，避免整条聊天链路 4xx。
    // Set 在 VM 持有（VM 级生命周期），经构造注入 ConnectionProbe 读写
    private val streamOptionsUnsupportedBases = mutableSetOf<String>()
    // 探针写盘开关（审计 S4）：suspend 读 DataStore——probe 的 sendChatStream 是
    // 独立写盘路径（不经 LlmClient Deps），构造时注入读开关 lambda，每次探测求值
    private val connectionProbe = ConnectionProbe(
        streamOptionsUnsupportedBases,
        diagEnabled = { settingsRepository.diagDumpEnabled.first() },
    )

    // 体检第一步"地址可达"专用：轻量 GET，5 秒超时快速给结论（ModelsApi 的
    // probeClient 是 2/3 秒且打在 /v1/models 上，语义是探测端口而不是验地址）
    private val diagClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * 能力探测专用客户端（P5 T2）：连接 5s（打错地址快速失败）+ 读 60s（本地大模型
     * 首 token 慢）。探测是后台预热且失败可容忍（瞬时 → 回落目录先验），超时不影响 UI。
     */
    private val capabilityClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl

    private val _provider = MutableStateFlow(BackendProvider.LM_STUDIO)
    val provider: StateFlow<BackendProvider> = _provider

    private val _model = MutableStateFlow("")
    val model: StateFlow<String> = _model

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey

    /** 接口路径前缀："" = 标准 /v1；特殊后端可填 /api 等 */
    private val _apiPath = MutableStateFlow("")
    val apiPath: StateFlow<String> = _apiPath

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting

    private val _connectionResult = MutableStateFlow<ConnectionResult?>(null)
    val connectionResult: StateFlow<ConnectionResult?> = _connectionResult

    /** 已成功拉取过的模型列表：地址被编辑后 result 会被清掉，但模型数据保留供重开面板 */
    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels

    private val _lanScan = MutableStateFlow<LanScanState>(LanScanState.Idle)
    val lanScan: StateFlow<LanScanState> = _lanScan

    /** 服务器真实上下文大小的同步说明；null = 未检测到 */
    private val _serverContextInfo = MutableStateFlow<String?>(null)
    val serverContextInfo: StateFlow<String?> = _serverContextInfo

    /** 工具能力标记表（P5 T2，spec §13.6：模型条目显示能力标记）：模型名 → 四态 */
    private val _capabilityBadges = MutableStateFlow<Map<String, ToolCapability>>(emptyMap())
    val capabilityBadges: StateFlow<Map<String, ToolCapability>> = _capabilityBadges

    init {
        viewModelScope.launch {
            // 读取时顺带完成老版 ip+port 数据的自动迁移；
            // 输入框回显剥掉 http:// 前缀（stripHttpScheme），用户看到的只有 IP:端口
            _serverUrl.value = ServerUrl.stripHttpScheme(settingsRepository.getServerAddress())
            _apiPath.value = settingsRepository.getApiPath()
            _provider.value = BackendProvider.fromId(settingsRepository.getProviderId())
            _model.value = settingsRepository.modelName.first()
            _apiKey.value = settingsRepository.apiKey.first()
        }
        // 能力件预载（IO）：目录读 assets + 缓存水合 DataStore——首次徽章/modeResolver
        // 读取不落在主线程
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            AgentCapability.cache(app)
            AgentCapability.reader(app)
        }
    }

    val cloudVendor: StateFlow<String> = settingsRepository.cloudVendor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "deepseek")

    fun updateServerUrl(value: String) {
        _serverUrl.value = value
    }

    /**
     * 地址被用户编辑后清除过期的连接结果：避免"红字错误/找到 N 个模型"
     * 与新输入的地址并存。已拉到的 availableModels 不动，模型面板可随时重开。
     * 能力标记同批清除：它们绑定旧地址的探测/先验，换址后不再可信（新连接成功即重发）。
     */
    fun clearConnectionResult() {
        _connectionResult.value = null
        _capabilityBadges.value = emptyMap()
    }

    fun updateApiPath(value: String) {
        _apiPath.value = value
    }

    /** 云端厂商选择（cc-switch 理念）：点厂商 = 全自动配好——官方地址、示例模型一键切换，
     *  用户只需要填 API Key。地址想用中转站，连上后随时可改。
     *  重复点当前厂商忽略；切换后清掉旧连接结果（旧地址的模型列表不再可信）。 */
    fun updateCloudVendor(vendor: CloudVendor) {
        if (cloudVendor.value == vendor.id) return
        viewModelScope.launch { settingsRepository.setCloudVendor(vendor.id) }
        updateServerUrl(vendor.baseUrl)
        updateModel(vendor.sampleModel)
        clearConnectionResult()
    }

    fun updateProvider(value: BackendProvider) {
        _provider.value = value
        // 切到云端厂商类型：自动配好当前厂商的官方地址和示例模型，用户只填 API Key。
        // 地址若是某厂商官方预设则跟随默认厂商；手填的中转站地址尊重不覆盖
        if (value == BackendProvider.CLOUD) {
            val currentUrl = _serverUrl.value.trim()
            val isPresetUrl = CloudVendor.entries.any { it.baseUrl == currentUrl }
            if (!isPresetUrl) {
                val v = CloudVendor.fromId(cloudVendor.value)
                updateServerUrl(v.baseUrl)
                if (_model.value.isBlank()) updateModel(v.sampleModel)
                clearConnectionResult()
            }
        }
    }

    fun updateModel(value: String) {
        _model.value = value
    }

    fun updateApiKey(value: String) {
        _apiKey.value = value
    }

    /** 输入框下方的即时校验提示；null = 合法 */
    fun urlError(input: String): String? = ServerUrl.validate(input)

    private fun diagnoseError(e: Throwable?): String {
        val msg = e?.message ?: "连接失败"
        // fetchModels 的失败形如 "HTTP 404: body"，分派给状态码翻译
        val statusMatch = Regex("HTTP (\\d{3})").find(msg)
        if (statusMatch != null && msg.startsWith("HTTP")) {
            val status = statusMatch.groupValues[1].toInt()
            val body = msg.substringAfter(":", "").trim()
            return dev.liuyouwu.familiar.util.ApiErrorHint.fromHttpStatus(status, body)
        }
        val desc = BackendProvider.fromId(_provider.value.id).label
        return dev.liuyouwu.familiar.util.ApiErrorHint.fromThrowable(e, "$desc 服务器")
    }

    /** 在途的连接探测任务：新连接请求进来时掐掉它（进页自动连可能卡在不可达
     *  地址上几十秒，旧的静默重入守卫会把用户手动点"连接"整个吞掉——按钮一直
     *  "连接中"没法点）。fetchModels 已有 5s/15s 短超时兜底，join 最多等一拍 */
    private var inFlightJob: kotlinx.coroutines.Job? = null

    suspend fun testConnection(isAuto: Boolean = false): Boolean {
        // 新请求优先：掐掉在途探测并等它收尾（它会把自己的 isTesting 复位）
        inFlightJob?.cancel()
        inFlightJob?.join()
        val normalized = ServerUrl.normalize(_serverUrl.value)
        if (normalized == null) {
            _connectionResult.value = ConnectionResult.Error(
                "服务器地址格式不正确\n\n示例：192.168.1.100:${_provider.value.sampleUrl.substringAfterLast(':')}"
            )
            return false
        }
        val apiPath = ServerUrl.normalizeApiPath(_apiPath.value)
        // 输入框一律保持显示形态（无 http:// 前缀），完整 URL 只存在设置里
        _serverUrl.value = ServerUrl.stripHttpScheme(normalized)
        // 探测期间用户可能再改地址/仅保存：快照比对，过期探测的结果（含自动落盘）一律丢弃
        val urlAtStart = _serverUrl.value
        inFlightJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        _isTesting.value = true
        _connectionResult.value = null
        _serverContextInfo.value = null

        return try {
            // 未填端口时自动探测常用端口；命中后回填完整地址，下次可直连
            val parsedUrl = normalized.toHttpUrlOrNull()
            // https / 云端后端 / 已填显式端口：跳过探测（探测只对本地 HTTP 服务有意义）
            val skipProbe = parsedUrl == null ||
                ServerUrl.hasExplicitPort(normalized) ||
                parsedUrl.scheme == "https" ||
                !BackendProvider.fromId(_provider.value.id).isLocal
            val candidates = if (skipProbe) {
                listOf(normalized)
            } else {
                COMMON_PORTS.mapNotNull { port ->
                    // 用 HttpUrl 换端口而不是字符串拼接，避免把端口拼进路径段
                    parsedUrl?.newBuilder()?.port(port)?.build()?.toString()?.trimEnd('/')
                }
            }
            for (base in candidates) {
                // 探测中地址被改：本次结果过期，静默放弃（不回填、不落盘、不报成功）
                if (_serverUrl.value != urlAtStart) {
                    _isTesting.value = false
                    return false
                }
                val result = modelsApi.fetchModels(base + apiPath, _apiKey.value, probe = candidates.size > 1)
                val models = result.getOrNull()?.data?.mapNotNull { it.id }.orEmpty()
                if (result.isSuccess && models.isNotEmpty()) {
                    // fetch 在途期间用户改了地址：本次结果过期，无条件丢弃（不回填不落盘）
                    return if (onConnected(base, models, isAuto, urlAtStart)) true else false
                }
                if (result.isSuccess && models.isEmpty()) {
                    // 服务可达但没加载模型：停止探测，直接给出可读提示
                    _serverUrl.value = ServerUrl.stripHttpScheme(base)
                    _isTesting.value = false
                    _connectionResult.value = ConnectionResult.Error(
                        "连上了，但没有可用模型\n先在 ${_provider.value.label} 里加载一个模型"
                    )
                    return false
                }
            }
            // 二段扩展扫描（一段常用名单全空时才触发）：只对"没填端口"的本地 HTTP
            // 地址扫，并行百余端口首中即返；命中回填完整地址走同一条成功路径
            if (!skipProbe && parsedUrl != null) {
                val apiKey = _apiKey.value
                val hit = dev.liuyouwu.familiar.util.PortScanner.sweep(
                    hostUrl = normalized,
                    ports = dev.liuyouwu.familiar.util.PortScanner.extendedPortsExcluding(COMMON_PORTS),
                ) { candidate -> modelsApi.probePort(candidate + apiPath, apiKey) }
                if (_serverUrl.value != urlAtStart) {
                    _isTesting.value = false
                    return false
                }
                if (hit != null) {
                    return if (onConnected(hit.first, hit.second, isAuto, urlAtStart)) true else false
                }
            }
            _isTesting.value = false
            // 报错说"你该做什么"，不甩探测日志（2026-10-30 用户反馈：原文案列 7 条
            // URL + "监听 0.0.0.0" 是调试话术，用户看不懂）
            _connectionResult.value = ConnectionResult.Error(
                "没连上这个地址。\n" +
                    "如果服务不是默认端口，填完整地址再试（如 192.168.1.100:8081）；" +
                    "也确认服务已启动、和手机在同一网络。"
            )
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 被新连接请求掐掉：安静收尾（isTesting 复位即可），不能把取消伪装成
            // 连接错误覆盖掉新请求的结果
            _isTesting.value = false
            throw e
        } catch (e: Exception) {
            _isTesting.value = false
            _connectionResult.value = ConnectionResult.Error(diagnoseError(e))
            false
        }
    }

    /**
     * 连接命中（一段常用名单或二段扩展扫描）后的统一成功路径：过期检查 → 回填
     * 地址 → 端口映射 → 兜底选模型 → 落盘 → 结果发布 → 后台预热/对齐。
     * 返回 false 仅当探测在途地址被改（结果过期，静默放弃）。
     */
    private suspend fun onConnected(base: String, models: List<String>, isAuto: Boolean, urlAtStart: String): Boolean {
        // fetch/扫描在途期间用户改了地址：本次结果过期，无条件丢弃（不回填不落盘）
        if (_serverUrl.value != urlAtStart) {
            _isTesting.value = false
            return false
        }
        _serverUrl.value = ServerUrl.stripHttpScheme(base)
        // 仅当端口能明确映射到已知本地后端、且用户没特意选"自定义端点"时才覆盖预设：
        // CUSTOM 是用户有意规避 max_tokens/思考参数注入的选择，不能被端口映射改回去；
        // 非标端口（如 8000）一律保持原预设，同理防止静默改成"自定义/云端"
        base.toHttpUrlOrNull()?.let {
            val mapped = BackendProvider.fromPort(it.port.toString())
            if (mapped != BackendProvider.CUSTOM &&
                BackendProvider.fromId(_provider.value.id) != BackendProvider.CUSTOM
            ) {
                _provider.value = mapped
            }
        }
        // 连接成功即落盘：之前只更新内存，用户不点模型弹窗里的"开始对话"
        // 就退出的话新地址全丢，首页/聊天还在用旧地址（组网换址"被改回去"即此因）。
        // 上次选的模型不在新服务列表里：兜底选第一个非嵌入模型
        // （与模型面板 selectableModels 同口径，嵌入模型做对话必报错）
        if (_model.value !in models) {
            _model.value = models.filter { !dev.liuyouwu.familiar.util.isEmbeddingModel(it) }
                .ifEmpty { models }.first()
        }
        // 落盘失败（磁盘 IO 故障）不能把"连接成功"吞成"连接失败"：
        // 网络验证已通过，配置此刻在内存里是对的，落盘异常单独存在
        runCatching { composeAndSave() }
        _connectionResult.value = ConnectionResult.Success(models)
        _availableModels.value = models
        _isTesting.value = false
        // 后台预热（评审 P1-3 / spec §4.4 第 3 层时机"连接成功后"）：
        // 目录先验徽章先发 + 两步实测逐模型写缓存；查询失败/慢不拖住 UI
        // （与 alignServerContext 同模式，全部在 IO）
        warmCapabilities(base, models)
        // 后台对齐：查询失败/慢不能拖住模型选择弹窗。
        // 仅手动连接时对齐——启动期的静默重连不应覆盖用户手动设定的窗口
        if (!isAuto) {
            viewModelScope.launch { alignServerContext(base, models) }
        }
        return true
    }

    // ===== 能力探测预热 + 能力标记（P5 T2，评审 P1-3 / spec §13.6） =====

    /** 在途预热任务：新连接请求掐掉旧预热（与 inFlightJob 同模式——换地址后旧探测结果不回写） */
    private var warmJob: kotlinx.coroutines.Job? = null

    /**
     * 连接成功后的后台缓存预热（spec §4.4 第 3 层时机"连接成功后"）：逐非嵌入
     * 模型两步实测并写缓存（[CapabilityProber.resolve]：成功写缓存、瞬时失败回落
     * 目录先验且**不写缓存**，下次连接自动重探）。Ollama 先走第 2 层 /api/show
     * 元数据顺路（含 "tools" → 直接 NATIVE 落缓存，省两步实测）。串行执行（本地
     * 后端并发多请求互相挤占加载）；每模型完成即刷新徽章表。
     */
    private fun warmCapabilities(baseUrl: String, models: List<String>) {
        warmJob?.cancel()
        warmJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val providerId = BackendProvider.fromId(_provider.value.id).id
            val base = ServerUrl.normalize(baseUrl) ?: return@launch
            // 先发目录先验版徽章（P5 T2：探测未完成前也有 qwen3/gemma 类先验可显示）
            publishCapabilityBadges(providerId, base, models)
            for (model in models.filter { !dev.liuyouwu.familiar.util.isEmbeddingModel(it) }) {
                ensureActive()
                if (ServerUrl.stripHttpScheme(base) != _serverUrl.value) return@launch   // 地址已被改：旧预热作废
                try {
                    warmOne(app, providerId, base, model)
                    publishCapabilityBadges(providerId, base, models)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 单模型探测异常（判据矩阵已把瞬时折叠进 resolve，此处仅兜未预期形状）：
                    // 预热失败不影响连接，徽章保留目录先验
                }
            }
        }
    }

    private suspend fun warmOne(app: Application, providerId: String, base: String, model: String) {
        val transport = HttpProbeTransport(
            model = model,
            sseSource = dev.liuyouwu.familiar.core.llm.client.okHttpSseSource(capabilityClient),
            baseUrl = { base },
            apiKey = { _apiKey.value },
            plainHttp = HttpProbeTransport.okHttpPlainHttp(capabilityClient),
        )
        val cache = AgentCapability.cache(app)
        // 第 2 层：Ollama /api/show 元数据顺路（连接期调用，不进 probeOnce 判定矩阵）
        if (BackendProvider.fromId(providerId) == BackendProvider.OLLAMA) {
            val caps = transport.ollamaCapabilities(base)
            if (caps != null && "tools" in caps) {
                val prober = CapabilityProber(AgentCapability.catalog(app), transport, cache)
                cache.put(prober.cacheKey(providerId, base, model), ToolCapability.NATIVE)
                return
            }
        }
        // 第 3 层：两步实测（resolve = 成功写缓存；瞬时回落目录先验不写缓存）
        CapabilityProber(AgentCapability.catalog(app), transport, cache)
            .resolve(providerId, base, model)
    }

    /** 徽章表发布：纯读器折叠（覆盖>缓存>目录先验>UNKNOWN）——探测写入缓存后逐模型更新 */
    private fun publishCapabilityBadges(providerId: String, base: String, models: List<String>) {
        val reader = AgentCapability.reader(getApplication())
        _capabilityBadges.value = models.associateWith { model ->
            reader.capability(providerId, base, model)
        }
    }

    /**
     * 连接成功后自动对齐上下文大小：本地后端能查到真实 n_ctx 时直接写入
     * 应用设置（保存即生效，ChatViewModel 收 DataStore 流实时更新）。
     * 应用窗口与服务器窗口不一致是两种故障：大了超窗报错，小了白白扔历史。
     */
    private suspend fun alignServerContext(origin: String, models: List<String>) {
        _serverContextInfo.value = null
        val result = connectionProbe.fetchServerContext(
            origin = origin,
            apiPath = ServerUrl.normalizeApiPath(_apiPath.value),
            providerId = _provider.value.id,
            model = models.firstOrNull() ?: "",
            apiKey = _apiKey.value
        )
        result.onSuccess { raw ->
            val ctx = raw.coerceIn(512, dev.liuyouwu.familiar.data.repository.SettingsRepository.MAX_CONTEXT_LIMIT)
            settingsRepository.saveMaxContext(ctx)
            _serverContextInfo.value = "服务器上下文 $ctx tokens，已同步到设置"
        }
    }

    /** 只保存不测试：服务器临时不可用时用户改的配置也不丢 */
    fun saveOnly() {
        viewModelScope.launch { composeAndSave() }
    }

    /** 地址 + 接口路径 → 完整 base URL 入库（运行时各处直接用） */
    private suspend fun composeAndSave() {
        val normalized = ServerUrl.normalize(_serverUrl.value) ?: return
        val apiPath = ServerUrl.normalizeApiPath(_apiPath.value)
        _serverUrl.value = ServerUrl.stripHttpScheme(normalized)
        val encrypted = settingsRepository.saveConnectionParts(
            serverUrl = normalized + apiPath,
            address = normalized,
            apiPath = apiPath,
            providerId = _provider.value.id,
            model = _model.value,
            apiKey = _apiKey.value
        )
        // 审计 P2-19：Keystore 不可用降级明文不静默——用户会以为 Key 已加密存储
        _keyStorageWarning.value = !encrypted
    }

    /** Keystore 不可用、API Key 明文降级时置 true（审计 P2-19），连接页显示提示 */
    private val _keyStorageWarning = MutableStateFlow(false)
    val keyStorageWarning: StateFlow<Boolean> = _keyStorageWarning

    suspend fun saveSettings() {
        composeAndSave()
    }

    /** 扫描局域网内的本地 LLM 服务（llama.cpp/Ollama/LM Studio 常见端口） */
    fun startLanScan() {
        if (_lanScan.value is LanScanState.Scanning) return
        _lanScan.value = LanScanState.Scanning(0, 0)
        viewModelScope.launch {
            try {
                val localIp = dev.liuyouwu.familiar.util.LanScanner.run { localIpv4OrNull() }
                val prefixes = if (localIp != null) {
                    listOfNotNull(dev.liuyouwu.familiar.util.LanScanner.ipPrefix(localIp))
                } else {
                    _lanScan.value = LanScanState.Failed("拿不到局域网 IP：确认 WiFi 已连接")
                    return@launch
                }
                val results = mutableListOf<dev.liuyouwu.familiar.util.LanScanner.FoundServer>()
                for (prefix in prefixes) {
                    results += dev.liuyouwu.familiar.util.LanScanner.scan(prefix) { done, total ->
                        _lanScan.value = LanScanState.Scanning(done, total)
                    }
                }
                _lanScan.value = LanScanState.Done(results.distinctBy { it.url }.sortedByDescending { it.modelCount })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _lanScan.value = LanScanState.Failed("扫描没跑起来：${(e.message ?: "网络不可用").lineSequence().firstOrNull().orEmpty().take(60)}")
            }
        }
    }

    fun pickScannedServer(url: String) {
        // 扫描结果是完整 URL，回显同样剥掉 http:// 前缀保持显示形态统一
        _serverUrl.value = ServerUrl.stripHttpScheme(url)
        // 扫描发现的服务即意味着标准 /v1 可用：清掉可能残留的自定义接口路径，
        // 否则旧值会拼上去导致该扫描项连不上
        _apiPath.value = ""
        // 按端口切预设（复用统一映射）
        _provider.value = BackendProvider.fromPort(url.substringAfterLast(':'))
    }

    // ===== 连接体检（四步红绿灯） =====

    private val _diagnostics = MutableStateFlow<List<DiagStep>>(emptyList())
    val diagnostics: StateFlow<List<DiagStep>> = _diagnostics

    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing

    /** 串行执行四步体检；每次运行先整体重置，运行中重复调用直接忽略（按钮已禁用兜底） */
    fun runDiagnostics() {
        if (_isDiagnosing.value) return
        viewModelScope.launch { runDiagnosticsInternal() }
    }

    private suspend fun runDiagnosticsInternal() {
        _isDiagnosing.value = true
        val steps = mutableListOf(
            DiagStep("地址可达"),
            DiagStep("认证有效"),
            DiagStep("模型列表"),
            DiagStep("回复测试")
        )
        fun update(index: Int, status: DiagStatus, detail: String = "") {
            steps[index] = steps[index].copy(status = status, detail = detail)
            _diagnostics.value = steps.toList()
        }
        // ApiErrorHint 的完整文案可能多行，体检行只保留第一句，避免面板被撑爆
        fun brief(e: Throwable?): String =
            diagnoseError(e).lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "请求失败" }

        try {
            val normalized = ServerUrl.normalize(_serverUrl.value)
            if (normalized == null) {
                update(0, DiagStatus.FAIL, "地址格式不对：先填服务器地址")
                return
            }
            _serverUrl.value = normalized
            val apiPath = ServerUrl.normalizeApiPath(_apiPath.value)
            val fullBase = normalized + apiPath

            // 第 1 步：地址可达 —— 对 baseUrl 发轻量 GET（5 秒超时）。
            // 任何 HTTP 响应（含 404/401）都证明服务在线，路径对不对交给后面几步判断
            update(0, DiagStatus.RUNNING)
            val ping = pingBaseUrl(normalized)
            val pingOk = ping.getOrNull()
            if (pingOk != null) {
                update(0, DiagStatus.PASS, pingOk)
            } else {
                update(0, DiagStatus.FAIL, brief(ping.exceptionOrNull()))
                return // 服务都够不着，后面三步只会白白超时
            }

            // 第 2 步：认证有效 —— 填了 Key 就带 Key 拉 /v1/models 看 401 与否；
            // 本地服务没填 Key 是正常用法，直接通过并注明跳过。
            // 拉到的结果留给第 3 步复用，不重复发请求
            update(1, DiagStatus.RUNNING)
            var modelsResult: Result<ModelsResponse>? = null
            if (_apiKey.value.isNotBlank()) {
                modelsResult = modelsApi.fetchModels(fullBase, apiKey = _apiKey.value)
                if (modelsResult.isSuccess) {
                    update(1, DiagStatus.PASS, "API Key 认证通过")
                } else {
                    update(1, DiagStatus.FAIL, brief(modelsResult.exceptionOrNull()))
                    return // 认证都过不去，模型列表只会重复同一个错误
                }
            } else if (BackendProvider.fromId(_provider.value.id).isLocal) {
                update(1, DiagStatus.PASS, "未配置密钥，跳过（本地服务无需认证）")
            } else {
                // 云端没填 Key：老老实实请求一次，让服务器告诉我们能不能匿名访问
                modelsResult = modelsApi.fetchModels(fullBase, apiKey = "")
                if (modelsResult.isSuccess) {
                    update(1, DiagStatus.PASS, "该服务无需密钥即可访问")
                } else {
                    update(1, DiagStatus.FAIL, brief(modelsResult.exceptionOrNull()))
                    return
                }
            }

            // 第 3 步：模型列表 —— 复用 fetchModels（第 2 步已拉到就直接用）
            update(2, DiagStatus.RUNNING)
            val listResult = modelsResult ?: modelsApi.fetchModels(fullBase, apiKey = _apiKey.value)
            val models = listResult.getOrNull()?.data?.mapNotNull { it.id }.orEmpty()
            when {
                listResult.isSuccess && models.isNotEmpty() -> {
                    // 顺手刷新模型缓存，与 testConnection 的行为保持一致
                    _availableModels.value = models
                    update(2, DiagStatus.PASS, "找到 ${models.size} 个模型")
                }
                listResult.isSuccess -> {
                    update(2, DiagStatus.FAIL, "连上了，但没有可用模型：先在 ${_provider.value.label} 里加载")
                    return
                }
                else -> {
                    update(2, DiagStatus.FAIL, brief(listResult.exceptionOrNull()))
                    return
                }
            }

            // 第 4 步：回复测试 —— 用第一个可用模型发一条极小 chat 请求。
            // 本地后端的 max_tokens 会被 ConnectionProbe 钳到最小 256，仍属极小请求；
            // 收到第一条回复流事件即判定"回复流已建立"，firstOrNull 会取消上游并断开连接。
            // 事件判定（ProbeEvent 三变体）：Delta（首个 preview 非空白的 data 行；
            // usage-only/空载荷流不再伪造空 Delta）触发 PASS，Error 触发 FAIL，
            // Usage 一律跳过
            update(3, DiagStatus.RUNNING)
            val model = models.first()
            try {
                val event = withTimeout(60_000) {
                    connectionProbe.sendChatStream(
                        baseUrl = fullBase,
                        model = model,
                        messages = listOf(MessageDto.TextMessage(role = "user", content = "ping")),
                        thinkingEnabled = false,
                        apiKey = _apiKey.value,
                        providerId = _provider.value.id,
                        maxContextTokens = 1
                    ).firstOrNull { it !is ProbeEvent.Usage }
                }
                when (event) {
                    is ProbeEvent.Error -> update(3, DiagStatus.FAIL, firstLineOf(event.message))
                    null -> update(3, DiagStatus.FAIL, "60 秒没等到回复")
                    else -> update(3, DiagStatus.PASS, "回复流已建立（模型 $model）")
                }
            } catch (e: TimeoutCancellationException) {
                update(3, DiagStatus.FAIL, "没等到回复：模型太大加载慢会这样，稍后重试")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 异常兜底（审计 P2-10）：未预期异常标红当前运行中的步骤，不再沿 viewModelScope 逃逸崩溃
                val idx = steps.indexOfFirst { it.status == DiagStatus.RUNNING }
                if (idx >= 0) update(idx, DiagStatus.FAIL, brief(e))
            }
        } finally {
            _isDiagnosing.value = false
        }
    }

    /** ApiErrorHint 的完整文案可能多行，体检行只保留第一句 */
    private fun firstLineOf(text: String): String =
        text.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "请求失败" }

    /** 第 1 步专用：对服务器根地址发轻量 GET（5 秒超时），成功返回一句话结论 */
    private suspend fun pingBaseUrl(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            diagClient.newCall(request).execute().use { resp ->
                Result.success("能连上（HTTP ${resp.code}）")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        // 未填端口时依次自动探测的常用端口（本地 LLM 服务的常见默认值）
        private val COMMON_PORTS = listOf(8080, 11434, 1234, 5000, 8000, 1337, 7860)
    }
}

sealed interface LanScanState {
    data object Idle : LanScanState
    data class Scanning(val done: Int, val total: Int) : LanScanState
    data class Done(val results: List<dev.liuyouwu.familiar.util.LanScanner.FoundServer>) : LanScanState
    data class Failed(val reason: String) : LanScanState
}

sealed class ConnectionResult {
    data class Success(val models: List<String>) : ConnectionResult()
    data class Error(val message: String) : ConnectionResult()
}

/** 体检单步状态：等待 → 进行中 → 通过/失败 */
enum class DiagStatus { PENDING, RUNNING, PASS, FAIL }

/** 连接体检单步：名称 / 状态 / 一句话详情 */
data class DiagStep(
    val name: String,
    val status: DiagStatus = DiagStatus.PENDING,
    val detail: String = ""
)
