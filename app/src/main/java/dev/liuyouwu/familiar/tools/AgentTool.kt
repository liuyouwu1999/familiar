package dev.liuyouwu.familiar.tools

import com.google.gson.JsonObject

/**
 * Agent 工具接口（spec §7）：一个工具 = 自描述（name/description/JSON Schema）
 * + 可挂起执行。异常即失败（ToolRegistry.execute 捕获后回填 isError ToolResult；
 * CancellationException 例外——取消必须传播，不得被改写成"执行失败"，
 * 对照旧 ChatViewModel 搜索 catch 块的取消守卫）。
 */
interface AgentTool {
    val name: String
    val description: String

    /** JSON Schema 字符串形（ToolSpec.parametersJson 直通 WireConverter） */
    val parametersJson: String

    /**
     * 执行工具。[onProgress] 向 UI 工具卡片推送进行中状态（如"正在搜索：xxx"），
     * 工具不保证调用时机与次数。
     */
    suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String
}
