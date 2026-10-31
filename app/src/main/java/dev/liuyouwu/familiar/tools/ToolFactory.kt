package dev.liuyouwu.familiar.tools

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import java.io.File

/**
 * Agent 工具统一注册工厂（spec §7 / Task 5）：按 [install] 入参 [ToolFactory] 的
 * toolNames 逐件构造并注册九件 AgentTool 中的八件。
 *
 * 生效集分层：[BASIC] 四件（get_current_time/read_file/write_file/list_files）、
 * [EXTENDED] 八件（BASIC + http_get/clipboard_read/clipboard_write/open_url）。
 * 生效清单的最终裁决不在此处——调用方（SkillHost 装配）按模式/设置传入 toolNames，
 * 工厂只负责"名单 → 实例"的映射与注册。
 *
 * 语义契约：
 * - **web_search 不归工厂**：注册必须与 SessionBudgetGate 预算门同装（SkillHost
 *   专管，见 SkillHost.WEB_SEARCH 装配段），工厂单独造门必缺——已知名静默让渡
 *   （不 warn：生产装配若把 web_search 连同其余名单传入属正常让渡，告警反而噪声）；
 * - **未知名 warn 跳过不抛**：单个坏名不废整次装配（降级层语义，ToolRegistry
 *   specs 同口径）；
 * - 同名重复出现按 ToolRegistry.register 覆盖语义（幂等，后写胜）；
 * - 注册顺序 = toolNames 出现顺序（ToolRegistry 是 LinkedHashMap，specs 导出
 *   保序，模型侧工具清单顺序稳定）。
 *
 * warn 注入缝（默认参）：缺省走 android.util.Log.w；JVM 单测未 mock 会崩
 * （LlmClient.kt:108 onPenaltyFallback / FamiliarMemoryStore.kt:9 "log 可注入"
 * 仓内先例），测试注入记录器断言。生产装配点用缺省即可。
 *
 * Context 夹具假设（评审 P1-7，测试已单点验证 ToolFactoryTest 首用例）：
 * 八件构造路径装配期零框架调用——文件根不读 context.filesDir（构造直注）、
 * 剪贴板/open_url 的 context 只存引用，getSystemService/startActivity 均推迟到
 * execute 期——故纯 JVM 测试可用 `android.app.Application()` 空构造当 Context。
 * http_get 传 agentOkHttp 原件，派生（callTimeout + 关自动重定向）在 HttpGetTool
 * 构造内完成，共享连接池/超时基座不动。
 */
object ToolFactory {

    /** 基础集：探测件 + 文件三件套（无 Context/网络依赖） */
    val BASIC = listOf("get_current_time", "read_file", "write_file", "list_files")

    /** 扩展集：BASIC + 网络与框架四件（web_search 不在其列，归 SkillHost） */
    val EXTENDED = BASIC + listOf("http_get", "clipboard_read", "clipboard_write", "open_url")

    /** web_search 字面（SkillHost.WEB_SEARCH 同值——tools 层不反向依赖 viewmodel，各自钉死） */
    private const val WEB_SEARCH = "web_search"

    fun install(
        registry: ToolRegistry,
        toolNames: List<String>,
        agentFilesRoot: File,
        appContext: Context,
        agentOkHttp: OkHttpClient,
        warn: (String) -> Unit = { Log.w("ToolFactory", it) },
    ) {
        for (name in toolNames) {
            if (name == WEB_SEARCH) continue   // SkillHost 专管：预算门必须同装，让渡
            val tool = create(name, agentFilesRoot, appContext, agentOkHttp)
            if (tool == null) {
                warn("skill tools 含未知名 $name，跳过")
                continue
            }
            registry.register(tool)
        }
    }

    /** 名单名 → 工具实例的唯一映射；未知名返回 null（install 侧 warn 跳过） */
    private fun create(
        name: String,
        agentFilesRoot: File,
        appContext: Context,
        agentOkHttp: OkHttpClient,
    ): AgentTool? = when (name) {
        "get_current_time" -> GetTimeTool()
        "read_file" -> ReadFileTool(agentFilesRoot)
        "write_file" -> WriteFileTool(agentFilesRoot)
        "list_files" -> ListFilesTool(agentFilesRoot)
        "http_get" -> HttpGetTool(agentOkHttp)
        "clipboard_read" -> ClipboardReadTool(appContext)
        "clipboard_write" -> ClipboardWriteTool(appContext)
        "open_url" -> OpenUrlTool(appContext)
        else -> null
    }
}
