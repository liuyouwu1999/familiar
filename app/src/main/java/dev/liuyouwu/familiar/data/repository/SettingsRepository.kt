package dev.liuyouwu.familiar.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.liuyouwu.familiar.tools.mcp.McpServerConfig
import dev.liuyouwu.familiar.util.BackendProvider
import dev.liuyouwu.familiar.util.CryptoStore
import dev.liuyouwu.familiar.util.ServerUrl
import java.lang.reflect.Type

// internal：data/update 包复用同一实例（同一 DataStore 文件不允许第二个属性持有者）
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// Theme mode: 0 = system, 1 = light, 2 = dark
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

class SettingsRepository(private val context: Context) {

    companion object {
        private const val TAG = "SettingsRepository"
        private val KEY_IP = stringPreferencesKey("server_ip")
        private val KEY_PORT = stringPreferencesKey("server_port")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        // 地址与接口路径分存：KEY_SERVER_URL 保存拼接后的完整 base（运行时契约不变）
        private val KEY_ADDRESS = stringPreferencesKey("server_address")
        private val KEY_API_PATH = stringPreferencesKey("api_path")
        private val KEY_PROVIDER = stringPreferencesKey("provider")
        private val KEY_MODEL = stringPreferencesKey("model_name")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        // API Key 改为 Keystore AES-GCM 密文存储（util/CryptoStore）；
        // KEY_API_KEY 只作老版本明文遗留的迁移来源，新写入一律走 KEY_API_KEY_ENC
        private val KEY_API_KEY_ENC = stringPreferencesKey("api_key_encrypted")
        private val KEY_VIDEO_FRAMES = intPreferencesKey("video_frames")
        private val KEY_MAX_CONTEXT = intPreferencesKey("max_context")
        private val KEY_MAX_CONTEXT_MIGRATED = booleanPreferencesKey("max_context_migrated_v2")

        /** 新装默认值：现代长上下文模型（128k 起步） */
        const val DEFAULT_MAX_CONTEXT = 131072
        /** 上限：200 万。OpenAI 兼容接口普遍不上报模型上下文长度，无法自动感知，
         *  按所用模型的实际上下文手动设定即可（Gemini 1M/2M、GPT-4.1 1M 等都覆盖）。 */
        const val MAX_CONTEXT_LIMIT = 2_000_000
        /** 旧版默认值：迁移时视为"用户从未调过" */
        private const val LEGACY_MAX_CONTEXT = 4096
        private val KEY_CHAT_ROOM_REPLIES = intPreferencesKey("chat_room_max_replies")
        private val KEY_THINKING_ENABLED = booleanPreferencesKey("thinking_enabled")
        private val KEY_AUTO_READ = booleanPreferencesKey("auto_read_enabled")
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        // ===== familiar 陪伴精灵（称呼/模式/节奏；默认 default/主人/我/true）=====
        private val KEY_FAMILIAR_MODE = stringPreferencesKey("familiar_mode")
        private val KEY_FAMILIAR_ADDRESS = stringPreferencesKey("familiar_address")
        private val KEY_FAMILIAR_SELFNAME = stringPreferencesKey("familiar_selfname")
        private val KEY_FAMILIAR_PACING = booleanPreferencesKey("familiar_pacing")
        // 主精灵会话 id：入口按 id 直查会话是否存在（避免按 tutorConfig 列扫描），无则新建
        private val KEY_FAMILIAR_CONVERSATION_ID = longPreferencesKey("familiar_conversation_id")

        private val KEY_PET_ENABLED = booleanPreferencesKey("pet_enabled")
        private val KEY_PET_POSITION = stringPreferencesKey("pet_position")
        private val KEY_PET_COLOR = stringPreferencesKey("pet_color")
        private val KEY_PET_SHOW_IN_BACKGROUND = booleanPreferencesKey("pet_show_in_background")
        private val KEY_USE_SYSTEM_FONT = booleanPreferencesKey("use_system_font")
        private val KEY_CHAT_ROOM_ADDRESS = stringPreferencesKey("chat_room_address")
        private val KEY_CLOUD_VENDOR = stringPreferencesKey("cloud_vendor")
        private val KEY_WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")

        // ===== MCP 服务器（tools/mcp）=====
        // 服务器列表：Gson JSON 数组（tokenCipher 字段含 CryptoStore 密文，同 KEY_API_KEY_ENC 口径）
        private val KEY_MCP_SERVERS = stringPreferencesKey("mcp_servers")
        // MCP 总开关：默认 false（关着不接任何 MCP 工具面，UI 显式开启才生效）
        private val KEY_MCP_ENABLED = booleanPreferencesKey("mcp_enabled")
        private val MCP_GSON = Gson()
        // TypeToken.getParameterized 编程式构造（非匿名子类）：R8 full-mode 类合并会剥匿名
        // TypeToken 子类的 Signature 泛型签名（-keepattributes 拦不住），release 启动即崩
        // （2026-10-30 vivo/三星实证）；此写法不依赖签名反射，混淆免疫
        private val MCP_SERVERS_TYPE: Type =
            TypeToken.getParameterized(List::class.java, McpServerConfig::class.java).type

        /**
         * 同名原位 upsert（纯函数，[saveMcpServer] 的合并步，终审 F-1）：
         * 撞名（行内启停 Switch、编辑弹窗不改名保存）indexOf + set 原位替换，不挪队尾——
         * 列表 UI 行序稳定，且存储顺序作为跨服务器撞名工具"后配置者胜"的定序判据
         * 不因一次保存/开关动作静默翻转胜者；新名 append 在尾（新增语义不变）。
         * 改名路径（UI 先 delete 旧名再存新名）新名不存在 → append 落尾，仍符合
         * "后配置者胜"。
         */
        internal fun upsertMcpServer(servers: List<McpServerConfig>, config: McpServerConfig): List<McpServerConfig> {
            val idx = servers.indexOfFirst { it.name == config.name }
            if (idx < 0) return servers + config
            return servers.toMutableList().also { it[idx] = config }
        }

        /**
         * 坏条目过滤（纯函数，[parseMcpServers] 的防御步）：Gson Unsafe 分配绕过 Kotlin
         * 构造器，存量 JSON 缺 name/url 字段时字段为 null（穿透非空类型，运行时才炸）。
         * 缺 name 或 url 的条目整条丢弃——MCP 服务器无这两字段本就不可用，宁弃不崩。
         * isNullOrEmpty 而非 == null：非空类型字段上的可空比较编译期告警噪声大。
         */
        internal fun sanitizeMcpServers(parsed: List<McpServerConfig>): List<McpServerConfig> =
            parsed.filter { !it.name.isNullOrEmpty() && !it.url.isNullOrEmpty() }

    }

    // Theme mode
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_THEME_MODE] ?: 0) {
            1 -> ThemeMode.LIGHT
            2 -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = when (mode) {
                ThemeMode.SYSTEM -> 0
                ThemeMode.LIGHT -> 1
                ThemeMode.DARK -> 2
            }
        }
    }

    suspend fun getThemeMode(): ThemeMode {
        return themeMode.first()
    }

    val systemPrompt: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SYSTEM_PROMPT] ?: "你是一个有用的助手。请使用 Markdown 格式回复。用中文思考。"
    }

    val serverIp: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_IP] ?: ""
    }

    val serverPort: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PORT] ?: "1234"
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    val provider: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PROVIDER] ?: BackendProvider.LM_STUDIO.id
    }

    val modelName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MODEL] ?: ""
    }

    /** API Key（解密后明文）。兼容读：新键缺失/解密失败时退回旧明文键。
     *  解密走 Android Keystore（审计 P2-17），flowOn 移出采集线程（Main） */
    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        readApiKeyCompat(prefs)
    }.flowOn(Dispatchers.IO)

    /** 从偏好快照读 Key：优先解密新键，解密失败或新键缺失时回退旧明文键 */
    private fun readApiKeyCompat(prefs: Preferences): String {
        val enc = prefs[KEY_API_KEY_ENC]
        if (!enc.isNullOrEmpty()) {
            return CryptoStore.decrypt(enc) ?: (prefs[KEY_API_KEY] ?: "")
        }
        return prefs[KEY_API_KEY] ?: ""
    }

    val videoFrameCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIDEO_FRAMES] ?: 20
    }

    /**
     * 保存连接配置。serverUrl 必须是规范化后的地址（调用方先过 ServerUrl.normalize）。
     */
    suspend fun saveConnection(serverUrl: String, providerId: String, model: String, apiKey: String): Boolean {
        var encryptedOk = true
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl
            prefs[KEY_PROVIDER] = providerId
            prefs[KEY_MODEL] = model
            encryptedOk = writeApiKeyEncrypted(prefs, apiKey)
        }
        return encryptedOk
    }

    /**
     * 保存"地址 + 接口路径"两部分：serverUrl 存拼接后的完整 base（运行时用），
     * address/apiPath 单独存（连接页表单还原用）。
     */
    suspend fun saveConnectionParts(
        serverUrl: String,
        address: String,
        apiPath: String,
        providerId: String,
        model: String,
        apiKey: String
    ): Boolean {
        var encryptedOk = true
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl
            prefs[KEY_ADDRESS] = address
            prefs[KEY_API_PATH] = apiPath
            prefs[KEY_PROVIDER] = providerId
            prefs[KEY_MODEL] = model
            encryptedOk = writeApiKeyEncrypted(prefs, apiKey)
        }
        return encryptedOk
    }

    /** 连接页表单用的地址（不含接口路径）；老版本无此键时退回完整 base */
    suspend fun getServerAddress(): String {
        val prefs = context.dataStore.data.first()
        prefs[KEY_ADDRESS]?.let { return it }
        return getBaseUrl()
    }

    /** 接口路径前缀，如 ""(=标准 /v1) 或 "/api"；老版本返回 "" */
    suspend fun getApiPath(): String {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_API_PATH] ?: ""
    }

    suspend fun getSystemPrompt(): String {
        return systemPrompt.first()
    }

    /**
     * 规范化后的服务器 base URL。老版本只存了 ip+port，首次读取时自动迁移，
     * 并按端口推断后端预设（8080=llama.cpp / 11434=Ollama / 1234=LM Studio）。
     */
    suspend fun getBaseUrl(): String {
        val prefs = context.dataStore.data.first()
        prefs[KEY_SERVER_URL]?.let { return it }
        val migrated = ServerUrl.fromLegacyIpPort(
            prefs[KEY_IP] ?: "",
            prefs[KEY_PORT] ?: ""
        ) ?: return ""
        val provider = BackendProvider.fromPort(prefs[KEY_PORT])
        context.dataStore.edit {
            it[KEY_SERVER_URL] = migrated
            it[KEY_PROVIDER] = provider.id
        }
        return migrated
    }

    suspend fun getProviderId(): String {
        val prefs = context.dataStore.data.first()
        prefs[KEY_PROVIDER]?.let { return it }
        // 触发 getBaseUrl 的迁移逻辑后 provider 一定已写入；此处兜底
        getBaseUrl()
        return context.dataStore.data.first()[KEY_PROVIDER] ?: BackendProvider.LM_STUDIO.id
    }

    suspend fun getApiKey(): String {
        val prefs = context.dataStore.data.first()
        val legacy = prefs[KEY_API_KEY]
        val enc = prefs[KEY_API_KEY_ENC]
        // 兼容迁移（一次性）：旧明文键有值且新键为空 → 加密写入新键、清掉旧明文。
        // Keystore 不可用（encrypt 返回 null）时保留旧明文键不动，功能照常、以后再迁移
        if (!legacy.isNullOrEmpty() && enc.isNullOrEmpty()) {
            val encrypted = CryptoStore.encrypt(legacy)
            if (encrypted == null) return legacy
            context.dataStore.edit {
                it[KEY_API_KEY_ENC] = encrypted
                it.remove(KEY_API_KEY)
            }
            return legacy
        }
        if (!enc.isNullOrEmpty()) {
            return CryptoStore.decrypt(enc) ?: (legacy ?: "")
        }
        return legacy ?: ""
    }

    /** 单独保存 API Key：加密写入新键并清掉旧明文键。
     *  @return false = Keystore 不可用已明文回退（调用方应告知用户，审计 P2-19） */
    suspend fun saveApiKey(key: String): Boolean {
        var encryptedOk = true
        context.dataStore.edit { prefs ->
            encryptedOk = writeApiKeyEncrypted(prefs, key)
        }
        return encryptedOk
    }

    /** Key 统一写入口：密文进新键，旧明文键移除；CryptoStore.encrypt 对空串原样返回。
     *  @return false = Keystore 不可用，已明文回退写旧键 */
    private fun writeApiKeyEncrypted(prefs: MutablePreferences, key: String): Boolean {
        val encrypted = CryptoStore.encrypt(key)
        return if (encrypted == null) {
            // Keystore 不可用：明文回退写旧键并清掉可能存在的旧密文，保证 decrypt 链路不被污染
            prefs[KEY_API_KEY] = key
            prefs.remove(KEY_API_KEY_ENC)
            false
        } else {
            prefs[KEY_API_KEY_ENC] = encrypted
            prefs.remove(KEY_API_KEY)
            true
        }
    }

    suspend fun saveSystemPrompt(prompt: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SYSTEM_PROMPT] = prompt
        }
    }

    suspend fun saveModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MODEL] = model
        }
    }

    suspend fun getVideoFrameCount(): Int {
        return videoFrameCount.first()
    }

    suspend fun saveVideoFrameCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIDEO_FRAMES] = count.coerceIn(1, 50)
        }
    }

    // ===== Max context tokens =====
    /** 原始读取（不做迁移），仅供表单回显等场景 */
    val maxContext: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAX_CONTEXT] ?: DEFAULT_MAX_CONTEXT
    }

    fun getMaxContextFlow(): Flow<Int> = maxContext

    /**
     * 读取最大上下文。旧版默认 4096 对 128k+ 模型等于扔掉几乎全部历史，
     * 一次性迁移：未调过（存值==旧默认）的升级到新默认并写回，
     * 手动改过的尊重原值；迁移打标后用户再故意设回 4096 也不会被反复改掉。
     */
    suspend fun getMaxContext(): Int {
        val prefs = context.dataStore.data.first()
        if (prefs[KEY_MAX_CONTEXT_MIGRATED] == true) {
            return prefs[KEY_MAX_CONTEXT] ?: DEFAULT_MAX_CONTEXT
        }
        val stored = prefs[KEY_MAX_CONTEXT]
        val value = if (stored == null || stored == LEGACY_MAX_CONTEXT) DEFAULT_MAX_CONTEXT else stored
        context.dataStore.edit {
            it[KEY_MAX_CONTEXT] = value
            it[KEY_MAX_CONTEXT_MIGRATED] = true
        }
        return value
    }

    suspend fun saveMaxContext(max: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAX_CONTEXT] = max.coerceIn(512, MAX_CONTEXT_LIMIT)
            // 显式写入（手动修改或自动对齐）都是"值有主"的证据：
            // 打迁移标，防止一次性迁移把对齐/手设的 4096 再改回新默认
            prefs[KEY_MAX_CONTEXT_MIGRATED] = true
        }
    }

    // 聊天室：每轮用户发言后最多几个 AI 回复（1-6）
    val chatRoomMaxReplies: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_CHAT_ROOM_REPLIES] ?: 3
    }

    suspend fun getChatRoomMaxReplies(): Int {
        return chatRoomMaxReplies.first()
    }

    suspend fun saveChatRoomMaxReplies(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CHAT_ROOM_REPLIES] = count.coerceIn(1, 6)
        }
    }

    // 深度思考开关（跨会话记忆，不再每次进聊天页被重置）
    val thinkingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_THINKING_ENABLED] ?: false
    }

    suspend fun getThinkingEnabled(): Boolean {
        return thinkingEnabled.first()
    }

    suspend fun saveThinkingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THINKING_ENABLED] = enabled
        }
    }

    // 生成完成后自动朗读回复
    val autoReadEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_READ] ?: false
    }

    suspend fun getAutoReadEnabled(): Boolean {
        return autoReadEnabled.first()
    }

    suspend fun saveAutoReadEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_READ] = enabled
        }
    }

    // ===== familiar 陪伴精灵（照 themeMode 读写模式：flow 带默认 + save/get 对）=====

    /** 说话模式：default / proactive / passive（PersonaPromptBuilder.MODES） */
    val familiarMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAMILIAR_MODE] ?: "default"
    }

    suspend fun getFamiliarMode(): String {
        return familiarMode.first()
    }

    suspend fun setFamiliarMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAMILIAR_MODE] = mode
        }
    }

    /** 大肥鱼对用户的称呼（覆写人设卡 address；建会话时播种进设置）。默认"老板" */
    val familiarAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAMILIAR_ADDRESS] ?: "老板"
    }

    suspend fun getFamiliarAddress(): String {
        return familiarAddress.first()
    }

    suspend fun setFamiliarAddress(address: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAMILIAR_ADDRESS] = address
        }
    }

    /** 精灵自称（覆写人设卡 selfName） */
    val familiarSelfName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAMILIAR_SELFNAME] ?: "我"
    }

    suspend fun getFamiliarSelfName(): String {
        return familiarSelfName.first()
    }

    suspend fun setFamiliarSelfName(selfName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAMILIAR_SELFNAME] = selfName
        }
    }

    /** 主动搭话节奏开关（true = 按模式设定主动互动） */
    val familiarPacing: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAMILIAR_PACING] ?: true
    }

    suspend fun isFamiliarPacing(): Boolean {
        return familiarPacing.first()
    }

    suspend fun setFamiliarPacing(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAMILIAR_PACING] = enabled
        }
    }

    /** 主精灵常驻会话 id；null = 从未建过（入口此时新建并写回） */
    val familiarConversationId: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAMILIAR_CONVERSATION_ID]
    }

    suspend fun getFamiliarConversationId(): Long? {
        return familiarConversationId.first()
    }

    suspend fun setFamiliarConversationId(id: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAMILIAR_CONVERSATION_ID] = id
        }
    }

    // ===== 桌宠（大肥鱼）=====

    /** 桌宠总开关（全应用唯一一个）：true = 连上模型后大肥鱼常驻悬浮窗，
     *  应用内、桌面、其他应用上面都是同一只；关 = 全部收回。默认开 */
    val petEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PET_ENABLED] ?: true
    }

    suspend fun setPetEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PET_ENABLED] = enabled
        }
    }

    /** 桌宠位置（归一化坐标 "x,y"，0-1 相对可用区域，拖动结束保存）；null = 默认右下角 */
    val petPosition: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_PET_POSITION]
    }

    suspend fun setPetPosition(x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PET_POSITION] = "$x,$y"
        }
    }

    /** 一次性迁移标记（铁律：用户改过的值即默认值，迁移只跑一次、只匹配旧默认值，绝不覆盖用户定制） */
    suspend fun isMigrated(key: String): Boolean {
        return context.dataStore.data.map { it[booleanPreferencesKey("mig_" + key)] ?: false }.first()
    }

    suspend fun markMigrated(key: String) {
        context.dataStore.edit { it[booleanPreferencesKey("mig_" + key)] = true }
    }

    /** 界面字体：true = 系统默认字体（照顾不喜欢像素风的用户）。默认像素字体 */
    val useSystemFont: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_USE_SYSTEM_FONT] ?: false
    }

    suspend fun setUseSystemFont(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USE_SYSTEM_FONT] = enabled
        }
    }

    /** 云端厂商 id（provider=cloud 时使用；默认 DeepSeek） */
    val cloudVendor: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_VENDOR] ?: "deepseek"
    }

    suspend fun setCloudVendor(id: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLOUD_VENDOR] = id
        }
    }

    /** 角色卡（闲聊群/圆桌）对用户的称呼：独立于大肥鱼的"老板"，默认"小刘鸭" */
    val chatRoomAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CHAT_ROOM_ADDRESS] ?: "小刘鸭"
    }

    suspend fun getChatRoomAddress(): String {
        return chatRoomAddress.first()
    }

    suspend fun setChatRoomAddress(address: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CHAT_ROOM_ADDRESS] = address
        }
    }

    /** 联网搜索开关：作用于大肥鱼对话与聊天室（课堂/圆桌始终开启不受此限）。
     *  开启即同意"搜索词明文发往所选引擎"。默认开——用户预期"问知识就该搜"，
     *  默认关时模型对未知词只会幻觉硬答，还没有任何提示（2026-10-27 设计复盘） */
    val webSearchEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_WEB_SEARCH_ENABLED] ?: true
    }

    suspend fun saveWebSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WEB_SEARCH_ENABLED] = enabled
        }
    }

    /** 桌宠配色：blue = 蓝色大肥鱼（原版）/ orange = 中转站的大肥鱼（Claude 橘） */
    val petColor: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PET_COLOR] ?: "orange"
    }

    suspend fun getPetColor(): String {
        return petColor.first()
    }

    suspend fun setPetColor(color: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PET_COLOR] = color
        }
    }

    /** 退后台显示：false（默认）= 应用退到后台自动收窗（回前台无缝回来，服务不死）；
     *  true = 旧版常驻行为，其他应用上面也浮着 */
    val petShowInBackground: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PET_SHOW_IN_BACKGROUND] ?: false
    }

    suspend fun setPetShowInBackground(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PET_SHOW_IN_BACKGROUND] = show
        }
    }

    // ===== MCP 服务器（tools/mcp，Task 1）=====

    /** 服务器列表（name 为身份键，存储顺序即列表顺序）；JSON 损坏时按空列表处理不崩 */
    val mcpServers: Flow<List<McpServerConfig>> = context.dataStore.data.map { prefs ->
        parseMcpServers(prefs[KEY_MCP_SERVERS])
    }

    /** MCP 总开关：默认 false（连接与工具面接入的统一闸门） */
    val mcpEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_MCP_ENABLED] ?: false
    }

    suspend fun setMcpEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MCP_ENABLED] = enabled
        }
    }

    /**
     * 保存（upsert，按 [McpServerConfig.name] 原位替换同名项——同名不挪队尾，存储顺序
     * 稳定即撞名工具定序稳定；新名 append 在尾。合并步见 [upsertMcpServer]）。
     * 先过 requireLegalName（拒存判据唯一权威，全下划线/空名在此拦截）。
     *
     * @param tokenPlain null = 编辑不改 token（保留 config.tokenCipher 原值）；
     *                   非空（含空串=清空）→ CryptoStore 加密后存 tokenCipher
     * @return false = Keystore 不可用已明文回退（调用方应提示用户，同 saveApiKey 口径）
     */
    suspend fun saveMcpServer(config: McpServerConfig, tokenPlain: String?): Boolean {
        McpServerConfig.requireLegalName(config.name)
        var encryptedOk = true
        context.dataStore.edit { prefs ->
            val effective = if (tokenPlain != null) {
                val encrypted = CryptoStore.encrypt(tokenPlain)
                if (encrypted == null) {
                    // Keystore 不可用：明文回退存 tokenCipher 本身，Boolean 透传 UI 提示
                    encryptedOk = false
                    config.copy(tokenCipher = tokenPlain)
                } else {
                    config.copy(tokenCipher = encrypted)
                }
            } else {
                config
            }
            val servers = upsertMcpServer(parseMcpServers(prefs[KEY_MCP_SERVERS]), effective)
            prefs[KEY_MCP_SERVERS] = MCP_GSON.toJson(servers)
        }
        return encryptedOk
    }

    /** 删除指定名字的服务器；不存在时静默（幂等） */
    suspend fun deleteMcpServer(name: String) {
        context.dataStore.edit { prefs ->
            val remaining = parseMcpServers(prefs[KEY_MCP_SERVERS]).filterNot { it.name == name }
            prefs[KEY_MCP_SERVERS] = MCP_GSON.toJson(remaining)
        }
    }

    /** 解出 Bearer token 明文（非 suspend：CryptoStore.decrypt 同步）；未配置/解密失败 → null */
    fun mcpTokenPlain(config: McpServerConfig): String? {
        val cipher = config.tokenCipher
        if (cipher.isNullOrEmpty()) return null
        return CryptoStore.decrypt(cipher)
    }

    /** KEY_MCP_SERVERS 解析：空/损坏 JSON 一律回空列表（不因脏数据崩设置流，仅告警）；
     *  解析成功再过 [sanitizeMcpServers] 丢坏条目（Gson Unsafe 绕构造器，存量脏数据可带
     *  null 字段穿透非空类型——2026-11-01 小米真机实证启动即 NPE 于 McpServerStatus） */
    private fun parseMcpServers(json: String?): List<McpServerConfig> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val parsed = MCP_GSON.fromJson<List<McpServerConfig>>(json, MCP_SERVERS_TYPE) ?: emptyList()
            sanitizeMcpServers(parsed).also { clean ->
                if (clean.size != parsed.size) {
                    Log.w(TAG, "mcp_servers 含 ${parsed.size - clean.size} 条缺 name/url 的坏记录，已丢弃")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mcp_servers JSON 解析失败，按空列表处理", e)
            emptyList()
        }
    }
}
