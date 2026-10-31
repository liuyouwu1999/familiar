package dev.liuyouwu.familiar.pet

import com.google.gson.GsonBuilder
import dev.liuyouwu.familiar.pet.skindto.ClipDto
import dev.liuyouwu.familiar.pet.skindto.FrameEntry
import dev.liuyouwu.familiar.pet.skindto.FrameEntryAdapter
import dev.liuyouwu.familiar.pet.skindto.PetSkinDto

/**
 * pet.json 宽容解析（SkillJson §6.4 同款分层，spec §3 规则表的唯一实现）：
 * - 拒载仅四条：JSON 畸形 / name 空白 / clips 缺失空 / idle 无有效帧 → null + 告警；
 * - 其余逐项宽容：未知状态键、ms≤0、条目类型非法、文件名越界、文件不存在 → 丢该项告警照载；
 * - 解析成功后无条件注入派生 clip（THINKING/SEARCHING→BUSY、SPEAKING→IDLE；基态缺失落 IDLE）——
 *   PetHub.kt 原帧表（已迁皮肤包 assets/pets/）.let 派生的语义平移，spec §3"派生 clip"行；
 * - thinking/searching/speaking 键非法（与派生双重定义），落"未知状态键"告警忽略。
 * 纯 JVM 可测：exists/warn 注入，不触 Android 类。
 */
object PetSkinJson {

    private val gson = GsonBuilder()
        .registerTypeAdapter(FrameEntry::class.java, FrameEntryAdapter)
        .create()

    private val STATE_KEYS = mapOf(
        "idle" to PetHub.Mood.IDLE, "busy" to PetHub.Mood.BUSY, "done" to PetHub.Mood.DONE,
        "error" to PetHub.Mood.ERROR, "shy" to PetHub.Mood.SHY, "turn" to PetHub.Mood.TURN,
        "sleep" to PetHub.Mood.SLEEP,
        // 表演动作（P6 任务4b）：可选键，皮肤缺键即无此动作（运行时回落 IDLE、抽选被过滤）
        "dance" to PetHub.Mood.DANCE, "wave" to PetHub.Mood.WAVE, "jump" to PetHub.Mood.JUMP,
        "eat" to PetHub.Mood.EAT, "sneak" to PetHub.Mood.SNEAK, "token" to PetHub.Mood.TOKEN,
        "meal" to PetHub.Mood.MEAL, "hum" to PetHub.Mood.HUM, "tail" to PetHub.Mood.TAIL,
    )

    private val DERIVED = mapOf(
        PetHub.Mood.THINKING to PetHub.Mood.BUSY,
        PetHub.Mood.SEARCHING to PetHub.Mood.BUSY,
        PetHub.Mood.SPEAKING to PetHub.Mood.IDLE,
    )

    private val ACCENT_RE = Regex("^#[0-9A-Fa-f]{6}$")

    fun parse(id: String, json: String, exists: (String) -> Boolean, warn: (String) -> Unit): PetSkin? {
        val raw: PetSkinDto? = try {
            gson.fromJson(json, PetSkinDto::class.java)
        } catch (_: Exception) {
            null
        }
        if (raw == null) { warn("PetSkin[$id] pet.json 畸形或为空，拒载"); return null }
        val name: String? = raw.name
        if (name.isNullOrBlank()) { warn("PetSkin[$id] name 缺失或空白，拒载"); return null }
        val rawClips = raw.clips
        if (rawClips.isNullOrEmpty()) { warn("PetSkin[$id] clips 缺失或空，拒载"); return null }

        val clips = mutableMapOf<PetHub.Mood, Clip>()
        for ((key, dto) in rawClips) {
            val mood = STATE_KEYS[key]
            if (mood == null) { warn("PetSkin[$id] clips 含未知状态键 '$key'，已忽略"); continue }
            val clip = clipOf(id, key, dto, exists, warn)
            if (clip != null) clips[mood] = clip
        }
        val idle = clips[PetHub.Mood.IDLE]
        if (idle == null) { warn("PetSkin[$id] idle clip 缺失或无有效帧，拒载"); return null }

        // 派生（同引用注入；基态缺失落 IDLE——与运行时"?: idle"回落语义一致）
        DERIVED.forEach { (from, base) -> clips[from] = clips[base] ?: idle }

        return PetSkin(
            id = id,
            name = name,
            accent = raw.accent?.let {
                if (ACCENT_RE.matches(it)) it else { warn("PetSkin[$id] accent 非法 '$it'，置 null"); null }
            },
            persona = raw.persona?.let {
                SkinPersona(it.identity, it.personality, it.traits, it.speechStyle, it.catchphrase, it.exampleDialogs, it.selfName, it.address)
            },
            clips = clips,
            avatarFile = safeFile(id, "avatar", raw.avatar, exists, warn),
            headFile = safeFile(id, "head", raw.head, exists, warn),
        )
    }

    private fun clipOf(id: String, key: String, dto: ClipDto?, exists: (String) -> Boolean, warn: (String) -> Unit): Clip? {
        if (dto == null) { warn("PetSkin[$id] clips.$key 为 null，视同缺失"); return null }
        val frames = dto.frames.orEmpty().mapIndexedNotNull { i, e ->
            when {
                e == null -> { warn("PetSkin[$id] clips.$key 第${i + 1}帧类型非法，已丢弃"); null }
                e.file == null || e.ms == null -> { warn("PetSkin[$id] clips.$key 第${i + 1}帧字段非法，已丢弃"); null }
                e.ms <= 0 -> { warn("PetSkin[$id] clips.$key 第${i + 1}帧 ms≤0，已丢弃"); null }
                !isBareFileName(e.file) -> { warn("PetSkin[$id] clips.$key 第${i + 1}帧文件名越界 '${e.file}'，已丢弃"); null }
                !exists(e.file) -> { warn("PetSkin[$id] clips.$key 第${i + 1}帧文件不存在 '${e.file}'，已丢弃"); null }
                else -> e.file to e.ms
            }
        }
        if (frames.isEmpty()) return null
        return Clip(frames, dto.loop ?: true)
    }

    private fun safeFile(id: String, field: String, value: String?, exists: (String) -> Boolean, warn: (String) -> Unit): String? =
        when {
            value.isNullOrBlank() -> null
            !isBareFileName(value) -> { warn("PetSkin[$id] $field 文件名越界 '$value'，字段作废"); null }
            !exists(value) -> { warn("PetSkin[$id] $field 文件不存在 '$value'，字段作废"); null }
            else -> value
        }

    /** 裸文件名：禁路径分隔符与 ".."（filesDir 皮肤可能来自不可信来源，路径穿越零容忍，spec §3） */
    internal fun isBareFileName(name: String): Boolean =
        name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains("..")
}
