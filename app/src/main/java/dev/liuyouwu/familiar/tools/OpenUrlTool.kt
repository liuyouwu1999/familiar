package dev.liuyouwu.familiar.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject

/**
 * open_url（spec §3 / Task 3）：http(s) 外链交系统处理——ACTION_VIEW +
 * FLAG_ACTIVITY_NEW_TASK（appContext 非 Activity 栈，无此旗标必崩）；
 * 先 queryIntentActivities 探 handler，非空才 startActivity，否则异常
 * "无可处理应用"（ToolRegistry 回填 isError，模型可改口而非静默无效果）。
 *
 * manifest `<queries>`（API 30+ 包可见性）已声明 ACTION_VIEW+http/https——
 * 不加则 queryIntentActivities 恒空 → 100% "无可处理应用"。
 * scheme 门用字符串 startsWith（spec 钉死口径；非规范 URL 交给浏览器自纠）。
 * 失败即异常不 catch（AgentTool 契约）。
 */
class OpenUrlTool(private val appContext: Context) : AgentTool {

    override val name = "open_url"
    override val description = "用系统应用（通常是浏览器）打开 http/https 链接"
    override val parametersJson =
        """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""

    override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String {
        val url = args.requiredString("url")
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "仅支持 http/https 地址：$url"
        }
        onProgress("正在打开 $url")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        require(appContext.packageManager.queryIntentActivities(intent, 0).isNotEmpty()) {
            "无可处理应用：$url"
        }
        appContext.startActivity(intent)
        return "已打开 $url"
    }
}

// ---------- 参数取值（口径同 FileTools：isJsonPrimitive 守卫，缺参即 IAE） ----------

private fun JsonObject.requiredString(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
        ?: throw IllegalArgumentException("缺少必填参数 $key")
