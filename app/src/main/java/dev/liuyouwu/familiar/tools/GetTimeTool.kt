package dev.liuyouwu.familiar.tools

import com.google.gson.JsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Agent 基础工具：获取当前本地时间（ToolFactory BASIC 集注册，
 * 六张 skill JSON 挂名；同时是 CapabilityProber 的探测件）。
 * 无参数 schema；输出本地时间（用户设备的墙钟，模型无时钟时以此为准）。
 */
class GetTimeTool : AgentTool {

    override val name = "get_current_time"
    override val description = "获取当前本地时间"
    override val parametersJson = """{"type":"object","properties":{},"required":[]}"""

    override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String {
        onProgress("正在查询当前时间")
        val now = LocalDateTime.now()
        val weekday = "周" + "一二三四五六日"[now.dayOfWeek.value - 1]
        return now.format(FMT) + "（$weekday）"
    }

    companion object {
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
