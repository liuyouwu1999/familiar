package dev.liuyouwu.familiar.pet

/** 单状态帧序列：帧 = 皮肤目录内裸文件名 + 展示毫秒；loop=循环，否则播完定住末帧。
 *  自 PetHub 迁来（P5）：帧从 R.drawable id 换皮肤目录内文件名 */
data class Clip(val frames: List<Pair<String, Long>>, val loop: Boolean)

/** pet.json 可选 persona 字段（D3）：null 字段在 personaOf 里字段级回落蓝卡 */
data class SkinPersona(
    val identity: String? = null,
    val personality: String? = null,
    val traits: String? = null,
    val speechStyle: String? = null,
    val catchphrase: String? = null,
    val exampleDialogs: String? = null,
    val selfName: String? = null,
    val address: String? = null,
)

/** 解析成功并过回落/派生注入的皮肤。clips 保证含 IDLE，且 THINKING/SEARCHING/SPEAKING 已派生注入 */
data class PetSkin(
    val id: String,
    val name: String,
    val accent: String?,
    val persona: SkinPersona?,
    val clips: Map<PetHub.Mood, Clip>,
    val avatarFile: String?,   // null = idle 首帧
    val headFile: String?,     // null = avatar 链
)

/** 头图/陪伴卡位图取图位 */
enum class Kind { HEAD, AVATAR }

/** 取图链（spec §4）：HEAD → headFile → avatarFile → idle 首帧；AVATAR → avatarFile → idle 首帧。
 *  idle clip 缺失时返回 null（解析层保证 IDLE 恒在，此处纯防御） */
fun imageFile(skin: PetSkin, kind: Kind): String? {
    val idleFirst = skin.clips[PetHub.Mood.IDLE]?.frames?.firstOrNull()?.first
    return when (kind) {
        Kind.HEAD -> skin.headFile ?: skin.avatarFile ?: idleFirst
        Kind.AVATAR -> skin.avatarFile ?: idleFirst
    }
}
