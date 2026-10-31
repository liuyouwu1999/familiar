package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import dev.liuyouwu.familiar.core.llm.client.LlmClient
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.probe.ToolCapability
import dev.liuyouwu.familiar.agent.InMemoryMessageStore
import dev.liuyouwu.familiar.agent.SamplingParams
import dev.liuyouwu.familiar.agent.MultiAgentOrchestrator
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.pet.PetSkinStore
import dev.liuyouwu.familiar.search.SearchEngine
import dev.liuyouwu.familiar.search.SearchHit
import dev.liuyouwu.familiar.search.SearchResult
import dev.liuyouwu.familiar.skills.SkillRegistry
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.util.ConversationDefaults
import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import dev.liuyouwu.familiar.util.persona.PersonaCard
import dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks
import dev.liuyouwu.familiar.viewmodel.skillhooks.ClassroomHooks
import dev.liuyouwu.familiar.viewmodel.skillhooks.FamiliarParams
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillRepoPort
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillSettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SkillHost 组装工厂 + familiar/classroom hooks 的集成测试（P3 Task 6）。
 *
 * 覆盖（brief 逐项）：
 * - 四类型 sessionFor 产出正确 systemPrompt（familiar 含派生变量值、classroom 含
 *   TS 状态段；chat 特例 system_prompt 直通）+ 每 active 会话装配缓存（切换重建）；
 * - familiar hooks：记忆注入（buildMemoryTail 语义：本轮 user 拼接→buildContext）、
 *   切段回调（含 rowIds）/updateSegments 落库、appendTurn 落盘 + 桌宠回调；
 * - classroom hooks：TS 状态注入反馈回路、拆条（首行带 Thinking）、废稿重试限一次、
 *   续讲深度（真 followUpRun）——含 **P1-T15 场景 4/5 复刻：续讲链预算共享 +
 *   中途停止不复活**（P1 台账"AutoContinueHooks 替身重校"项闭合）；
 * - searchAllowed 两态：familiar 关搜索 → 请求 searchAllowed=false、标记不解析；
 * - quickCommands / isSkillConv。
 *
 * 不重复 agent 层单测边界（AgentSessionTest/AgentLoopTest/AgentEndToEndTest 已钉死），
 * 只测组装后的整体行为。
 */
class SkillHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gson = Gson()

    // ---------- 测试基建（假 settings/repo/store/llm/engine） ----------

    /** 设置端口假件：字段可变，webSearchEnabled 切换两态用（petColor 域 = "blue"/"orange"，ChatScreen:145 同源）；open 仅为 SamplingSettings（B2-L1 用例 A）可继承 */
    private open class FakeSettings(
        var familiarModeValue: String = "default",
        var familiarAddressValue: String = "老板",
        var familiarSelfNameValue: String = "本鲸",
        var petColorValue: String = "blue",
        var webSearchEnabledValue: Boolean = false,
        var maxContextValue: Int = 8192,
        var modelValue: String = "test-model",
        var chatRoomMaxRepliesValue: Int = 3,
        var chatRoomAddressValue: String = "小刘鸭",
    ) : SkillSettingsPort {
        override fun familiarMode() = familiarModeValue
        override fun familiarAddress() = familiarAddressValue
        override fun familiarSelfName() = familiarSelfNameValue
        override fun petColor() = petColorValue
        override fun webSearchEnabled() = webSearchEnabledValue
        override fun maxContext() = maxContextValue
        override fun currentModel() = modelValue
        override fun chatRoomMaxReplies() = chatRoomMaxRepliesValue
        override fun chatRoomAddress() = chatRoomAddressValue
    }

    /** convSampling 非空假件（B2-L1 用例 A）：可变字段承载返回值，其余端口沿用缺省假件——注意 [FakeSettings] 本身不覆写 convSampling（用例 B 走接口缺省 null） */
    private class SamplingSettings(private val samplingValue: SamplingParams) : FakeSettings() {
        override fun convSampling() = samplingValue
    }

    /** repo 端口假件：记录 segments/tutorState/title 三类写 + 成员表/会话行读 */
    private class FakeRepo : SkillRepoPort {
        val segmentUpdates = CopyOnWriteArrayList<Pair<Long, String?>>()
        val tutorStateUpdates = CopyOnWriteArrayList<Pair<Long, String>>()
        val titleUpdates = CopyOnWriteArrayList<Pair<Long, String>>()
        val participants = LinkedHashMap<Long, List<ChatRoomParticipantEntity>>()
        val conversations = LinkedHashMap<Long, ConversationEntity>()
        var participantReads = 0
        override suspend fun updateSegments(messageId: Long, segmentsJson: String?) {
            segmentUpdates += messageId to segmentsJson
        }

        override suspend fun updateTutorState(conversationId: Long, stateJson: String) {
            tutorStateUpdates += conversationId to stateJson
        }

        override suspend fun getParticipants(conversationId: Long): List<ChatRoomParticipantEntity> {
            participantReads++
            return participants[conversationId].orEmpty()
        }

        override suspend fun getConversation(conversationId: Long): ConversationEntity? =
            conversations[conversationId]

        override suspend fun updateTitle(conversationId: Long, title: String) {
            titleUpdates += conversationId to title
            conversations[conversationId]?.let { conversations[conversationId] = it.copy(title = title) }
        }
    }

    /** 假搜索引擎（预算共享复刻：记录查询顺序） */
    private class FakeEngine : SearchEngine {
        val queries = CopyOnWriteArrayList<String>()
        override suspend fun search(query: String): SearchResult {
            queries += query
            return SearchResult(
                hits = listOf(SearchHit("关于$query 的头条", "https://example.com/$query", "片段", "example.com")),
                diagnostics = "命中 1 条",
            )
        }
    }

    /**
     * 脚本回放 LlmClient（E2E 同款 DSL）：每次调用回放一条脚本；标记生效条件对照
     * LlmClient 硬契约 = 请求带 web_search 工具 **且 searchAllowed 且预算>0**。
     * - `[T]正文` / `[K]思考` / `[C]query`（web_search 调用）/ 裸 `[搜索: q]` 行 /
     *   `[W]ms` 暂停 / `[!]` 截断
     */
    private class ScriptedLlm : LlmClient {
        val calls = CopyOnWriteArrayList<LlmRequest>()
        val budgetSeen = CopyOnWriteArrayList<Boolean>()
        private val scripts = ArrayDeque<String>()
        private val lock = Any()

        fun script(vararg turns: String) = synchronized(lock) { scripts.addAll(turns) }

        override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
            calls += req
            budgetSeen += req.budgetLeft()
            val s = synchronized(lock) { scripts.removeFirstOrNull() }
                ?: error("第 ${calls.size} 次 LLM 调用无脚本")
            emit(StreamEvent.RunStart("s-${calls.size}"))
            val effective = req.tools.any { it.name == "web_search" } &&
                req.searchAllowed && req.budgetLeft()
            var blockSeq = 0
            var truncated = false
            val toolCalls = mutableListOf<ContentBlock.ToolCall>()
            val text = StringBuilder()

            suspend fun flushText() {
                if (text.isNotEmpty()) {
                    val id = "b${blockSeq++}"
                    emit(StreamEvent.BlockStart(id, "text"))
                    emit(StreamEvent.BlockDelta(id, text.toString()))
                    emit(StreamEvent.BlockEnd(id))
                    text.setLength(0)
                }
            }

            suspend fun emitCall(query: String) {
                flushText()
                val id = "c${blockSeq++}"
                val args = """{"query":"$query"}"""
                emit(StreamEvent.BlockStart(id, "toolCall"))
                emit(StreamEvent.ToolCallDelta(id, "web_search", args))
                emit(StreamEvent.BlockEnd(id))
                toolCalls += ContentBlock.ToolCall(id, "web_search", args)
            }

            for (line in s.lines().map { it.trim() }) {
                val marker = MARKER_RE.matchEntire(line)
                when {
                    line.isEmpty() -> {}
                    line.startsWith("[T]") -> text.append(line.removePrefix("[T]"))
                    line.startsWith("[K]") -> {
                        flushText()
                        val id = "k${blockSeq++}"
                        emit(StreamEvent.BlockStart(id, "thinking"))
                        emit(StreamEvent.BlockDelta(id, line.removePrefix("[K]")))
                        emit(StreamEvent.BlockEnd(id))
                    }
                    line.startsWith("[C]") -> if (effective) emitCall(line.removePrefix("[C]").trim())
                    marker != null && effective -> emitCall(marker.groupValues[1])
                    marker != null -> {}   // 不生效（预算尽/禁搜/无工具）：标记行剥除
                    line.startsWith("[W]") -> delay(line.removePrefix("[W]").trim().toLong())
                    line == "[!]" -> truncated = true
                    // 裸文本行（课堂剧本 [老师]/[小柯] 台词等多行正文）原样拼接
                    else -> {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(line)
                    }
                }
            }
            flushText()
            emit(StreamEvent.TurnEnd(toolCalls.toList(), truncated))
            emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
        }

        companion object {
            val MARKER_RE = Regex("""\[搜索:\s*(.+?)]""")
        }
    }

    private fun loadJson(id: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("skills/$id.json"))
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    private fun registry(): SkillRegistry = SkillRegistry.fromAssetsJsons(
        listOf("chat", "familiar", "classroom", "eli5", "chatroom", "roundtable")
            .associateWith { loadJson(it) }
    )

    private class Harness(
        val settings: FakeSettings,
        val repo: FakeRepo,
        val engine: FakeEngine,
        val memory: FamiliarMemoryStore,
        val store: InMemoryMessageStore,
        val llm: ScriptedLlm,
        val host: SkillHost,
    )

    /** 装配被测 SkillHost：全接缝注入假件（gap 压短让续讲测试不拖墙钟） */
    private fun harness(
        gapMs: Long = 10,
        mode: () -> ToolMode = { ToolMode.MARKER },
        settings: FakeSettings = FakeSettings(),
        onSegmentPersisted: (String?, Int, Int) -> Unit = { _, _, _ -> },
        onRoleTurnEnded: (String, String) -> Unit = { _, _ -> },
        // MCP T4：快照注入缝（默认空 = 既有调用点零改动；仅 26 例传非空）
        mcpTools: List<AgentTool> = emptyList(),
        scope: CoroutineScope,
    ): Harness {
        val repo = FakeRepo()
        val engine = FakeEngine()
        val memory = FamiliarMemoryStore(tmp.newFolder())
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm()
        val host = SkillHost(
            registry = registry(),
            settings = settings,
            repo = repo,
            memoryStore = memory,
            store = store,
            llm = llm,
            summarizer = { "S[$it]" },
            // 工具面 T6：ToolFactory 三件（ToolFactoryTest P1-7 夹具先例——root=@TempDir
            // 沙箱、context=Application() 空构造、client=OkHttpClient()）
            agentFilesRoot = tmp.newFolder(),
            appContext = Application(),
            agentOkHttp = OkHttpClient(),
            mcpTools = mcpTools,
            ttsTrigger = { },
            petTurnEnded = { },
            onSegments = { _, _ -> },
            onRevealDone = { },
            // P4 T4：段级/角色级触发回调透传（事件桥接线的装配面）
            onSegmentPersisted = onSegmentPersisted,
            onRoleTurnEnded = onRoleTurnEnded,
            searchEngine = { engine },
            modeResolver = mode,
            hooksScope = scope,
            continuationGapMs = gapMs,
        )
        return Harness(settings, repo, engine, memory, store, llm, host)
    }

    private fun conversation(
        id: Long,
        tutorConfig: String? = null,
        systemPrompt: String = "",
        skillId: String? = null,
        tutorState: String? = null,
        skillParams: String? = null,
        title: String = "t",
    ) = ConversationEntity(
        id = id, title = title, systemPrompt = systemPrompt,
        tutorConfig = tutorConfig, tutorState = tutorState, skillId = skillId,
        skillParams = skillParams,
    )

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    /** 轮询等待条件成立（续讲/异步落库的确定性观察窗） */
    private suspend fun awaitTrue(deadlineMs: Long = 5000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (!cond()) {
            assertTrue("等待超时", System.currentTimeMillis() < deadline)
            delay(10)
        }
    }

    /**
     * 橙卡断言夹具（T6 personaOf 接线）：JVM 单测无 PetSkinStore.init（assetManager/filesRoot
     * 恒 null → get 返回 null → personaOf 落蓝卡），反射把 filesRoot 指向临时目录并播种
     * 最小合法 orange 皮肤（pet.json + 空帧文件），让「petColor=orange → 橙卡」走真链：
     * store filesDir 现扫 → personaOf 分支②（id∈{blue,orange} → 内置原卡）。
     * TemporaryFolder 收尾删目录后 filesDirFor 失配，对后续测试类等价于未 init。
     */
    private fun installOrangeSkinFixture() {
        val pets = tmp.newFolder("pets")
        val orange = File(pets, "orange").apply { mkdirs() }
        File(orange, "f.png").writeBytes(ByteArray(0))
        File(orange, "pet.json").writeText(
            """{"name":"中转站的大肥鱼","accent":"#D97757","clips":{"idle":{"frames":[["f.png",100]]}}}"""
        )
        PetSkinStore::class.java.getDeclaredField("filesRoot").apply { isAccessible = true }
            .set(PetSkinStore, pets)
    }

    // ---------- ① familiar sessionFor：派生变量 + 缓存 + chat/eli5/classroom ----------

    @Test
    fun `1 familiar session renders derived persona variables and caches per conversation`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val conv = conversation(1, tutorConfig = familiarConfig())
        h.llm.script("[T]在的，老板。")

        val session = h.host.sessionFor(conv)
        session.send("1", "在吗")

        val sys = h.llm.calls[0].system
        // 派生变量（蓝卡 + 设置覆写）：name/address/selfName/mode/tagBlock/memory_note
        assertTrue("name 未注入: $sys", sys.contains("「蓝色大肥鱼」"))
        assertTrue("selfName 设置值未注入", sys.contains("「本鲸」"))
        assertTrue("address 设置值未注入", sys.contains("「老板」"))
        assertTrue("mode 引导未注入", sys.contains("说话方式=默认"))
        assertTrue("tagBlock ifBlank 派生未注入", sys.contains("LANG_ZH_CN_ONLY"))
        assertTrue("tagBlock PERSONALITY 截断派生未注入", sys.contains("PERSONALITY_"))
        assertTrue("memory_note 静态声明未注入", sys.contains("记忆说明：你的长期记忆会以消息形式随上下文提供"))
        assertFalse("familiar 骨架不得残留未替换变量", Regex("\\{\\{\\w+\\}\\}").containsMatchIn(sys))
        // 橙卡随 petColor 切换（T6 起 petColor=皮肤 id，走 PetSkinStore 现扫 → personaOf）
        installOrangeSkinFixture()
        h.settings.petColorValue = "orange"
        val orange = h.host.sessionFor(conversation(2, tutorConfig = familiarConfig()))
        orange.send("2", "在吗")
        assertTrue(h.llm.calls[1].system.contains("「中转站的大肥鱼」"))
    }

    @Test
    fun `2 sessionFor caches per conversation and rebuilds on switch`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val c1 = conversation(1, tutorConfig = familiarConfig())
        val c2 = conversation(2, tutorConfig = familiarConfig())
        assertSame(h.host.sessionFor(c1), h.host.sessionFor(c1))          // 同会话缓存
        assertNotEquals(h.host.sessionFor(c1), h.host.sessionFor(c2))     // 切换重建
        assertSame(h.host.sessionFor(c2), h.host.sessionFor(c2))
        assertSame(h.host.sessionFor(c2), h.host.sessionFor(c2.copy(title = "改名")))  // 同 id 仍命中
    }

    @Test
    fun `3 chat system prompt passes through conversation row and eli5 renders template`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        h.llm.script("[T]答", "[T]答")
        h.host.sessionFor(conversation(3, systemPrompt = "你是测试助手")).send("3", "问")
        assertEquals("你是测试助手", h.llm.calls[0].system)   // chat 特例：会话行直通
        h.host.sessionFor(conversation(4, skillId = "eli5")).send("4", "问")
        assertTrue(h.llm.calls[1].system.isNotBlank())         // eli5 模板渲染非空
        assertTrue(h.llm.calls[1].system != "你是测试助手")
    }

    // ---------- ② familiar hooks：记忆注入 / 切段落库 / 落盘 + 桌宠 ----------

    @Test
    fun `4 familiar memory tail injected per request from user query and turn appended to memory`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        var petDoneCount = 0
        val host = SkillHost(
            registry = registry(), settings = h.settings, repo = h.repo, memoryStore = h.memory,
            store = h.store, llm = h.llm, summarizer = { "S[$it]" },
            agentFilesRoot = tmp.newFolder(), appContext = Application(), agentOkHttp = OkHttpClient(),
            petTurnEnded = { petDoneCount++ }, hooksScope = CoroutineScope(coroutineContext),
            continuationGapMs = 10,
        )
        // 预置记忆：稳定事实（自我披露抽取）+ 互动行
        h.memory.appendTurn("我的名字叫小明", "在的", "老板", "本鲸", 0L)
        h.llm.script("[T]在的，老板。")
        host.sessionFor(conversation(5, tutorConfig = familiarConfig())).send("5", "你还记得我吗")
        // 4 记忆注入：facts 块 + 记忆参考包裹（buildMemoryTail → buildContext 语义）。
        //   P5 起走尾部临时 user 附注（ContextBuilder.appendTrailingUserNote）：
        //   system 恒稳定（KV 前缀缓存锚点），注入文本位于末条 kept 消息之后
        val sys = h.llm.calls[0].system
        assertFalse("system 不得含记忆尾（缓存锚点）: $sys", sys.contains("记忆参考"))
        val tail = h.llm.calls[0].history.last()
        assertEquals(Role.USER, tail.role)
        assertTrue("记忆参考包裹未注入: ${textOf(tail)}", textOf(tail).contains("（记忆参考"))
        assertTrue("稳定事实未注入", textOf(tail).contains("我的名字叫小明"))
        // 附注在末条真实 user 之后（attachTimeContext 式尾部注入位）
        assertTrue(textOf(h.llm.calls[0].history[h.llm.calls[0].history.size - 2]).contains("你还记得我吗"))
        // 4' 本轮落盘：appendTurn（地址/自称按卡），桌宠回调（成功 run）
        awaitTrue { h.memory.current().lines.any { it.contains("你还记得我吗") && it.contains("本鲸") } }
        awaitTrue { petDoneCount == 1 }
    }

    @Test
    fun `5 familiar segments persisted via updateSegments and surfaced through onSegments with row id`() = runBlocking {
        var segCallback: Pair<Long, List<String>>? = null
        val repo = FakeRepo()
        val engine = FakeEngine()
        val memory = FamiliarMemoryStore(tmp.newFolder())
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm()
        val host = SkillHost(
            registry = registry(), settings = FakeSettings(), repo = repo, memoryStore = memory,
            store = store, llm = llm, summarizer = { "S[$it]" },
            agentFilesRoot = tmp.newFolder(), appContext = Application(), agentOkHttp = OkHttpClient(),
            onSegments = { id, segs -> segCallback = id to segs },
            searchEngine = { engine }, hooksScope = CoroutineScope(coroutineContext),
        )
        val reply = "今天天气不错。我们去吃鱼吧。吃完再睡一觉。"
        llm.script("[T]$reply")
        host.sessionFor(conversation(6, tutorConfig = familiarConfig())).send("6", "讲点什么")

        awaitTrue { segCallback != null && repo.segmentUpdates.isNotEmpty() }
        val (rowId, json) = repo.segmentUpdates.single()
        // 切段写库（segments 列 = JSON 数组）；回调携带同一行 id（VM 推进 reveal 的锚点）
        val segs: List<String> = gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
        assertTrue("应切出多段", segs.size >= 2)
        assertEquals(reply, segs.joinToString(""))        // 拼接无损
        assertEquals(rowId, segCallback?.first)
        assertEquals(segs, segCallback?.second)
        // rowId 对位：InMemory 合成行号（user=1、assistant=2）——切段锚定的是 assistant 行
        assertEquals(2L, rowId)
    }

    // ---------- ③ classroom hooks：状态注入 / 拆条 / 废稿重试 / 续讲 ----------

    @Test
    fun `6 classroom state block injected from tutorState column with stateToPromptBlock copy`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val state = """{"v":1,"sections":[{"t":"力学基础","goal":"理解惯性","st":"done"},{"t":"能量守恒","st":"active"}],"current":2,"quizRight":3,"quizTotal":4,"weak":["惯性","摩擦力"],"digest":"学完了惯性"}"""
        h.llm.script("[K]推演\n[T]这是一段足够长的中文回答，超过三十个汉字就不会被判定为废稿重试。")   // 无剧本标记 → 单条路径、无续讲
        h.host.sessionFor(conversation(7, tutorConfig = classroomConfig(), tutorState = state))
            .send("7", "继续")
        val sys = h.llm.calls[0].system
        // 注入文案照抄 stateToPromptBlock：标题/大纲标记/当前节/小测战绩/薄弱点/摘要
        //   P5 起走尾部临时 user 附注：system 恒稳定（不含状态块），注入文本在历史末条之后
        assertFalse("system 不得含 TS 状态块（缓存锚点）: $sys", sys.contains("当前学习进度"))
        val tail = textOf(h.llm.calls[0].history.last())
        assertEquals(Role.USER, h.llm.calls[0].history.last().role)
        assertTrue("状态段标题未注入: $tail", tail.contains("# 当前学习进度（系统记录，汇报进度/继续教学时以此为准）"))
        assertTrue("大纲 done 标记未注入", tail.contains("✓1. 力学基础"))
        assertTrue("大纲 active 标记未注入", tail.contains("▶2. 能量守恒"))
        assertTrue("当前节未注入", tail.contains("当前进行到第 2 节。"))
        assertTrue("小测战绩未注入", tail.contains("小测战绩：3/4。"))
        assertTrue("薄弱点未注入", tail.contains("薄弱点：惯性、摩擦力。讲评时优先针对这些。"))
        assertTrue("摘要未注入", tail.contains("最近学习摘要：学完了惯性"))
        // 单条路径：终稿落库为 TS 清洗后的正文（extractState 从正文移除标记的迁移）
        val row = h.store.list("7").single { it.role == Role.ASSISTANT }
        assertEquals("这是一段足够长的中文回答，超过三十个汉字就不会被判定为废稿重试。", textOf(row))
    }

    @Test
    fun `7 classroom script splits into speaker rows with thinking on first row`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val ts = "<!--TS:{\"v\":1,\"phase\":\"await_user\",\"sections\":[{\"t\":\"力学\",\"st\":\"done\"}],\"current\":1}-->"
        h.llm.script("[K]先想一想\n[T]推演丢弃这段---课堂---\n[老师]你好！我是你的物理私教。今天讲力的第一课：惯性。惯性是物体保持原来运动状态的脾气，刹车时身体前倾就是它在表演。\n[小柯]为什么先讲力？\n[老师]因为力学是整个物理的地基，先打好地基后面才不会晃。$ts")
        h.host.sessionFor(conversation(8, tutorConfig = classroomConfig())).send("8", "开始上课")

        awaitTrue { h.repo.tutorStateUpdates.isNotEmpty() }
        // 拆条：[老师]/[小柯]/[老师] → 3 行 assistant（相邻同人合并 → 老师两条不相邻保留）
        val rows = h.store.list("8").filter { it.role == Role.ASSISTANT }
        assertEquals(3, rows.size)
        assertEquals(listOf("老师", "小柯", "老师"), rows.map { it.sender?.name })
        // 首行带 Thinking（T2 pre 钩子拆条契约）；全部同 turnId（regenerate 删整组）
        assertTrue(rows[0].blocks[0] is ContentBlock.Thinking)
        assertFalse(rows[1].blocks.any { it is ContentBlock.Thinking })
        assertEquals(rows[0].turnId, rows[1].turnId)
        assertEquals(rows[0].turnId, rows[2].turnId)
        // 正文剥离：分隔符前推演不落库、TS 标记不落库、说话人标记不留在正文
        val texts = rows.map { textOf(it) }
        assertTrue(texts.none { it.contains("---课堂---") || it.contains("<!--TS:") || it.contains("[老师]") })
        assertTrue(texts[0].contains("今天讲力"))
        // TS 提取落 tutorState 列（含 sections 全量，非仅 phase）
        val written = h.repo.tutorStateUpdates.single().second
        assertTrue(written.contains("\"sections\"") && written.contains("力学"))
        // await_user：不续讲
        delay(80)
        assertEquals(1, h.llm.calls.size)
    }

    @Test
    fun `8 classroom draft retry fires once at depth zero with nonblank user text`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        h.llm.script(
            "[T]Sure, let me think about this problem step by step.",   // run1 废稿（无中文正文）
            "[T]Okay, another english draft again.",                     // run2 仍废稿 → 限一次，不再重试
        )
        h.host.sessionFor(conversation(9, tutorConfig = classroomConfig())).send("9", "帮我学物理")

        awaitTrue { h.llm.calls.size == 2 }
        delay(120)   // 若误发第 3 个 run，此处之后必然可见
        assertEquals("废稿重试限一次", 2, h.llm.calls.size)
        assertTrue(h.repo.tutorStateUpdates.isEmpty())   // 无 TS 标记不落状态
    }

    @Test
    fun `9 classroom autocontinue persists TS state and reinjects it on next segment`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val tsTeaching = "<!--TS:{\"v\":1,\"phase\":\"teaching\",\"sections\":[{\"t\":\"力学\",\"st\":\"active\"}],\"current\":1,\"digest\":\"开讲\"}-->"
        val tsAwait = "<!--TS:{\"v\":1,\"phase\":\"await_user\",\"sections\":[{\"t\":\"力学\",\"st\":\"done\"}],\"current\":1}-->"
        h.llm.script(
            "[T][老师]你好！我是你的物理私教。先讲第一段：惯性是物体的脾气。$tsTeaching",
            "[T][老师]第二段：摩擦力到处都在，刹车就靠它。$tsAwait",
        )
        h.host.sessionFor(conversation(10, tutorConfig = classroomConfig())).send("10", "开始上课")

        awaitTrue { h.llm.calls.size == 2 }
        delay(80)
        assertEquals("await_user 后收束", 2, h.llm.calls.size)
        // 续讲请求注入上一段上报的状态（防失忆反馈回路）——P5 起在尾部 user 附注（不在 system）
        assertFalse("续讲请求 system 不得含状态段（缓存锚点）", h.llm.calls[1].system.contains("当前学习进度"))
        val contTail = h.llm.calls[1].history.last()
        assertEquals(Role.USER, contTail.role)
        val contNote = textOf(contTail)
        assertTrue("续讲请求未注入状态段: $contNote", contNote.contains("当前学习进度"))
        assertTrue(contNote.contains("力学"))
        // 续讲调度指令同走尾部附注（CONTINUATION_NOTE 不再进 system）
        assertTrue(contNote.contains(ClassroomHooks.CONTINUATION_NOTE))
        // wire 层缓存锚点（P5 审查 Minor）：前后两请求 system 字节相等——逐请求变化
        // 的状态段/续讲指令全在尾部附注，system 前缀缓存跨 run 命中
        assertEquals(h.llm.calls[0].system, h.llm.calls[1].system)
        // TS 状态两段各自落库
        assertEquals(2, h.repo.tutorStateUpdates.size)
        assertTrue(h.repo.tutorStateUpdates[0].second.contains("\"teaching\""))
        assertTrue(h.repo.tutorStateUpdates[1].second.contains("\"await_user\""))
    }

    // ---------- ③' P4 T4：课堂段级落库回调（拆条组逐行，组末行=组完成） ----------

    /**
     * 拆条 run：3 行剧本 → 回调序 (turnId,0,3)(turnId,1,3)(turnId,2,3)，turnId 与
     * 落库组一致；单条路径 run（无剧本标记的普通回答）→ 单行组 (turnId,0,1)。
     * 回调时行**已落库**（onPersisted 位——VM 事件桥的 turnId 组全文读赖此序）。
     */
    @Test
    fun `9b classroom per-segment persisted callbacks carry turn group shape after rows land`() = runBlocking {
        val segs = CopyOnWriteArrayList<Triple<String?, Int, Int>>()
        val rowsAtCallback = CopyOnWriteArrayList<Int>()
        var storeRef: InMemoryMessageStore? = null   // 回调期（send 内）已装配，构造期不触达
        val h = harness(
            onSegmentPersisted = { tid, i, total ->
                segs += Triple(tid, i, total)
                // list 是挂起接口：回调在 send 协程内同步触发，嵌套 runBlocking 直读
                //（InMemory 无真挂起点，嵌套事件循环安全）
                val landed = storeRef?.let { kotlinx.coroutines.runBlocking { it.list("17") } }
                rowsAtCallback += landed?.count { row -> row.role == Role.ASSISTANT } ?: 0
            },
            scope = CoroutineScope(coroutineContext),
        )
        storeRef = h.store
        val tsAwait = "<!--TS:{\"v\":1,\"phase\":\"await_user\"}-->"
        h.llm.script("[T][老师]你好！我是你的物理私教。今天讲惯性，先看一个实验现象。\n[小柯]为什么刹车时身体会前倾？\n[老师]因为惯性让身体保持原来的运动状态。$tsAwait")
        h.host.sessionFor(conversation(17, tutorConfig = classroomConfig())).send("17", "开始上课")

        awaitTrue { segs.size >= 3 }
        delay(80)   // 若误对每行重复成组触发，此处后可见
        val rows = h.store.list("17").filter { it.role == Role.ASSISTANT }
        assertEquals(3, rows.size)
        // 逐行回调：(i, total) 全组形状一致，turnId=拆条组 turnId
        assertEquals(listOf(0, 1, 2), segs.map { it.second })
        assertEquals(listOf(3, 3, 3), segs.map { it.third })
        assertTrue(segs.all { it.first == rows[0].turnId && rows[0].turnId != null })
        // 回调时点在整组落库后（persistProduced：append 全部行 → onPersisted 一次）：
        // 首次回调即可见全组 3 行——VM 的 turnId 组全文读不会读空/读半组
        assertEquals(listOf(3, 3, 3), rowsAtCallback)

        // 单条路径（新会话，无剧本标记的普通回答）：单行组——回调序接在拆条组之后
        h.llm.script("[T]这是一段足够长的中文回答，不会被判定为废稿而触发重试链路。")
        h.host.sessionFor(conversation(18, tutorConfig = classroomConfig())).send("18", "问")
        awaitTrue { segs.size >= 4 }
        delay(50)
        val singleRow = h.store.list("18").single { it.role == Role.ASSISTANT }
        assertEquals(listOf(Triple(singleRow.turnId, 0, 1)), segs.drop(3))
    }

    // ---------- ③'' P4 T7：课堂流式标记瞬态剥离（streamingText 显示层过滤） ----------

    /**
     * 组装接线契约（P4 T7，评审 P1-5 落点裁定）：contentProtocol=classroomScript 时
     * SkillHost 注入显示层过滤——streamingText 无 TS 标记/说话人行首前缀；落库与
     * ContentProtocol 输入不受影响（拆条管线照常看到 `[老师]` 标记 → 产出说话人
     * sender 行——若过滤污染了协议输入，拆条退化单条路径、sender 为空）。非课堂
     * 会话不注入：标记照旧进 streamingText（既有行为不变）。
     */
    @Test
    fun `9c classroom streaming text is display-filtered while contentprotocol split stays intact`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val ts = "<!--TS:{\"v\":1,\"phase\":\"await_user\"}-->"
        // 正文 ≥30 CJK 防废稿重试；await_user 防续讲——单 run 收束
        h.llm.script("[T][老师]你好！我是你的物理私教。惯性是物体保持原来运动状态的脾气，刹车时身体前倾就是它在表演。$ts")
        h.host.sessionFor(conversation(19, tutorConfig = classroomConfig())).send("19", "开始上课")
        awaitTrue {
            !h.host.sessionFor(conversation(19, tutorConfig = classroomConfig())).events.value.streaming
        }

        // 显示层：streamingText 无标记前缀，台词正文保留
        val st = h.host.sessionFor(conversation(19, tutorConfig = classroomConfig())).events.value.streamingText
        assertFalse("TS 标记不得进 streamingText: $st", st.contains("<!--TS:"))
        assertFalse("说话人前缀不得进 streamingText: $st", st.contains("[老师]"))
        assertTrue("台词正文保留", st.contains("惯性是物体保持原来运动状态的脾气"))

        // ContentProtocol 输入不受影响：拆条管线照常看到标记 → 说话人 sender 行
        val rows = h.store.list("19").filter { it.role == Role.ASSISTANT }
        assertEquals(1, rows.size)
        assertEquals("老师", rows[0].sender?.name)
        assertEquals("你好！我是你的物理私教。惯性是物体保持原来运动状态的脾气，刹车时身体前倾就是它在表演。", textOf(rows[0]))

        // 非 classroom 会话（chat）不注入过滤：标记照旧进 streamingText
        h.llm.script("[T][老师]标记照旧<!--TS:x-->")
        h.host.sessionFor(conversation(20, systemPrompt = "chat sys", skillId = "chat")).send("20", "问")
        val raw = h.host.sessionFor(conversation(20, systemPrompt = "chat sys", skillId = "chat")).events.value.streamingText
        assertTrue("无过滤器会话的流式载体保持原样: $raw", raw.contains("[老师]") && raw.contains("<!--TS:"))
    }

    /**
     * 过滤器纯函数增量契约（P4 T7）：调用方按**累计文本**逐窗口套用——标记跨 delta
     * 切分时，完整标记聚合后照剥；流中未收尾标记（残缺 `<!--TS:` 体、起始头真前缀、
     * 末行未合拢 `[xx` 前缀）自起点扣留——每个中间窗口都不得让任何标记形态（含残缺
     * 头）闪现。
     */
    @Test
    fun `9d classroom display filter withholds partial markers across delta boundaries`() {
        val f = ClassroomDisplayFilter.of(ClassroomHooks.DEFAULT_SPEAKERS)
        val deltas = listOf("[老", "师]你好", "\n<!--TS", ":{\"v\":1}", "-->", "\n[小柯]", "为什么？")
        var acc = ""
        for (d in deltas) {
            acc += d
            val shown = f(acc)
            assertFalse(
                "中间窗口泄露标记形态: acc=[$acc] shown=[$shown]",
                shown.contains("<!--TS") || shown.contains("[老师]") || shown.contains("[小柯]") ||
                    shown.contains("[阿穗]"),
            )
        }
        // 终态：完整标记整剥（原位留空行）、行首说话人前缀剥除、台词正文保留
        assertEquals("你好\n\n为什么？", f(acc))
    }

    @Test
    fun `9e display filter cuts at first residual marker when multiple unclosed remain`() {
        // P4-T7 minor 一词改动钉死：多个未收尾标记时自**首个**残留 BEGIN 截除
        //（与 ContentProtocol.extractState 的 indexOf 语义对齐；原 lastIndexOf
        // 会把更早的残留 `<!--TS:` 体漏进显示）
        val f = ClassroomDisplayFilter.of(ClassroomHooks.DEFAULT_SPEAKERS)
        val shown = f("讲解正文<!--TS:{\"a\":1<!--TS:{\"b\":2")
        assertTrue(shown.contains("讲解正文"))
        assertFalse(shown.contains("<!--TS"))
        assertFalse(shown.contains("{\"a\":1"))
    }

    // ---------- ④ P1-T15 场景复刻：续讲链预算共享 + 中途停止不复活 ----------

    @Test
    fun `10 followup chain shares exhausted budget and strips markers (P1-T15 scene 5 replica)`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val tsAwait = "<!--TS:{\"v\":1,\"phase\":\"await_user\"}-->"
        h.llm.script(
            "[C]q1\n[C]q2",                                    // run1 t1：两次搜索 → 链预算 2→0
            "[T][老师]一答讲解。",                                // run1 t2：作答（有剧本 → 续讲）
            "[C]再搜\n[T][老师]二答讲解。预算尽说明照常保留。",        // run2：标记剥除不执行
            "[T][老师]三答收束并等待学生。$tsAwait",                  // run3：await_user 收束
        )
        h.host.sessionFor(conversation(11, tutorConfig = classroomConfig())).send("11", "问")

        awaitTrue { h.repo.tutorStateUpdates.isNotEmpty() }
        delay(80)
        assertEquals(4, h.llm.calls.size)   // run1 两 turn + run2 + run3
        // 预算回路逐请求可观测：首 turn true（2>0），其后全部 false（0）——run2 的标记
        // 因 budgetLeft=false 剥除不解析（共享链预算，不重置）
        assertEquals(listOf(true, false, false, false), h.llm.budgetSeen.toList())
        // 剥除不执行：引擎只搜过 run1 的两次，落库的 ToolCall 块恰为 run1 的两个
        // （同 turn 两个调用共享一条 assistant 消息——按块计数，E2E 场景2 同口径）
        assertEquals(listOf("q1", "q2"), h.engine.queries.toList())
        assertEquals(2, h.store.list("11").sumOf { m -> m.blocks.count { it is ContentBlock.ToolCall } })
        // run2 正文保留（剥的是标记行，不是整段文本）
        assertTrue(h.store.list("11").any { textOf(it).contains("二答讲解") })
        // await_user 终态：链收束、终态发布
        awaitTrue { !h.host.sessionFor(conversation(11, tutorConfig = classroomConfig())).events.value.streaming }
    }

    @Test
    fun `11 stop during continuation gap kills chain and no followup revives it (P1-T15 scene 4 replica)`() = runBlocking {
        val h = harness(gapMs = 600, scope = CoroutineScope(coroutineContext))
        val tsTeaching = "<!--TS:{\"v\":1,\"phase\":\"teaching\"}-->"
        h.llm.script(
            "[T][老师]一答讲解。$tsTeaching",
            "[W]1000\n[T][老师]二答讲解。",
        )
        val session = h.host.sessionFor(conversation(12, tutorConfig = classroomConfig()))
        session.send("12", "问")   // run1 完成 → onRunEnd 挂 600ms gap 后 followUpRun

        delay(200)   // 处于段间 gap（600ms delay 中）
        assertTrue("gap 期间流式态保持（终态由链上最后 run 统一发布）", session.events.value.streaming)
        assertEquals(1, h.llm.calls.size)

        session.stop()   // 用户停止：取消整链——gap 后续 run 不复活
        awaitTrue { !session.events.value.streaming }
        delay(300)
        assertEquals("gap 后的续讲 run 不复活", 1, h.llm.calls.size)
        assertTrue(h.store.list("12").any { textOf(it).contains("一答讲解") })   // 已产出照常落库
    }

    // ---------- ④b 预算门新语义（P5 T2 评审 P1-2 修订：openChain 单点回填） ----------

    @Test
    fun `10b budget gate restricts from first run and refuses next search in same run`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        h.settings.webSearchEnabledValue = true   // familiar user-toggle 搜索开
        // 单 turn 三连搜索：链预算 2。openChain 即回填 → q3 必被门拒（旧 onRunEnd
        // 回填方案下首 run 门无引用恒放行，q3 会执行——本断言即新判据）
        h.llm.script(
            "[C]q1\n[C]q2\n[C]q3",   // run1 t1：请求期 budgetLeft=true（三调用都上 wire）
            "[T]这是一段足够长的中文讲解内容，不会被判定为废稿而触发重试链路。",
        )
        val session = h.host.sessionFor(conversation(21, tutorConfig = familiarConfig()))
        session.send("21", "问")
        awaitTrue { h.llm.calls.size >= 2 && !session.events.value.streaming }

        // 执行侧门：q1/q2 放行（2→1→0），q3 被拒（0）
        assertEquals(listOf("q1", "q2"), h.engine.queries.toList())
        // 被拒调用落 isError ToolResult（"搜索预算已用尽"），ToolCall 块 3 个（模型侧照发）
        val results = h.store.list("21").flatMap { m -> m.blocks.filterIsInstance<ContentBlock.ToolResult>() }
        assertTrue(
            "q3 被预算门拒绝且落 isError 回执",
            results.any { it.isError && it.output.contains("搜索预算已用尽") },
        )
        assertEquals(3, h.store.list("21").sumOf { m -> m.blocks.count { it is ContentBlock.ToolCall } })
    }

    // ---------- ⑤b modeResolver 纯缓存/目录读（P5 T2，评审 P1-4） ----------

    @Test
    fun `15b mode resolver reads cache and catalog only per run`() = runBlocking {
        // reader 只持目录+快照（构造上无传输）——快照 NATIVE → 请求 NATIVE；miss → MARKER
        val reader = dev.liuyouwu.familiar.core.llm.probe.CapabilityReader(
            dev.liuyouwu.familiar.core.llm.probe.ModelCatalog(
                listOf(dev.liuyouwu.familiar.core.llm.probe.ModelCatalog.Family("qwen3", ToolCapability.NATIVE))
            ),
        ) { _, _, m -> if (m.startsWith("qwen3")) ToolCapability.NATIVE else null }

        val h = harness(
            mode = { reader.mode("ollama", "http://192.168.1.5:11434", "qwen3:32b") },
            scope = CoroutineScope(coroutineContext),
        )
        h.llm.script("[T]原生模式作答")
        h.host.sessionFor(conversation(22, tutorConfig = familiarConfig())).send("22", "问")
        assertEquals("缓存 NATIVE → NATIVE 模式上请求", ToolMode.NATIVE, h.llm.calls[0].mode)

        // 目录/缓存 miss：UNKNOWN 折叠 MARKER 兜底（不探测、不报错）
        val h2 = harness(
            mode = { reader.mode("ollama", "http://192.168.1.5:11434", "llama-3") },
            scope = CoroutineScope(coroutineContext),
        )
        h2.llm.script("[T]兜底模式作答")
        h2.host.sessionFor(conversation(23, tutorConfig = familiarConfig())).send("23", "问")
        assertEquals("miss 折叠 UNKNOWN → MARKER", ToolMode.MARKER, h2.llm.calls[0].mode)
    }

    // ---------- ⑤ searchAllowed 两态 + quickCommands/isSkillConv ----------

    @Test
    fun `12 familiar search off yields searchAllowed false and marker never resolves`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))   // webSearchEnabled 默认 false
        h.llm.script("[T]正文\n[搜索: 想搜的内容]")
        h.host.sessionFor(conversation(13, tutorConfig = familiarConfig())).send("13", "问")

        assertEquals(1, h.llm.calls.size)
        assertFalse("familiar 关搜索：请求 searchAllowed=false", h.llm.calls[0].searchAllowed)
        assertTrue("禁搜时搜索标记不解析（等效 tools 空）", h.engine.queries.isEmpty())
        // 正文保留（标记行剥除不报错）
        assertTrue(h.store.list("13").any { textOf(it).contains("正文") })
        // 开搜索：标记解析为工具调用（user-toggle 语义；作答 turn 续一条脚本）
        h.settings.webSearchEnabledValue = true
        h.llm.script("[T]再问\n[搜索: 换个问题]", "[T]搜到了，正文照常收线。")
        h.host.sessionFor(conversation(13, tutorConfig = familiarConfig())).send("13", "再问")
        awaitTrue { h.engine.queries.isNotEmpty() }
        assertTrue(h.llm.calls[1].searchAllowed)
    }

    @Test
    fun `13 classroom search always on regardless of toggle`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))   // webSearchEnabled 默认 false
        h.llm.script("[T]这是一段足够长的中文讲解内容，不会被判定为废稿而触发重试链路。")
        h.host.sessionFor(conversation(14, tutorConfig = classroomConfig())).send("14", "问")
        assertTrue("classroom 恒 true（§6.2 表）", h.llm.calls[0].searchAllowed)
    }

    @Test
    fun `14 quickCommands from skill json and isSkillConv excludes orchestrator skills`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val classroom = h.host.quickCommands(conversation(15, tutorConfig = classroomConfig()))
        assertEquals(listOf("/plan", "/start", "/test", "/example", "/progress"), classroom.map { it.command })
        assertEquals("学习计划", classroom.first().label)
        assertTrue(h.host.quickCommands(conversation(16)).isEmpty())   // chat 无快捷条
        assertTrue(h.host.isSkillConv("classroom"))
        assertTrue(h.host.isSkillConv("familiar"))
        assertTrue(h.host.isSkillConv("chat"))
        assertTrue(h.host.isSkillConv("eli5"))
        // P4 T2：chatroom/roundtable 归 Orchestrator 装配（roundFor/sessionFor 可装配），不再是自留拒绝区
        assertTrue(h.host.isSkillConv("chatroom"))
        assertTrue(h.host.isSkillConv("roundtable"))
        assertFalse(h.host.isSkillConv("no-such-skill"))
        assertFalse(h.host.isSkillConv(null))
    }

    // ---------- ⑦ P4 T2：chatroom/roundtable 装配（ChatRoomHooks + Orchestrator） ----------

    /** members JSON 样例（T3 建室/编辑两写点的产出契约；字段与 participants 表同名） */
    private fun membersJson() =
        """{"members":[{"displayName":"阿宅","modelName":"m1","identity":"毒舌但心软的像素小精灵",""" +
            """"traits":"嘴硬心软","speechStyle":"短句","catchphrase":"哼",""" +
            """"exampleDialogs":"<START>\n用户：嗨\n阿宅：哼。","colorHex":"#FF0000"},""" +
            """{"displayName":"小柯","modelName":"","sortOrder":1}]}"""

    @Test
    fun `16 chatroom assembly prefers skillParams members with persona mapping and no table fallback`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val conv = conversation(21, skillId = "chatroom", skillParams = membersJson())
        val ra = h.host.roundFor(conv)

        assertEquals("scheduler=PLANNER（skill JSON behaviors.multiAgent.scheduler）",
            MultiAgentOrchestrator.Scheduler.PLANNER, ra.scheduler)
        assertNull("chatroom 无建室锚点", ra.openingMessage)
        assertEquals(2, ra.roles.size)
        val a = ra.roles[0]
        assertEquals("阿宅", a.name)
        assertEquals("成员模型名贯通 RoleSpec.model", "m1", a.model)
        assertEquals("#FF0000", a.colorHex)
        // 人设卡 v2 字段映射（identity/traits/称呼）+ 群规段（memberSystem 语义迁移）
        assertTrue("人设卡 PERSONA_LOAD 段未注入: ${a.prompt}", a.prompt.contains("PERSONA_LOAD"))
        assertTrue("identity 段未注入", a.prompt.contains("毒舌但心软的像素小精灵"))
        assertTrue("称呼缺省未注入", a.prompt.contains("称呼用户为「小刘鸭」"))
        assertTrue("群规段未注入", a.prompt.contains("绝不代替其他角色或用户发言"))
        assertFalse("SEARCH_RULE 已在 P2 剥离，组装侧不得回加", a.prompt.contains("查证纪律"))
        assertTrue("群规段应位于 prompt 末尾（memberSystem 输出结构）", a.prompt.trimEnd().endsWith("然后加新东西"))
        // 空白 modelName → null（回落 session 模型供应商，generateCharacterReply ifBlank 同语义）
        assertEquals("小柯", ra.roles[1].name)
        assertNull(ra.roles[1].model)
        // members 优先：participants 表不得读
        assertEquals("members 优先：表读不得发生", 0, h.repo.participantReads)
        // 迁移新家直钉：同成员同 address 与 ChatRoomHooks.memberPrompt（迁自
        // ChatRoomPrompts.memberSystem 的唯一实现）输出逐字一致
        val entity = ChatRoomParticipantEntity(
            conversationId = 21, modelName = "m1", displayName = "阿宅",
            identity = "毒舌但心软的像素小精灵", traits = "嘴硬心软", speechStyle = "短句",
            catchphrase = "哼", exampleDialogs = "<START>\n用户：嗨\n阿宅：哼。", colorHex = "#FF0000",
        )
        assertEquals(ChatRoomHooks.memberPrompt(entity), a.prompt)
    }

    @Test
    fun `17 chatroom members fall back to participants table when skillParams has no members`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        val conv = conversation(22, skillId = "chatroom", skillParams = null)   // P4 前存量
        val legacy = ChatRoomParticipantEntity(
            conversationId = 22, modelName = "m2", displayName = "阿宅",
            personality = "毒舌但心软的像素小精灵", sortOrder = 1,
        )
        val v2 = ChatRoomParticipantEntity(
            conversationId = 22, modelName = "", displayName = "小柯",
            identity = "学习圆桌的讲师", traits = "一次只讲一个概念", sortOrder = 0,
        )
        h.repo.participants[22] = listOf(legacy, v2)

        val ra = h.host.roundFor(conv)

        assertEquals("回退读表（participantReads=1）", 1, h.repo.participantReads)
        assertEquals("sortOrder 序", listOf("小柯", "阿宅"), ra.roles.map { it.name })
        assertEquals("m2", ra.roles[1].model)
        // identity 空（旧数据）→ personality 拼法不回归；identity 非空 → v2 卡路径
        assertTrue("legacy 拼法未注入: ${ra.roles[1].prompt}", ra.roles[1].prompt.contains("你正在群聊中扮演「阿宅」"))
        assertTrue(ra.roles[1].prompt.contains("你的人设：毒舌但心软的像素小精灵"))
        assertTrue("v2 卡路径未注入", ra.roles[0].prompt.contains("PERSONA_LOAD"))
        // 等价锚点（表路径双分支）
        assertEquals(ChatRoomHooks.memberPrompt(legacy), ra.roles[1].prompt)
        assertEquals(ChatRoomHooks.memberPrompt(v2, "小刘鸭"), ra.roles[0].prompt)
    }

    @Test
    fun `18 chatroom round run wires planner model maxTurns and user-toggle search`() = runBlocking {
        val h = harness(
            settings = FakeSettings(chatRoomMaxRepliesValue = 5, chatRoomAddressValue = "主人"),
            scope = CoroutineScope(coroutineContext),
        )
        val conv = conversation(23, skillId = "chatroom", skillParams = membersJson())
        h.llm.script(
            """[T]{"turns":[{"speaker":"阿宅","point":"先接话"}]}""",
            "[T]阿宅的话",
        )
        val ra = h.host.roundFor(conv)
        h.store.append("23", AgentMessage(id = "u", role = Role.USER, blocks = listOf(ContentBlock.Text("聊点什么"))))
        ra.orchestrator.runRound("23", h.store.list("23"), ra.roles, ra.scheduler) { }

        assertEquals(2, h.llm.calls.size)
        // 承接③：plannerModel=全局当前模型（pickRoundPlan 的 currentModel 同源）+ maxTurnsPerRound=设置现值
        assertEquals("调度模型=主模型", "test-model", h.llm.calls[0].model)
        assertTrue("maxTurnsPerRound 注入规划 prompt: ${h.llm.calls[0].system}", h.llm.calls[0].system.contains("上限 5 条"))
        // user-toggle（skill JSON searchPolicy）跟设置：默认关 → 角色请求 searchAllowed=false
        assertFalse("chatroom 搜索跟设置（关）", h.llm.calls[1].searchAllowed)
        // 角色 prompt = memberPrompt（address 设置注入）
        assertEquals(ra.roles[0].prompt, h.llm.calls[1].system)
        assertTrue(h.llm.calls[1].system.contains("称呼用户为「主人」"))
        // 角色产出落库带 sender 盖章（Orchestrator 既有机制经组装通道贯通）
        val rows = h.store.list("23")
        assertEquals(SenderInfo("阿宅", "m1", "#FF0000"), rows[1].sender)
        assertEquals("阿宅的话", textOf(rows[1]))
    }

    @Test
    fun `18b members array write side round-trips through parseMembers`() {
        // P4 T3：建室/编辑两写点的产出侧（ChatRoomHooks.membersArray）与读侧
        // （parseMembers）对偶契约——字段同名同域，round-trip 等值。
        val dialogs = "<START>\n用户：嗨\n阿宅：哼。"
        val m = ChatRoomParticipantEntity(
            conversationId = 0L, modelName = "m1", displayName = "阿宅",
            personality = "毒舌但心软", identity = "像素小精灵", traits = "嘴硬心软",
            speechStyle = "短句", catchphrase = "哼",
            exampleDialogs = dialogs, colorHex = "#FF0000",
            sortOrder = 2,
        )
        val params = """{"topic":"量子纠缠","members":${ChatRoomHooks.membersArray(listOf(m))}}"""
        val back = ChatRoomHooks.parseMembers(params)!!.single()
        assertEquals("阿宅", back.displayName)
        assertEquals("m1", back.modelName)
        assertEquals("毒舌但心软", back.personality)
        assertEquals("像素小精灵", back.identity)
        assertEquals("嘴硬心软", back.traits)
        assertEquals("短句", back.speechStyle)
        assertEquals("哼", back.catchphrase)
        assertEquals(dialogs, back.exampleDialogs)
        assertEquals("#FF0000", back.colorHex)
        assertEquals(2, back.sortOrder)
    }

    @Test
    fun `19 roundtable assembly uses skill json presets always-on search and opening message`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))   // webSearchEnabled 默认 false
        val conv = conversation(24, skillId = "roundtable", skillParams = """{"topic":"为什么天空是蓝色的"}""")
        h.llm.script(
            """[T]{"turns":[{"speaker":"讲师","point":"开场"}]}""",
            "[T]讲师开场。",
        )
        val ra = h.host.roundFor(conv)

        assertEquals(MultiAgentOrchestrator.Scheduler.PLANNER, ra.scheduler)
        // skill JSON 三角色预设（RoundtablePrompts P2 迁移源）
        assertEquals(listOf("讲师", "怀疑者", "新手"), ra.roles.map { it.name })
        assertTrue(ra.roles[0].prompt.contains("学习圆桌的讲师"))
        // 锚点建室消息：openingMessage.template 以 topic 渲染（RoundtablePrompts.opening 同文）
        assertEquals("（圆桌话题：为什么天空是蓝色的。讲师先开场，一次一句，等人接话。）", ra.openingMessage)

        h.store.append("24", AgentMessage(id = "u", role = Role.USER, blocks = listOf(ContentBlock.Text("天为什么是蓝的"))))
        ra.orchestrator.runRound("24", h.store.list("24"), ra.roles, ra.scheduler) { }
        // always-on：设置关仍恒开（skill JSON searchPolicy 映射）
        assertTrue("roundtable 恒开搜索", h.llm.calls[1].searchAllowed)
        assertEquals(ra.roles[0].prompt, h.llm.calls[1].system)
    }

    // ---------- ⑧ P4 T2：titleTrigger 语义（豁免/去重/替换/回合粒度） ----------

    private fun titleCalls(llm: ScriptedLlm) =
        llm.calls.filter { it.system.contains("生成一个简短的标题") }

    @Test
    fun `20 title trigger replaces default title exempts familiar and skips non-default`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        // 替换：默认标题 + 引号清洗（generateTitleIfNeeded :2899-2906 文案照抄）
        h.repo.conversations[25] = conversation(25, systemPrompt = "你是测试助手", title = ConversationDefaults.NEW_CHAT)
        h.llm.script("[T]在的。", "[T]「环游月球之旅」")
        h.host.sessionFor(conversation(25, systemPrompt = "你是测试助手", title = ConversationDefaults.NEW_CHAT))
            .send("25", "我们聊聊登月吧")
        awaitTrue { h.repo.titleUpdates.isNotEmpty() }
        val title = titleCalls(h.llm).single()
        assertEquals("标题请求=独立轻量请求（文案照抄）",
            "根据以下对话内容，生成一个简短的标题（最多10个字）。只输出标题，不要输出其他内容。", title.system)
        assertEquals("标题请求用当前模型", "test-model", title.model)
        assertTrue("预览含 User 行", title.history.single().blocks.filterIsInstance<ContentBlock.Text>()
            .single().text.startsWith("User: "))
        assertEquals("引号清洗后替换", listOf(25L to "环游月球之旅"), h.repo.titleUpdates.toList())

        // familiar 豁免：标题=卡名永不自动命名（判定源=skillId，P3 T7 单点）
        h.repo.conversations[26] = conversation(26, skillId = "familiar", title = ConversationDefaults.NEW_CHAT)
        h.llm.script("[T]在的，老板。")
        h.host.sessionFor(conversation(26, skillId = "familiar", title = ConversationDefaults.NEW_CHAT))
            .send("26", "在吗")
        delay(150)
        assertEquals("familiar 豁免：不发起标题请求（仍只有替换段的 1 次）", 1, titleCalls(h.llm).size)
        assertTrue(h.repo.titleUpdates.none { it.first == 26L })

        // 非默认标题：不触发生成
        h.repo.conversations[27] = conversation(27, systemPrompt = "你是测试助手", title = "自定义标题")
        h.llm.script("[T]答")
        h.host.sessionFor(conversation(27, systemPrompt = "你是测试助手", title = "自定义标题")).send("27", "问")
        delay(150)
        assertEquals("非默认名不发起", 1, titleCalls(h.llm).size)
    }

    @Test
    fun `21 title trigger dedupes while one generation is in flight`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        h.repo.conversations[28] = conversation(28, systemPrompt = "你是测试助手", title = ConversationDefaults.NEW_CHAT)
        // chat1 / chat2 / 标题（慢 400ms——第二次触发落在在途窗口内）
        h.llm.script("[T]答一", "[T]答二", "[W]400\n[T]标题甲")
        val session = h.host.sessionFor(conversation(28, systemPrompt = "你是测试助手", title = ConversationDefaults.NEW_CHAT))
        session.send("28", "问一")
        session.send("28", "问二")   // 标题 1 在途 → 去重跳过（titleJob 语义）
        awaitTrue { h.repo.titleUpdates.isNotEmpty() }
        delay(200)                    // 若未去重，第二个标题请求必然已出现
        assertEquals("在途去重：仅一次标题请求", 1, titleCalls(h.llm).size)
    }

    @Test
    fun `22 chatroom round title fires once per round not per role`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        h.repo.conversations[29] = conversation(29, skillId = "chatroom", skillParams = membersJson(), title = ConversationDefaults.CHAT_ROOM)
        h.llm.script(
            """[T]{"turns":[{"speaker":"阿宅","point":"接话"},{"speaker":"小柯","point":"补充"}]}""",
            "[T]阿宅的话",
            "[T]小柯的话",
            "[T]圆桌话题讨论",
        )
        val ra = h.host.roundFor(conversation(29, skillId = "chatroom", skillParams = membersJson(), title = ConversationDefaults.CHAT_ROOM))
        h.store.append("29", AgentMessage(id = "u", role = Role.USER, blocks = listOf(ContentBlock.Text("聊点什么"))))
        ra.orchestrator.runRound("29", h.store.list("29"), ra.roles, ra.scheduler) { }
        delay(200)
        assertEquals("run 级装空：两个角色 run 都不触发标题", 0, titleCalls(h.llm).size)
        // 回合级触发：每回合一次（T3 VM 在 runRound 返回后调用）
        ra.roundTitleTrigger()
        awaitTrue { h.repo.titleUpdates.isNotEmpty() }
        assertEquals("回合级恰好一次", 1, titleCalls(h.llm).size)
        assertEquals(listOf(29L to "圆桌话题讨论"), h.repo.titleUpdates.toList())
    }

    // ---------- ⑧b convSampling 装配（B2-L1）：端口 → 链播种 → 请求携带 ----------

    @Test
    fun `23 convSampling non-null seeds sampling params into llm request`() = runBlocking {
        val h = harness(settings = SamplingSettings(SamplingParams(temperature = 0.9, frequencyPenalty = 0.4, presencePenalty = 0.3)), scope = CoroutineScope(coroutineContext))
        h.llm.script("[T]在的，老板。")
        h.host.sessionFor(conversation(30, tutorConfig = familiarConfig())).send("30", "问")
        val req = h.llm.calls.single()
        assertEquals("采样三参经端口→链播种贯通进请求", 0.9, req.temperature)
        assertEquals(0.4, req.frequencyPenalty)
        assertEquals(0.3, req.presencePenalty)
    }

    @Test
    fun `24 convSampling default null leaves all three sampling fields null`() = runBlocking {
        // FakeSettings 不覆写 convSampling（走接口缺省 null）——其余 5 skill 零 diff 的装配面钉子
        val h = harness(scope = CoroutineScope(coroutineContext))
        h.llm.script("[T]在的，老板。")
        h.host.sessionFor(conversation(31, tutorConfig = familiarConfig())).send("31", "问")
        val req = h.llm.calls.single()
        assertNull(req.temperature)
        assertNull(req.frequencyPenalty)
        assertNull(req.presencePenalty)
    }

    // ---------- ⑧c 工具面 T6：skill JSON tools 注入装配断言 ----------

    /**
     * tools 注入装配契约（T6）：sessionFor 后 session.toolsSpecs 名单 = skill JSON
     * tools 数组（specs 按 toolNames 保序导出——web_search 由 SkillHost 注册+装门，
     * 其余八件经 ToolFactory.install）。评审 P2-4 遮蔽陷阱的装配面钉子：八件必须
     * 真的上 session，而非止步于注册表。chat 八件不含 web_search；eli5 空表零注册。
     */
    @Test
    fun `25 skill json toolNames wire into session tool specs`() = runBlocking {
        val h = harness(scope = CoroutineScope(coroutineContext))
        // familiar：九名全量（web_search 在列——预算门随装；其余随 JSON 保序）
        val familiar = h.host.sessionFor(conversation(40, tutorConfig = familiarConfig()))
        assertEquals(
            listOf(
                "web_search", "get_current_time", "read_file", "write_file", "list_files",
                "http_get", "clipboard_read", "clipboard_write", "open_url",
            ),
            familiar.toolsSpecs.map { it.name },
        )
        // chatroom：五名（web_search + 探测/文件四件；扩展四件不在列）
        val chatroom = h.host.sessionFor(conversation(41, skillId = "chatroom", skillParams = membersJson()))
        assertEquals(
            listOf("web_search", "get_current_time", "read_file", "write_file", "list_files"),
            chatroom.toolsSpecs.map { it.name },
        )
        // chat：八件不含 web_search（MARKER 模式 wire 侧 effectiveTools 另收敛，不在此断言面）
        val chat = h.host.sessionFor(conversation(42, skillId = "chat"))
        assertEquals(
            listOf(
                "get_current_time", "read_file", "write_file", "list_files",
                "http_get", "clipboard_read", "clipboard_write", "open_url",
            ),
            chat.toolsSpecs.map { it.name },
        )
        // eli5：空表零注册
        assertTrue(h.host.sessionFor(conversation(43, skillId = "eli5")).toolsSpecs.isEmpty())
    }

    // ---------- ⑧d MCP T4：mcpTools 快照注入装配断言（方案 A 钉子） ----------

    /**
     * mcpTools 注入装配契约（T4 方案 A，Review Focus 1）：SkillHost 构造传
     * mcpTools=listOf(fakeTool) → sessionFor 后 session.toolsSpecs 含 fakeTool 名
     * 且 ToolSpec 三字段（name/description/parametersJson）逐字正确——快照追加在
     * skill JSON toolNames 导出的本地件之后（chat 八件 + 1）。既有 25 例断言原样绿
     * （本例之外的装配全部不传 mcpTools = 默认空）即"默认空零改动"的行为等价证明。
     */
    @Test
    fun `26 mcp tools snapshot appended into session tool specs`() = runBlocking {
        val fake = object : AgentTool {
            override val name = "mcp_demo__echo"
            override val description = "[MCP:demo] 回声工具"
            override val parametersJson = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""
            override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String = "ok"
        }
        val h = harness(scope = CoroutineScope(coroutineContext), mcpTools = listOf(fake))
        val specs = h.host.sessionFor(conversation(44, skillId = "chat")).toolsSpecs
        // 追加位次：skill JSON tools 名单在前（chat 八件）、MCP 快照殿后
        assertEquals(
            listOf(
                "get_current_time", "read_file", "write_file", "list_files",
                "http_get", "clipboard_read", "clipboard_write", "open_url",
                "mcp_demo__echo",
            ),
            specs.map { it.name },
        )
        // ToolSpec 三字段逐字正确（AgentTool 自描述直通，零加工）
        val mcpSpec = specs.last()
        assertEquals("mcp_demo__echo", mcpSpec.name)
        assertEquals("[MCP:demo] 回声工具", mcpSpec.description)
        assertEquals(
            """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}""",
            mcpSpec.parametersJson,
        )
        // eli5（空 toolNames）同样追加：MCP 快照不依赖 skill JSON 声明
        val eli5Specs = h.host.sessionFor(conversation(45, skillId = "eli5")).toolsSpecs
        assertEquals(listOf("mcp_demo__echo"), eli5Specs.map { it.name })
    }

    // ---------- ⑨ FamiliarParams 派生单元边界 ----------
    @Test
    fun `15 familiar params derive tag fallbacks and blank dialogs leave no blank line`() = runBlocking {
        val settings = FakeSettings(familiarModeValue = "passive")
        val card = PersonaCard(
            name = "测试鱼", identity = "", traits = "嘴硬", speechStyle = "短句",
            catchphrase = "哼", exampleDialogs = "", selfName = "我", address = "主人",
        )
        val params = FamiliarParams.derive(card, settings)
        // ifBlank 推导（PersonaPromptBuilder:52-66 迁移）：take(40)/take(20) 截断 + 条件拼接
        assertEquals("测试鱼", params["name"])
        assertEquals("一条住在你手机里的蓝色大肥鱼，慢吞吞，黏人，记性意外地好", params["identity"])
        assertEquals("LANG_ZH_CN_ONLY\nPERSONALITY_嘴硬\nCATCHPHRASE_哼", params["tagBlock"])
        assertTrue(params["tagGloss"]!!.startsWith("只使用简体中文；性格：嘴硬；说话方式：短句；"))
        assertTrue(params["mode"]!!.contains("说话方式=被动"))
        assertEquals("", params["exampleDialogs"])
        // 空值变量空行兜底（P2-T3 登记项）：空 exampleDialogs 渲染后不留连续空行
        val rendered = SkillInterpreterForTest.render(registry(), "familiar", params)
        assertFalse("空值段空行未兜底: [$rendered]", Regex("\\n\\s*\\n").containsMatchIn(FamiliarParams.sanitize(rendered)))
        // 非空 exampleDialogs 时正常注入
        val withDialogs = FamiliarParams.derive(card.copy(exampleDialogs = "<START>\n主人：嗨\n测试鱼：哼。"), settings)
        val rendered2 = SkillInterpreterForTest.render(registry(), "familiar", withDialogs)
        assertTrue(rendered2.contains("<START>"))
    }

    /** 测试内直用 SkillInterpreter 渲染（FamiliarParams 单元边界不引入 SkillHost） */
    private object SkillInterpreterForTest {
        fun render(registry: SkillRegistry, skillId: String, params: Map<String, String>): String =
            dev.liuyouwu.familiar.skills.SkillInterpreter(registry).assemble(skillId, params)!!.systemPrompt
    }

    private fun classroomConfig(): String =
        """{"subject":"物理","level":"零基础","style":"讲解型（通俗演示为主）","tone":"鼓励式","language":"中文"}"""

    /** 旧 FamiliarKind.configJson(0) 产物的常量副本（util 对象已随 P4 删除批 B 删） */
    private fun familiarConfig(): String = """{"kind":"familiar","cardId":0}"""
}
