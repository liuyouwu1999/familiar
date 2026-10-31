package dev.liuyouwu.familiar.tools

import com.google.gson.JsonParser
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec
import kotlinx.coroutines.CancellationException

/**
 * 工具注册表（spec §7）：工具的注册/查找/规格导出入口，以及 AgentLoop 唯一的
 * 工具执行通道。执行契约：
 * - 未知工具 / 非法 JSON 参数 / 工具抛异常 → isError ToolResult（消息可读）；
 * - 预算门（[installBudgetGate]）在参数解析与执行**之前**求值，false → isError
 *   "搜索预算已用尽"——预算状态留在 agent 层 chainContext，经此回调流入工具层，
 *   tools 层不反向依赖 agent（spec §4.3）；
 * - CancellationException 原样重抛：用户停止不能被降级成一条失败结果
 *   （对照旧 ChatViewModel 搜索 catch 的取消守卫，审计低-7）。
 * - [isGated] 供 AgentLoop 判定"执行成功后是否递减链预算"（仅受门管的工具递减）。
 */
class ToolRegistry {

    private val tools = linkedMapOf<String, AgentTool>()
    private val budgetGates = mutableMapOf<String, () -> Boolean>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun find(name: String): AgentTool? = tools[name]

    /** 按入参顺序导出已注册工具的规格；未注册名跳过（不抛错，缺工具由探测/降级层处理） */
    fun specs(names: List<String>): List<ToolSpec> = names.mapNotNull { name ->
        tools[name]?.let { ToolSpec(it.name, it.description, it.parametersJson) }
    }

    /**
     * 给工具装预算门：每次执行前求值，false = 预算已尽，本次调用直接 isError。
     * 同名重复安装覆盖旧门（预算计数以最后装配的回调为准）。
     */
    fun installBudgetGate(toolName: String, gate: () -> Boolean) {
        budgetGates[toolName] = gate
    }

    /** 该工具是否装了预算门（Task 11 预算递减判定） */
    fun isGated(toolName: String): Boolean = budgetGates.containsKey(toolName)

    suspend fun execute(
        call: ContentBlock.ToolCall,
        onProgress: (String) -> Unit,
    ): ContentBlock.ToolResult {
        val tool = find(call.name)
            ?: return ContentBlock.ToolResult(
                call.id, call.name, "未知工具：${call.name}（未注册）", isError = true)

        budgetGates[call.name]?.let { gate ->
            if (!gate()) {
                return ContentBlock.ToolResult(call.id, call.name, "搜索预算已用尽", isError = true)
            }
        }

        // 解析非挂起：此处只可能是 Gson 的 JSON/类型异常，无需取消守卫
        val args = try {
            JsonParser.parseString(call.argsJson).asJsonObject
        } catch (e: Exception) {
            return ContentBlock.ToolResult(
                call.id, call.name, "非法工具参数：${e.message}", isError = true)
        }

        return try {
            ContentBlock.ToolResult(call.id, call.name, tool.execute(args, onProgress), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ContentBlock.ToolResult(
                call.id, call.name, e.message ?: e.javaClass.simpleName, isError = true)
        }
    }
}
