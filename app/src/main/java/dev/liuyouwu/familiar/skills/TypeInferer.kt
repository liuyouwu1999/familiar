package dev.liuyouwu.familiar.skills

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 迁移类型反推（spec §6.2）：旧会话无 skillId，按六序先命中即停反推 skillId。
 * 供 data 层迁移与备份导入反推调用（spec §8.2/§8.3，`data → skills` 仅 migration 依赖）。
 *
 * 纯函数：[roundtableAnchorCount] 由迁移调用方查询传入——DAO 的
 * countRoundtableAnchors 在 Migration 里不可直接调，Task 5 用游标 SQL COUNT 传入
 * （锚点即消息 LIKE '（圆桌话题：%'，>0 即圆桌）。
 *
 * 判定逻辑逐条复制自现实实现，但**不 import**（spec §3 依赖方向、skills 不依赖 util 的
 * 生产实现——测试里的双跑对照才允许 import）：kind 标记的 contains 语义照抄
 * FamiliarKind.isFamiliar / Eli5Prompts.isEli5，课堂配置合法性五字段判 null 语义照抄
 * TutorPrompts.fromJson（本地 [parseClassroomConfig]）。
 */
object TypeInferer {

    const val FAMILIAR = "familiar"
    const val ELI5 = "eli5"
    const val CLASSROOM = "classroom"
    const val ROUNDTABLE = "roundtable"
    const val CHATROOM = "chatroom"
    const val CHAT = "chat"

    private val gson = Gson()

    /**
     * spec §6.2 六序判定（先命中即停）：
     * 1. tutorConfig 含 `"kind":"familiar"` → familiar
     * 2. tutorConfig 含 `"kind":"eli5"` → eli5
     * 3. tutorConfig 可解析为合法课堂配置（五字段判 null）→ classroom
     * 4. isChatRoom 且 roundtableAnchorCount > 0 → roundtable
     * 5. isChatRoom → chatroom
     * 6. 其余 → chat
     */
    fun infer(tutorConfig: String?, isChatRoom: Boolean, roundtableAnchorCount: Int): String {
        // 1-2：contains 语义照抄 FamiliarKind.isFamiliar:14-15 / Eli5Prompts.isEli5:17-18
        // （含 null 判空；配置同时含两种 kind 时 familiar 先命中）
        if (tutorConfig != null && tutorConfig.contains("\"kind\":\"familiar\"")) return FAMILIAR
        if (tutorConfig != null && tutorConfig.contains("\"kind\":\"eli5\"")) return ELI5
        // 3：课堂配置判定（本地复制 TutorPrompts.fromJson 的五字段判 null 语义）
        if (parseClassroomConfig(tutorConfig)) return CLASSROOM
        // 4-6
        if (isChatRoom && roundtableAnchorCount > 0) return ROUNDTABLE
        if (isChatRoom) return CHATROOM
        return CHAT
    }

    /**
     * 课堂配置形状——与 TutorPrompts.TutorConfig 的五字段逐一对应（@SerializedName
     * 锁键名，混淆下已有全局 keep 规则双保险）。多余键（如 kind/cardId）被 Gson 忽略，
     * 与现实实现行为一致。
     */
    private data class ClassroomConfig(
        @SerializedName("subject") val subject: String? = null,
        @SerializedName("level") val level: String? = null,
        @SerializedName("style") val style: String? = null,
        @SerializedName("tone") val tone: String? = null,
        @SerializedName("language") val language: String? = null,
    )

    /**
     * 判定逻辑复制自 TutorPrompts.fromJson:41-50：Gson 反序列化绕过 Kotlin 构造器，
     * 五字段（subject/level/style/tone/language）任一缺失即非空类型实际为 null → 非法；
     * Gson 异常（畸形 JSON/类型不符/空）→ false。与 TutorPrompts.fromJson != null 等价
     * （classmate 可空缺省，不参与判定）。
     */
    fun parseClassroomConfig(json: String?): Boolean = try {
        if (json == null) {
            false
        } else {
            val cfg = gson.fromJson(json, ClassroomConfig::class.java)
            cfg?.subject != null && cfg.level != null && cfg.style != null &&
                cfg.tone != null && cfg.language != null
        }
    } catch (_: Exception) {
        false
    }
}
