package dev.liuyouwu.familiar.tools.mcp

import android.util.Log
import com.google.gson.JsonObject
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.util.CryptoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一台 MCP 服务器的可见状态（Task 3，设置子屏状态点/工具数的数据源）：
 * - [state] ∈ "未连接"（停用或总开关关）/ "就绪"（发现成功）/ "错误:原因"（发现失败，
 *   原因=异常消息，含定位要素由 McpClient 层自带）；
 * - [toolCount] 快照中该服务器的工具数（仅就绪态非 0）。
 */
data class McpServerStatus(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val state: String,
    val toolCount: Int,
)

/**
 * 多 MCP 服务器编排器（Task 3 / spec §4）：把各服务器工具面汇成一份 AgentTool 快照。
 *
 * 关键口径（spec §4 三轮评审钉死，逐条实现）：
 * - **init 不吃 Context**（object 首次触达自初始化；冷启动 refresh 归 T4 常驻收集），
 *   只装 [warn] 日志缝（缺省 Log.w，JVM 单测注入记录器——ToolFactory 先例）；
 * - **refresh 是 suspend**（测试 runBlocking 确定性等待；生产调用点 launch）：
 *   各启用服务器 Dispatchers.IO `async` 并发发现（listTools），**快照组装按 servers
 *   列表配置顺序遍历**（awaitAll 保序）——保证"后配置者胜"定序确定性；
 * - **逐服务器 try 隔离**：单台失败（连接拒/协议错/工厂抛）只记本台 错误:原因，
 *   其余服务器工具照常导出；CancellationException 原样重抛（取消不得降级为错误态）；
 * - **快照去重**：final 名 = "{slug}__{toolName}"（slug=server.name 的 slugOf 折叠，
 *   不同名可折同 slug，如 "a.b"/"a_b"），跨服务器撞名**后配置者胜 + warn**——
 *   ToolRegistry.register 是覆盖写、ToolSpec 导出全量追加，不去重会双 schema 上 wire；
 *   去重后**整体替换** @Volatile 快照（不 append，上次 refresh 的工具不残留）；
 * - **远端工具名规范化 + 总名钳（评审 F1 / D3）**：toolName 先按 slugOf 同款折叠、
 *   退化名（空/全 "_"）拒注册 + warn、总名超 64 截 56 + "_" + hex6 短哈希（见
 *   [buildFinalToolName]）——远端名是不可信输入，非法名上 wire 会被严格网关
 *   整字段 400；
 * - **statusFlow = MutableStateFlow 同步更新**：refresh 返回后 statusFlow()/快照
 *   一致可见（同一函数体内先建后换，无竞态窗口）；
 * - **McpToolAdapter.execute 直接透传** [McpClient.callTool] 结果：钳 32KB/NUL 拒/
 *   不可信声明全在 client 层，本层禁双重钳制；tools/call 以远端**原名**调用（非前缀名）。
 *
 * 线程模型：快照/statusFlow 只在 refresh 内单点写（协程串行由 T4 collect 背压保证），
 * 读侧任意线程；toolsForSkill() 无锁读 @Volatile 不可变列表。
 */
object McpToolManager {

    @Volatile
    private var snapshot: List<AgentTool> = emptyList()

    private val status = MutableStateFlow<List<McpServerStatus>>(emptyList())

    /** 日志缝：init 可换；resetForTest 回落缺省 */
    @Volatile
    private var warn: (String) -> Unit = { Log.w("McpToolManager", it) }

    /** 装日志缝（生产装配点缺省即可；JVM 单测注入记录器防 android.util.Log 崩） */
    fun init(warn: (String) -> Unit = { Log.w("McpToolManager", it) }) {
        this.warn = warn
    }

    /**
     * 全量重发现并原子换快照。[enabled] 总开关（SettingsRepository.mcpEnabled）：
     * false → 快照清空、**不发起任何连接**；true → 仅发现 config.enabled 的服务器
     * （停用服务器记 未连接 态进 statusFlow 供设置屏展示）。[clientFactory] 测试缝：
     * 缺省 [defaultFactory]；坏服务器模拟 = factory 返回指向已 shutdown 端口的 client。
     */
    suspend fun refresh(
        servers: List<McpServerConfig>,
        enabled: Boolean,
        clientFactory: (McpServerConfig) -> McpClient = ::defaultFactory,
    ) {
        if (!enabled) {
            snapshot = emptyList()
            status.value = servers.map { it.toStatus(STATE_DISCONNECTED, 0) }
            return
        }
        coroutineScope {
            // 并发发现：启用者 async(IO)，停用者 null（不建协程）；列表位次=配置顺序
            val discoveries: List<Deferred<Discovery>?> = servers.map { cfg ->
                if (cfg.enabled) async(Dispatchers.IO) { discover(cfg, clientFactory) } else null
            }
            val results = discoveries.map { it?.await() } // awaitAll 保序：按配置顺序组装

            val byName = LinkedHashMap<String, McpToolAdapter>()
            val statuses = mutableListOf<McpServerStatus>()
            servers.forEachIndexed { index, cfg ->
                val discovery = results[index]
                when {
                    // 停用服务器（async 位为 null）：不连接，记 未连接 供设置屏展示
                    discovery == null -> statuses += cfg.toStatus(STATE_DISCONNECTED, 0)
                    discovery is Discovery.Failed -> {
                        warn("MCP 服务器 ${cfg.name} 发现失败：${discovery.reason}")
                        statuses += cfg.toStatus("$STATE_PREFIX_ERROR${discovery.reason}", 0)
                    }
                    discovery is Discovery.Ready -> {
                        statuses += cfg.toStatus(STATE_READY, discovery.tools.size)
                        for (remote in discovery.tools) {
                            // F1：远端工具名是不可信输入，先规范化 + 总名钳；退化名拒注册（坏名不上 wire）
                            val finalName = try {
                                buildFinalToolName(cfg.slug, remote.name, cfg.name)
                            } catch (e: IllegalArgumentException) {
                                warn(e.message ?: "远端工具名非法，已拒注册（服务器 ${cfg.name}）")
                                continue
                            }
                            val adapter = McpToolAdapter(
                                serverName = cfg.name,
                                client = discovery.client,
                                remoteToolName = remote.name,
                                name = finalName,
                                description = "[MCP:${cfg.name}] ${remote.description}",
                                parametersJson = remote.parametersJson,
                            )
                            val previous = byName.put(finalName, adapter)
                            if (previous != null) {
                                // 后配置者胜：覆盖值（LinkedHashMap.put 保序换值），前者被顶掉
                                warn(
                                    "MCP 工具名冲突：$finalName（服务器 ${cfg.name} 后配置者胜，" +
                                        "覆盖来自 ${previous.serverName} 的同名工具）",
                                )
                            }
                        }
                    }
                }
            }
            snapshot = byName.values.toList() // 整体替换：旧快照工具不残留
            status.value = statuses           // 同步更新：refresh 返回后两处一致可见
        }
    }

    /** 当前快照（skill 装配点直取，ToolSpec 导出按 List 顺序） */
    fun toolsForSkill(): List<AgentTool> = snapshot

    /** 服务器状态流（设置子屏收集；refresh 返回时已同步更新） */
    fun statusFlow(): StateFlow<List<McpServerStatus>> = status

    /** 测试复位：清快照/状态/日志缝（@BeforeEach 调） */
    fun resetForTest() {
        snapshot = emptyList()
        status.value = emptyList()
        warn = { Log.w("McpToolManager", it) }
    }

    // ---------- 单服务器发现（IO 协程内执行） ----------

    /** 发现结果：成功（client + 工具清单）/ 失败（原因），二选一免去 null 判别 */
    private sealed interface Discovery {
        class Ready(val client: McpClient, val tools: List<McpRemoteTool>) : Discovery
        class Failed(val reason: String) : Discovery
    }

    /** 单台发现：工厂构造 client + listTools；异常即失败态（取消除外），不外溢 */
    private suspend fun discover(
        cfg: McpServerConfig,
        clientFactory: (McpServerConfig) -> McpClient,
    ): Discovery = try {
        val client = clientFactory(cfg)
        Discovery.Ready(client, client.listTools())
    } catch (e: CancellationException) {
        throw e // 取消贯通：refresh 整体取消不得被降级为单台错误态
    } catch (e: Exception) {
        Discovery.Failed(e.message ?: e.javaClass.simpleName)
    }

    private fun McpServerConfig.toStatus(state: String, toolCount: Int) =
        McpServerStatus(name, url, enabled, state, toolCount)

    /**
     * final 工具名构建（评审 F1 / D3 总名钳）：远端工具名是不可信输入（tools/list 的
     * name 为任意 JSON 字符串），非法名直上 tools 数组会被严格网关整字段 400——
     * 1) 工具名先按 [McpServerConfig.slugOf] 同款折叠（非 `[a-zA-Z0-9_-]` → "_"、
     *    连续折叠、无小写化；与服务器名同一折叠权威，零复制）；
     * 2) 折叠退化（空/全 "_"，无任何字母数字）→ IllegalArgumentException，由调用处
     *    warn + 拒注册该工具（其余工具与服务器不受累）；
     * 3) 总名 = "{slug}__{folded}" 超 [MAX_TOOL_NAME_LENGTH] → 截前 56 + "_" + 6 位
     *    hex 短哈希（取自截断前完整名，防截断撞名；真撞名由既有去重 + warn 承接）。
     */
    private fun buildFinalToolName(serverSlug: String, remoteName: String, serverName: String): String {
        val folded = McpServerConfig.slugOf(remoteName)
        if (folded.isEmpty() || folded.all { it == '_' }) {
            throw IllegalArgumentException(
                "远端工具名 \"$remoteName\" 规范化后无有效字符（folded=\"$folded\"，服务器 $serverName），已拒注册",
            )
        }
        val full = "${serverSlug}__$folded"
        if (full.length <= MAX_TOOL_NAME_LENGTH) return full
        val hash = (full.hashCode() and 0xFF_FFFF).toString(16).padStart(6, '0')
        return full.take(TRUNCATED_KEEP) + "_" + hash
    }

    // ---------- AgentTool 适配 ----------

    /**
     * 单个远程工具的 AgentTool 形态（object 内部类，不出模块 API 面）：
     * name/description/parametersJson 在 refresh 组装期定型；execute 透传——
     * 以远端原名调 [McpClient.callTool]，结果零加工返回（钳/NUL/声明在 client 层）。
     */
    private class McpToolAdapter(
        val serverName: String,
        private val client: McpClient,
        private val remoteToolName: String,
        override val name: String,
        override val description: String,
        override val parametersJson: String,
    ) : AgentTool {

        override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
            client.callTool(remoteToolName, args) // 直接透传，禁双重钳制
    }

    // ---------- 生产工厂 ----------

    /**
     * 缺省工厂：token 密文经 [CryptoStore.decrypt] 直解（同步函数；manager 不持
     * SettingsRepository）；解密失败（密文损坏/Keystore 不可用，返 null）warn 提示
     * 后以无鉴权 client 继续发现（F2：失败可见，"token 失效"重录提示归 UI）。
     * client 日志走 manager 当前 warn 缝，超时用 McpClient 缺省（call 30s/init 15s）。
     */
    private fun defaultFactory(config: McpServerConfig): McpClient {
        val token = config.tokenCipher?.takeIf { it.isNotEmpty() }?.let { cipher ->
            CryptoStore.decrypt(cipher).also { plain ->
                if (plain == null) {
                    warn("MCP 服务器 ${config.name} token 解密失败，将以无鉴权调用（重新保存 token 可修复）")
                }
            }
        }
        return McpClient(config.url, token, warn = warn)
    }

    private const val STATE_DISCONNECTED = "未连接"
    private const val STATE_READY = "就绪"
    private const val STATE_PREFIX_ERROR = "错误:"

    /** final 工具名 wire 硬顶（主流网关强制 ^[a-zA-Z0-9_-]{1,64}$） */
    private const val MAX_TOOL_NAME_LENGTH = 64

    /** 超长截断保留位次（56 + "_" + 6 位哈希 = 63 ≤ 64） */
    private const val TRUNCATED_KEEP = 56
}
