package dev.liuyouwu.familiar.backup

import android.content.ContentResolver
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonWriter
import dev.liuyouwu.familiar.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * 全量备份/恢复：会话 + 消息导出为 JSON（SAF 用户选位置）。
 * 刻意不包含 API Key 等敏感设置。结构版本化，未来字段演进走 version 分支。
 */
object BackupManager {

    const val FORMAT_VERSION = 4

    data class BackupData(
        val version: Int = FORMAT_VERSION,
        val exportedAt: Long,
        val app: String = "Familiar",
        val conversations: List<BackupConversation>,
        val messages: List<BackupMessage>,
        // v2 新增三表（审计 P1-2：恢复曾级联清空聊天室成员且无法找回）。
        // Gson 反序列化走 Unsafe，旧 v1 备份缺这些字段时不会应用 Kotlin 默认值，
        // 必须声明为可空、import 侧 orEmpty() 兜底
        val participants: List<BackupParticipant>? = null,
        val promptTemplates: List<BackupTemplate>? = null,
        val personaPresets: List<BackupPreset>? = null,
        // v4 新增：大肥鱼的长期记忆文件原文 + familiar 设置（聊半个月的心血，卸载/换机不能丢）。
        // 可空：v3 及更早备份缺字段解析为 null，导入侧原样透出、调用方跳过即可
        val familiarMemory: String? = null,
        val familiar: BackupFamiliar? = null
    )

    /** v4：familiar 设置快照（与 DataStore 键一一对应）。可空字段缺失时导入侧跳过该项 */
    data class BackupFamiliar(
        val address: String? = null,
        val selfName: String? = null,
        val mode: String? = null,
        val pacing: Boolean? = null
    )

    data class BackupParticipant(
        val conversationId: Long,
        val modelName: String,
        val displayName: String,
        val personality: String? = null,
        val colorHex: String? = null,
        val sortOrder: Int = 0,
        // v3 新增：人设卡七字段（可空，v2 及更早备份缺字段解析为 null，导入侧 toEntity 兜底）
        val identity: String? = null,
        val traits: String? = null,
        val speechStyle: String? = null,
        val catchphrase: String? = null,
        val exampleDialogs: String? = null,
        val selfName: String? = null,
        val address: String? = null
    )

    data class BackupTemplate(
        val name: String,
        val content: String,
        val isDefault: Boolean = false,
        val createdAt: Long = 0L
    )

    data class BackupPreset(
        // v3 补充：主键随备份走（终审 Important：恢复丢 id 会自增重排，拉断/错绑 familiar 卡的
        // preset_id 关联；与 BackupMessage 同款可空模式，v2 及更早备份缺字段解析为 null，导入侧 0=自增）
        val id: Long? = null,
        val name: String,
        val personality: String,
        val colorHex: String? = null,
        val createdAt: Long = 0L,
        // v3 新增：人设卡七字段（可空，v2 及更早备份缺字段解析为 null，导入侧 toEntity 兜底；
        // tagBlock/tagGloss 为派生缓存，提示词侧 ifBlank 重算，不随备份走）
        val identity: String? = null,
        val traits: String? = null,
        val speechStyle: String? = null,
        val catchphrase: String? = null,
        val exampleDialogs: String? = null,
        val selfName: String? = null,
        val address: String? = null
    )

    data class BackupConversation(
        val id: Long,
        val title: String,
        val systemPrompt: String,
        val createdAt: Long,
        val updatedAt: Long,
        val isChatRoom: Boolean,
        val isPinned: Boolean,
        val pinnedAt: Long?,
        val tutorConfig: String? = null,
        val tutorState: String? = null
    )

    data class BackupMessage(
        val id: Long? = null,
        val conversationId: Long,
        val role: String,
        val content: String,
        val imageBase64: String? = null,
        val fileName: String? = null,
        val fileContent: String? = null,
        val videoFrames: String? = null,
        val timestamp: Long,
        val senderModel: String? = null,
        val senderDisplayName: String? = null,
        val senderColorHex: String? = null,
        val replyToMessageId: Long? = null,
        val replyToContent: String? = null,
        val replyToRole: String? = null,
        // v3 新增：分段内容 JSON（可空，v2 及更早备份无此字段，实体列本就可空）
        val segments: String? = null,
        // Agent 内核结构化四列（可空，v4 及更早备份缺字段解析为 null，实体列本就可空，
        // toEntity 直映落 null 天然兼容）：
        // - reasoning：assistant 思考原文；
        // - blocks：结构化块 JSON（BlockCodec，权威列）；
        // - toolMeta：工具往返元数据 JSON（[{callId,name,argsJson}]，与 blocks 同组）；
        // - turnId：同轮分组键（跨行聚合一条逻辑回复）
        val reasoning: String? = null,
        val blocks: String? = null,
        val toolMeta: String? = null,
        val turnId: String? = null
    )

    data class ImportResult(
        val conversations: Int,
        val messages: Int,
        val participants: Int = 0,
        val templates: Int = 0,
        val presets: Int = 0,
        // v4：备份里的记忆原文与 familiar 设置原样透出；由调用方落盘/写设置
        // （BackupManager 保持纯 DB+纯数据，不碰文件系统与 DataStore，单测无需 Android）
        val familiarMemory: String? = null,
        val familiar: BackupFamiliar? = null
    )

    private val gson: Gson = GsonBuilder().serializeNulls().create()

    /** 导入文件大小上限：超限直接报错，绝不整读进内存（重度用户导出可远超此值） */
    private const val MAX_IMPORT_BYTES = 200L * 1024 * 1024

    /**
     * 备份头白名单校验（纯函数，供测试直接覆盖）：
     * 返回 null=合法；非 null=错误文案（调用方照抛 IllegalArgumentException）。
     * v3 起导出 app="Familiar"，但历史备份均为 "TalkWithLLM"，双标识放行。
     */
    fun validateHeader(app: String?, version: Int): String? {
        if (app != "TalkWithLLM" && app != "Familiar") {
            return "不是有效的 Familiar/TalkWithLLM 备份文件"
        }
        if (version > FORMAT_VERSION) {
            return "备份文件版本更新（v$version），先升级 app 再导入"
        }
        return null
    }

    /** v2 及更早备份缺卡字段（null）时落非空实体列：五内容字段 -> ""，selfName/address -> "我"/"主人"（与迁移默认一致）；
     *  id 缺失（null）落 0 交由 Room 自增，v3 新导出则显式保留原 id（Dao insert REPLACE，防 familiar 卡绑定错位） */
    fun toEntity(p: BackupPreset) = dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity(
        id = p.id ?: 0L,
        name = p.name,
        personality = p.personality,
        colorHex = p.colorHex ?: "#1976D2",
        createdAt = if (p.createdAt > 0) p.createdAt else System.currentTimeMillis(),
        identity = p.identity ?: "",
        traits = p.traits ?: "",
        speechStyle = p.speechStyle ?: "",
        catchphrase = p.catchphrase ?: "",
        exampleDialogs = p.exampleDialogs ?: "",
        selfName = p.selfName ?: "我",
        address = p.address ?: "主人"
    )

    /** 同 [toEntity] 的参与者版：卡字段兜底语义一致 */
    fun toEntity(p: BackupParticipant) = dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity(
        conversationId = p.conversationId,
        modelName = p.modelName,
        displayName = p.displayName,
        personality = p.personality ?: "",
        colorHex = p.colorHex ?: "#1976D2",
        sortOrder = p.sortOrder,
        identity = p.identity ?: "",
        traits = p.traits ?: "",
        speechStyle = p.speechStyle ?: "",
        catchphrase = p.catchphrase ?: "",
        exampleDialogs = p.exampleDialogs ?: "",
        selfName = p.selfName ?: "我",
        address = p.address ?: "主人"
    )

    /** 消息映射（纯函数）：segments 与 Agent 内核四列可空原样透传，其余兜底与既有 import 行为一致 */
    fun toEntity(m: BackupMessage) = dev.liuyouwu.familiar.data.local.entity.MessageEntity(
        id = m.id ?: 0,
        conversationId = m.conversationId,
        role = m.role ?: "user",
        content = m.content ?: "",
        imageBase64 = m.imageBase64,
        fileName = m.fileName,
        fileContent = m.fileContent,
        videoFrames = m.videoFrames,
        timestamp = m.timestamp ?: 0L,
        senderModel = m.senderModel,
        senderDisplayName = m.senderDisplayName,
        senderColorHex = m.senderColorHex,
        replyToMessageId = m.replyToMessageId,
        replyToContent = m.replyToContent,
        replyToRole = m.replyToRole,
        segments = m.segments,
        reasoning = m.reasoning,
        blocks = m.blocks,
        toolMeta = m.toolMeta,
        turnId = m.turnId
    )

    /**
     * 流式导出到输出流（推荐入口）：逐会话逐消息用 JsonWriter 写入，
     * 不把整库拼成单字符串，避免重度用户导出 OOM。
     * 顶层 JSON 结构与旧版 export 完全一致（字段名/顺序/serializeNulls 不变），旧版导入兼容。
     * v4 参数：[familiarMemory] 记忆文件原文、[familiar] familiar 设置；null 时不写出该字段
     * （老调用点/测试不传，输出与 v3 字节级一致）。
     * 注意：写完会关闭 out。
     */
    suspend fun export(
        db: AppDatabase,
        out: OutputStream,
        familiarMemory: String? = null,
        familiar: BackupFamiliar? = null
    ): Unit = withContext(Dispatchers.IO) {
        val convs = db.conversationDao().getAllConversationsOnce()
        JsonWriter(BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))).use { w ->
            // 顶层字段与 BackupData 数据类逐一对齐（version/exportedAt/app/conversations/messages）
            w.beginObject()
            w.name("version").value(FORMAT_VERSION)
            w.name("exportedAt").value(System.currentTimeMillis())
            w.name("app").value("Familiar")
            w.name("conversations")
            w.beginArray()
            for (c in convs) {
                gson.toJson(
                    BackupConversation(
                        c.id, c.title, c.systemPrompt, c.createdAt, c.updatedAt,
                        c.isChatRoom, c.isPinned, c.pinnedAt, c.tutorConfig, c.tutorState
                    ),
                    BackupConversation::class.java, w
                )
            }
            w.endArray()
            w.name("messages")
            w.beginArray()
            // 逐会话查询边写边丢（审计 P2-4）：消息表含 base64 大字段，全量载入内存
            // 在重度用户（图片多）导出时会 OOM/ANR；导入侧不依赖全局顺序，语义不变
            for (c in convs) {
                for (m in db.messageDao().getMessagesForConversationOnce(c.id)) {
                    gson.toJson(
                        BackupMessage(
                            m.id, m.conversationId, m.role, m.content, m.imageBase64, m.fileName,
                            m.fileContent, m.videoFrames, m.timestamp, m.senderModel,
                            m.senderDisplayName, m.senderColorHex, m.replyToMessageId,
                            m.replyToContent, m.replyToRole, m.segments,
                            m.reasoning, m.blocks, m.toolMeta, m.turnId
                        ),
                        BackupMessage::class.java, w
                    )
                }
            }
            w.endArray()
            // v2：聊天室成员 / 提示词模板 / 人设预设（成员随会话 CASCADE 删除，必须随备份走）
            w.name("participants")
            w.beginArray()
            for (p in db.chatRoomParticipantDao().getAllParticipantsSync()) {
                gson.toJson(
                    BackupParticipant(
                        p.conversationId, p.modelName, p.displayName,
                        p.personality, p.colorHex, p.sortOrder,
                        p.identity, p.traits, p.speechStyle, p.catchphrase,
                        p.exampleDialogs, p.selfName, p.address
                    ),
                    BackupParticipant::class.java, w
                )
            }
            w.endArray()
            w.name("promptTemplates")
            w.beginArray()
            for (t in db.promptTemplateDao().getAllTemplatesSync()) {
                gson.toJson(BackupTemplate(t.name, t.content, t.isDefault, t.createdAt), BackupTemplate::class.java, w)
            }
            w.endArray()
            w.name("personaPresets")
            w.beginArray()
            for (p in db.personaPresetDao().getAllPresetsSync()) {
                gson.toJson(
                    BackupPreset(
                        p.id, p.name, p.personality, p.colorHex, p.createdAt,
                        p.identity, p.traits, p.speechStyle, p.catchphrase,
                        p.exampleDialogs, p.selfName, p.address
                    ),
                    BackupPreset::class.java, w
                )
            }
            w.endArray()
            // v4：大肥鱼记忆 + familiar 设置（null 不写出，老格式字节级不变）
            if (familiarMemory != null) {
                w.name("familiarMemory").value(familiarMemory)
            }
            if (familiar != null) {
                w.name("familiar")
                gson.toJson(familiar, BackupFamiliar::class.java, w)
            }
            w.endObject()
        }
    }

    /**
     * 兼容旧签名的导出（整库进内存）。仅限小数据量/测试场景，
     * 常规备份请用 [export]（OutputStream 版，流式写入）。
     */
    suspend fun export(db: AppDatabase): String = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream()
        export(db, bytes)
        bytes.toString("UTF-8")
    }

    /** 恢复为"替换式"：清空现有会话后整体写入（ollama-app 的备份协议）。 */
    suspend fun import(db: AppDatabase, json: String): ImportResult = withContext(Dispatchers.IO) {
        val data = gson.fromJson(json, BackupData::class.java)
            ?: throw IllegalArgumentException("无法解析备份文件")
        // v3：白名单双标识（Familiar/TalkWithLLM）+ 版本上限，校验逻辑在纯函数 validateHeader
        validateHeader(data.app, data.version)?.let { throw IllegalArgumentException(it) }
        if (data.conversations == null || data.messages == null) {
            throw IllegalArgumentException("不是有效的 Familiar/TalkWithLLM 备份文件")
        }

        db.withTransaction {
            db.messageDao().deleteAllMessages()
            db.conversationDao().deleteAllConversations() // participants 由 FK CASCADE 一并清空
            // v1 备份不含模板/人设（复核 Minor-1）：清了就找不回来，跳过这两表让本机数据保留；
            // v2 备份含全量数据，维持替换式语义
            if (data.version >= 2) {
                db.promptTemplateDao().deleteAllTemplates()
                db.personaPresetDao().deleteAllPresets()
            }

            data.conversations.forEach { c ->
                db.conversationDao().insertConversation(
                    dev.liuyouwu.familiar.data.local.entity.ConversationEntity(
                        id = c.id,
                        title = c.title ?: "未命名",
                        systemPrompt = c.systemPrompt ?: "",
                        createdAt = c.createdAt ?: 0L,
                        updatedAt = c.updatedAt ?: 0L,
                        isChatRoom = c.isChatRoom ?: false,
                        isPinned = c.isPinned ?: false,
                        pinnedAt = c.pinnedAt,
                        tutorConfig = c.tutorConfig,
                        tutorState = c.tutorState
                    )
                )
            }
            data.messages.forEach { m ->
                db.messageDao().insertMessage(toEntity(m))
            }
            // v2：三张附属表回灌（v1 旧备份缺字段时 orEmpty() 为空，等价于"当时没有成员"）
            data.participants.orEmpty().forEach { p ->
                db.chatRoomParticipantDao().insertParticipant(toEntity(p))
            }
            data.promptTemplates.orEmpty().forEach { t ->
                db.promptTemplateDao().insertTemplate(
                    dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity(
                        name = t.name,
                        content = t.content,
                        isDefault = t.isDefault,
                        createdAt = if (t.createdAt > 0) t.createdAt else System.currentTimeMillis()
                    )
                )
            }
            data.personaPresets.orEmpty().forEach { p ->
                db.personaPresetDao().insert(toEntity(p))
            }
        }
        ImportResult(
            data.conversations.size, data.messages.size,
            data.participants.orEmpty().size,
            data.promptTemplates.orEmpty().size,
            data.personaPresets.orEmpty().size,
            data.familiarMemory,
            data.familiar
        )
    }

    /**
     * 从 SAF Uri 导入（推荐入口）：先用 openFileDescriptor 的 statSize 检查文件大小，
     * 超过 [MAX_IMPORT_BYTES] 直接报错，不再整读进内存；未超限才读入并走 [import]。
     */
    suspend fun import(db: AppDatabase, resolver: ContentResolver, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                ?: throw IllegalStateException("无法读取所选文件")
            if (size > MAX_IMPORT_BYTES) {
                throw IllegalStateException("备份文件过大（>200MB），无法导入")
            }
            val json = if (size > 0) {
                resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("无法读取所选文件")
            } else {
                // 云盘/网络 DocumentsProvider 常报 statSize=-1/0：上面的上限形同虚设，
                // 改为边读边计数，超过上限立刻中止，避免任意大文件整读进内存 OOM
                resolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArrayOutputStream(minOf(MAX_IMPORT_BYTES, 8 shl 20).toInt())
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMPORT_BYTES) {
                            throw IllegalStateException("备份文件过大（>200MB），无法导入")
                        }
                        buf.write(chunk, 0, n)
                    }
                    buf.toString("UTF-8")
                } ?: throw IllegalStateException("无法读取所选文件")
            }
            import(db, json)
        }
}
