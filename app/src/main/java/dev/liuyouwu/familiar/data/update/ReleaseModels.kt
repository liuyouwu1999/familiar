package dev.liuyouwu.familiar.data.update

import com.google.gson.Gson
import com.google.gson.JsonObject

data class ReleaseAsset(val name: String, val url: String)

data class Release(
    val tagName: String,
    val htmlUrl: String,
    val body: String?,
    val assets: List<ReleaseAsset>,
)

/** GitHub Releases /releases/latest 响应解析：只取 tag_name/html_url/body/assets 四个字段，畸形输入返回 null */
object ReleaseJsonParser {

    private val gson = Gson()

    fun parse(json: String): Release? = runCatching {
        val o = gson.fromJson(json, JsonObject::class.java) ?: return@runCatching null
        val tag = o.primitive("tag_name") ?: ""
        if (tag.isEmpty()) return@runCatching null
        val arr = o.getAsJsonArray("assets")
        Release(
            tagName = tag,
            htmlUrl = o.primitive("html_url") ?: "",
            body = o.get("body")?.takeIf { it.isJsonPrimitive }?.asString,
            assets = arr?.mapNotNull { el ->
                val a = el as? JsonObject ?: return@mapNotNull null
                val name = a.primitive("name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val url = a.primitive("browser_download_url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ReleaseAsset(name, url)
            }.orEmpty(),
        )
    }.getOrNull()

    private fun JsonObject.primitive(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}

/** 单 APK 发版：从资产里挑安装包；多个 apk 时优先 Familiar 前缀的官方包 */
object AssetPicker {
    fun pick(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            .sortedByDescending { it.name.startsWith("Familiar", ignoreCase = true) }
            .firstOrNull()
}
