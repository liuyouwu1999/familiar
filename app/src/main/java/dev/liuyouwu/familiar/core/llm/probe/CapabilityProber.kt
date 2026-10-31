package dev.liuyouwu.familiar.core.llm.probe

import com.google.gson.Gson
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型工具能力四态（spec §4.4）：NATIVE=原生 OpenAI tools 协议往返；
 * MARKER=文本标记协议兜底（MarkerToolAdapter）；OFF=用户手动关闭（仅来自
 * 手动设置，spec 评审 A7，探测链任何一层都不得产出）；UNKNOWN=尚未探测
 * （连接后探测完成前的瞬时态 / 探测瞬时失败且目录无先验）。
 */
enum class ToolCapability { NATIVE, MARKER, OFF, UNKNOWN }

/**
 * 第 1 层内置目录（spec §4.4）：已知模型系 → 能力先验。内容物在
 * app/src/main/assets/model_capabilities.json（P1 单测不读 assets——直接构造
 * 本类/fromJson 字面 JSON；assets 读取留 P3 接线）。
 * 匹配规则：模型名小写 contains(family.match 小写)，按列表序首个命中生效。
 */
class ModelCatalog(val families: List<Family>) {

    data class Family(val match: String, val capability: ToolCapability)

    /** 无命中返回 null（消费方折为 UNKNOWN，见 [CapabilityProber.resolve]） */
    fun lookup(model: String): ToolCapability? =
        families.firstOrNull { model.lowercase().contains(it.match.lowercase()) }?.capability

    companion object {
        private val gson = Gson()

        /**
         * assets JSON → 目录。非法 JSON/未知能力词在此 fail-fast（JsonSyntaxException /
         * 构造期空指针）——assets 是随包分发的静态内容，损坏属构建期缺陷，
         * 不做静默降级；P3 加载层可前置校验给用户提示。
         */
        fun fromJson(json: String): ModelCatalog {
            val dto = runCatching { gson.fromJson(json, CatalogDto::class.java) }
                .getOrElse { e -> throw IllegalArgumentException("bad model_capabilities.json", e) }
            return ModelCatalog((dto?.families ?: emptyList()).map { Family(it.match, it.capability) })
        }
    }
}

/**
 * 能力缓存缝（spec 评审 C5：结果缓存 DataStore，键见 [CapabilityProber.cacheKey]，
 * 可手动清除重探）。P1 交付内存实现 + 接口；DataStore 持久化实现在 P3 接线。
 * （brief 原文写 `fun interface`——两个抽象成员不可为 SAM 接口，最小修正为普通 interface。）
 */
interface CapabilityCache {
    suspend fun get(key: String): ToolCapability?
    suspend fun put(key: String, v: ToolCapability)
}

/** 线程安全内存实现（单测与 P3 前的运行期占位） */
class InMemoryCapabilityCache : CapabilityCache {
    private val map = ConcurrentHashMap<String, ToolCapability>()
    override suspend fun get(key: String): ToolCapability? = map[key]
    override suspend fun put(key: String, v: ToolCapability) {
        map[key] = v
    }
}

/** 两步实测（spec §4.4 第 3 层）的步骤标识，生产传输据此组装请求体 */
sealed interface ProbeStep {
    /** 第一步：带 get_current_time 工具直接问时间（见 [ProbeTransport] 请求规格） */
    object WITH_TOOLS : ProbeStep

    /** 第二步：把第一步工具结果以 role:"tool" 回传，请求普通收尾回答 */
    object TOOL_FOLLOWUP : ProbeStep
}

/** 单次探测往返的规范化结果（双信号判定的输入，spec §4.4） */
sealed interface ProbeResult {
    /** 200 且响应含 tool_calls（id=后端 call id，供第二步 tool_call_id 对位） */
    data class ToolCalled(val id: String) : ProbeResult

    /** 200 无调用（正文直接回答） */
    data class Ok(val text: String) : ProbeResult

    /** HTTP 错误响应（code + 后端报错文案） */
    data class HttpError(val code: Int, val message: String) : ProbeResult

    /** 超时/网络错误等瞬时故障（5xx 由 prober 归入瞬时，见判定矩阵） */
    data class Transient(val cause: Exception) : ProbeResult
}

/**
 * 探测传输缝：P1 只交付本接口 + 测试假实现；生产 HTTP 实现依赖连接配置
 * （OkHttp 实例/apiKey/模型名/baseUrl 拼接），推迟 P3 接线。请求规格（spec §4.4）：
 * - WITH_TOOLS：tools=[get_current_time 工具定义] + user 指令
 *   “现在几点？请直接调用工具查询，不要先解释或思考” + max_tokens=512（评审 C3）；
 * - TOOL_FOLLOWUP：把第一步 ToolCalled(id) 对应的 ToolResult 以 role:"tool"
 *   （tool_call_id 对位回传）入历史，请求普通收尾回答。
 */
interface ProbeTransport {
    /** 按步执行一次探测往返；实现自持连接目标（构造期绑定 baseUrl/apiKey/model） */
    suspend fun roundTrip(step: ProbeStep): ProbeResult

    /**
     * 第 2 层服务端元数据：Ollama /api/show 的 capabilities 集合（含 "tools" → NATIVE）。
     * 非 Ollama 后端 / 查询失败 → null。P3 连接期顺路调用，不进 probeOnce 判定矩阵。
     */
    suspend fun ollamaCapabilities(baseUrl: String): Set<String>?
}

/** 瞬时探测失败信号：probeOnce 抛出、resolve 捕获后回落第 1 层先验且不写缓存 */
class ProbeTransientException(message: String, cause: Exception?) : Exception(message, cause)

/**
 * 四层探测（spec §4.4）：①内置目录先验 → ②服务端元数据（P3 连接期，经
 * [ProbeTransport.ollamaCapabilities]，不在本类判定矩阵内）→ ③两步实测
 * （[probeOnce]，成功写缓存）→ ④手动覆盖（最高优先）。纯逻辑 + 注入传输，
 * P1 无 HTTP。
 *
 * probeOnce 判定矩阵（两步；矩阵外的 HttpError 一律按瞬时处理）：
 * - 第一步 ToolCalled → 第二步 Ok/ToolCalled（role:"tool" 被接受且 200）→ NATIVE；
 * - 任一步 HttpError 且 message 提及 tools/tool/function（contains "tool"/"function"，
 *   大小写不敏感）→ MARKER——后端明确拒绝工具字段，是定论；
 * - 第二步 HttpError 4xx（即使文案不提 tools）→ MARKER（role:"tool" 回传被定式
 *   拒绝即原生回路不通，spec 评审 C2）；
 * - 第一步 Ok 无调用（200 直接作答）→ MARKER；
 * - Transient（超时/网络错误）与 5xx、第一步非工具类 4xx → 抛
 *   [ProbeTransientException]：瞬时/无定论故障不得写成永久缓存
 * （spec 评审二 P2-3），resolve 回落第 1 层先验，下次连接自动重探。
 */
class CapabilityProber(
    private val catalog: ModelCatalog,
    private val transport: ProbeTransport,
    private val cache: CapabilityCache,
) {

    /** 第 1 层目录先验的直达口（无命中 → null，resolve 折为 UNKNOWN） */
    fun catalogLookup(model: String): ToolCapability? = catalog.lookup(model)

    /**
     * 缓存键（spec 评审 C5）：`toolcap:{providerId}:{normalize(baseUrl)}:{model}`。
     * baseUrl 归一化与 util/ServerUrl.normalize 同口径（语义逐行复制，不 import：
     * core/llm/probe 不新增 util 依赖）——用户填 `192.168.1.5:8080`、
     * `http://192.168.1.5:8080/`、`http://192.168.1.5:8080/v1` 必须同键。
     */
    fun cacheKey(providerId: String, baseUrl: String, model: String): String =
        probeCacheKey(providerId, baseUrl, model)

    /**
     * 第 3 层两步实测（不读不写缓存——写缓存是 [resolve] 的职责）。
     * providerId/baseUrl/model 标识探测目标（传输已构造期绑定连接，参数供
     * 上层透传/记录；保持与 resolve 同形签名）。瞬时故障抛 [ProbeTransientException]。
     */
    suspend fun probeOnce(providerId: String, baseUrl: String, model: String): ToolCapability {
        return when (val first = transport.roundTrip(ProbeStep.WITH_TOOLS)) {
            is ProbeResult.ToolCalled ->
                when (val second = transport.roundTrip(ProbeStep.TOOL_FOLLOWUP)) {
                    is ProbeResult.Ok -> ToolCapability.NATIVE
                    // 回传被接受且 200（模型再发起一次调用也算回路打通）
                    is ProbeResult.ToolCalled -> ToolCapability.NATIVE
                    is ProbeResult.HttpError -> httpVerdict(ProbeStep.TOOL_FOLLOWUP, second)
                    is ProbeResult.Transient -> throw transientOf(second)
                }
            is ProbeResult.Ok -> ToolCapability.MARKER // 200 无调用：模型不理会 tools 字段
            is ProbeResult.HttpError -> httpVerdict(ProbeStep.WITH_TOOLS, first)
            is ProbeResult.Transient -> throw transientOf(first)
        }
    }

    /**
     * 四层合流：手动覆盖（null=“自动”，最高优先）→ 缓存 → probeOnce（成功写缓存）。
     * 瞬时失败回落第 1 层目录先验（无先验 → UNKNOWN）且**不写缓存**（spec 评审二 P2-3）。
     */
    suspend fun resolve(
        providerId: String,
        baseUrl: String,
        model: String,
        override: ToolCapability? = null,
    ): ToolCapability {
        override?.let { return it }
        val key = cacheKey(providerId, baseUrl, model)
        cache.get(key)?.let { return it }
        return try {
            val verdict = probeOnce(providerId, baseUrl, model)
            cache.put(key, verdict)
            verdict
        } catch (e: ProbeTransientException) {
            catalogLookup(model) ?: ToolCapability.UNKNOWN
        }
    }

    /** HttpError 判定：矩阵内（定论）返回 MARKER，矩阵外（瞬时/无定论）抛瞬时异常 */
    private fun httpVerdict(step: ProbeStep, err: ProbeResult.HttpError): ToolCapability {
        if (toolsMentioned(err.message)) return ToolCapability.MARKER
        if (step == ProbeStep.TOOL_FOLLOWUP && err.code in 400..499) return ToolCapability.MARKER
        throw ProbeTransientException("probe http ${err.code}: ${err.message}", null)
    }

    /** 报错文案提及 tools/tool/tool_calls/function（"tool" 前缀 contains 覆盖 tools/tool_calls） */
    private fun toolsMentioned(message: String): Boolean {
        val m = message.lowercase()
        return "tool" in m || "function" in m
    }

    private fun transientOf(t: ProbeResult.Transient): ProbeTransientException =
        ProbeTransientException(
            "probe transient: ${t.cause.message ?: t.cause.javaClass.simpleName}", t.cause)
}

/**
 * 缓存键单点（spec 评审 C5）：[CapabilityProber.cacheKey] 与纯读侧
 * （[CapabilitySnapshot] 实现方 / [CapabilityReader] 消费方）同源——
 * 连接页预热写入的键与 modeResolver 读取的键必须逐字符一致。
 */
fun probeCacheKey(providerId: String, baseUrl: String, model: String): String =
    "toolcap:$providerId:${normalizeBaseUrl(baseUrl) ?: baseUrl.trim().trimEnd('/')}:$model"

/**
 * 同步缓存快照缝（P5 T2，评审 P1-4）：modeResolver 要求**非挂起纯读**，而
 * [CapabilityCache] 是挂起接口——生产由 DataStore 实现的内存镜像（peek）供给，
 * 测试注入 map 假件。实现方取键用 [probeCacheKey]（与 [CapabilityProber.cacheKey]
 * 同一单点）。
 */
fun interface CapabilitySnapshot {
    operator fun invoke(providerId: String, baseUrl: String, model: String): ToolCapability?
}

/**
 * 四层合流的**纯读侧**（spec §4.4；评审 P1-4 全局约束的执行点）：
 * 手动覆盖（第 4 层）→ 缓存快照（第 2.5 层）→ 目录先验（第 1 层）→
 * miss 折叠 UNKNOWN。不持有传输、无挂起、**永不发起网络探测**——探测预热
 * 只发生在连接页后台（[CapabilityProber.probeOnce]/[CapabilityProber.resolve]），
 * 本类只在已写缓存与目录先验上折叠出 ToolMode：
 * NATIVE → [ToolMode.NATIVE]；MARKER / UNKNOWN / OFF 一律折叠
 * [ToolMode.MARKER] 协议兜底（ToolMode 无 OFF 形——OFF 的彻底关闭面归装配层
 * 工具注销，非本类职责）。
 */
class CapabilityReader(
    private val catalog: ModelCatalog,
    private val snapshot: CapabilitySnapshot,
) {

    /** 手动覆盖 > 缓存 > 目录先验 > UNKNOWN（与 [CapabilityProber.resolve] 层序同源，纯读版） */
    fun capability(
        providerId: String,
        baseUrl: String,
        model: String,
        override: ToolCapability? = null,
    ): ToolCapability =
        override ?: snapshot(providerId, baseUrl, model)
            ?: catalog.lookup(model)
            ?: ToolCapability.UNKNOWN

    /** 能力 → 双栈模式：仅 NATIVE 走原生协议，其余折叠 MARKER */
    fun mode(
        providerId: String,
        baseUrl: String,
        model: String,
        override: ToolCapability? = null,
    ): ToolMode =
        if (capability(providerId, baseUrl, model, override) == ToolCapability.NATIVE) {
            ToolMode.NATIVE
        } else {
            ToolMode.MARKER
        }
}

/**
 * baseUrl 归一化——util/ServerUrl.normalize 的语义复制（brief 钦定不 import，
 * 键口径必须与用户填地址的清洗同源）：trim → 缺 scheme 补 http://（仅收 http/https）
 * → 循环剥结尾 /v1 与尾斜杠 → OkHttp 解析回规范形（主机小写/默认端口省略）
 * → trimEnd('/')。失败（空串/非法 URL/非 http(s) scheme）返回 null，由
 * [CapabilityProber.cacheKey] 落回原始串（不因地址未规范化而炸探测）。
 */
private fun normalizeBaseUrl(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    val schemeIdx = s.indexOf("://")
    if (schemeIdx > 0) {
        val scheme = s.substring(0, schemeIdx).lowercase()
        if (scheme != "http" && scheme != "https") return null
    } else {
        s = "http://$s"
    }
    while (true) {
        val lower = s.lowercase()
        when {
            lower.endsWith("/v1") -> s = s.dropLast(3)
            lower.endsWith("/") -> s = s.dropLast(1)
            else -> break
        }
    }
    val url = s.toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" && url.scheme != "https") return null
    return url.toString().trimEnd('/')
}

/** Gson DTO（仅供 [ModelCatalog.fromJson]，无 @SerializedName：键名与 JSON 字面同名） */
private data class CatalogDto(val families: List<FamilyDto>? = null)

private data class FamilyDto(val match: String, val capability: ToolCapability)
