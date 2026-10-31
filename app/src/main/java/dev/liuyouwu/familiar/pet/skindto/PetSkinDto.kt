package dev.liuyouwu.familiar.pet.skindto

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * pet.json 的 Gson 裸反序列化形态（SkillDefinition 同款防御）：一切可空，
 * 校验/回落/拒载分层在 PetSkinJson。本包被 proguard keep（<fields>），
 * 防 R8 类横向合并与字段改名（2026-10-25 release 事故同款坑）。
 */
class PetSkinDto(
    val name: String? = null,
    val accent: String? = null,
    val avatar: String? = null,
    val head: String? = null,
    val persona: SkinPersonaDto? = null,
    val clips: Map<String, ClipDto>? = null,
)

class SkinPersonaDto(
    val identity: String? = null, val personality: String? = null, val traits: String? = null,
    val speechStyle: String? = null, val catchphrase: String? = null, val exampleDialogs: String? = null,
    val selfName: String? = null, val address: String? = null,
)

class ClipDto(val frames: List<FrameEntry?>? = null, val loop: Boolean? = null)

/** 帧条目宽容读取（SkillJson QuickCommandAdapter 同款）：非数组/长度≠2/类型不符 → null 元素，由 PetSkinJson 统一丢帧告警 */
data class FrameEntry(val file: String?, val ms: Long?)

object FrameEntryAdapter : TypeAdapter<FrameEntry>() {
    override fun write(out: JsonWriter, value: FrameEntry?) {
        if (value == null) { out.nullValue(); return }
        out.beginArray().value(value.file).value(value.ms).endArray()
    }

    override fun read(reader: JsonReader): FrameEntry? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return null }
        val parts = mutableListOf<Any?>()
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                // nextString 对 NUMBER 令牌返回字面量字符串：整数毫秒 "1100"→1100L，
                // 浮点 "1100.5"/指数 "1e3"→toLongOrNull=null→丢帧（nextLong 遇浮点抛
                // NumberFormatException 会整皮拒载，违背逐条目宽容分层，审查 P1-1）
                JsonToken.STRING, JsonToken.NUMBER -> parts += reader.nextString()
                else -> { reader.skipValue(); parts += null }
            }
        }
        reader.endArray()
        val file = parts.getOrNull(0) as? String
        val ms = (parts.getOrNull(1) as? String)?.toLongOrNull()
        return if (file == null || ms == null) null else FrameEntry(file, ms)
    }
}
