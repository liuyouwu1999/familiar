// SkillHostReviewSessionTest.kt
package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import dev.liuyouwu.familiar.agent.InMemoryMessageStore
import dev.liuyouwu.familiar.core.llm.client.LlmClient
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity
import dev.liuyouwu.familiar.skills.SkillRegistry
import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillRepoPort
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillSettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 复习侧信道路径（Task 10 Step 3）组装级钉死：
 * - reviewSession 装配出的 ReviewChat 多轮 ask 历史单调累加（第 2 轮请求的历史含
 *   前 1 轮 user+assistant 与本轮 user）；
 * - **不落库红线**：任意多轮 ask 后 store（InMemoryMessageStore 记账假件）恒空表
 *   ——复习消息绝不进消息链路（runOnChain 不出现在调用链上，loop.run 直驱）；
 * - system 来自 ReviewCoachPrompt（含条目清单与 JSON 收尾指令）；
 * - cancel 后链死（isActive=false），后续 ask 静默收空（不抛）。
 */
class SkillHostReviewSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 文本回放 LlmClient：按调用序回放回复正文（每调用记一次请求，无工具面）。 */
    private class TextScriptedLlm(vararg replies: String) : LlmClient {
        val calls = mutableListOf<LlmRequest>()
        private val scripts = ArrayDeque(replies.toList())
        private var blockSeq = 0
        override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
            calls += req
            val reply = scripts.removeFirstOrNull() ?: error("第 ${calls.size} 次调用无脚本")
            emit(StreamEvent.RunStart("s${calls.size}"))
            val id = "s${calls.size}-b${blockSeq++}"
            emit(StreamEvent.BlockStart(id, "text"))
            emit(StreamEvent.BlockDelta(id, reply))
            emit(StreamEvent.BlockEnd(id))
            emit(StreamEvent.TurnEnd(emptyList(), false))
            emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
        }
    }

    private class FakeSettings : SkillSettingsPort {
        override fun familiarMode() = "default"
        override fun familiarAddress() = "老板"
        override fun familiarSelfName() = "我"
        override fun petColor() = "blue"
        override fun webSearchEnabled() = false
        override fun maxContext() = 8192
        override fun currentModel() = "test-model"
    }

    private class FakeRepo : SkillRepoPort {
        override suspend fun updateSegments(messageId: Long, segmentsJson: String?) {}
        override suspend fun updateTutorState(conversationId: Long, stateJson: String) {}
        override suspend fun getParticipants(conversationId: Long) = emptyList<dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity>()
        override suspend fun getConversation(conversationId: Long) = null
        override suspend fun updateTitle(conversationId: Long, title: String) {}
    }

    private fun entry(id: Long, point: String) = LearningProfileEntryEntity(
        id = id, topic = "课题$id", point = point, status = "UNDERSTOOD", sourceRoomId = 9,
        createdAt = 0, lastReviewedAt = 0, nextReviewAt = 1,
    )

    private fun hostWith(llm: LlmClient): SkillHost = SkillHost(
        registry = SkillRegistry.fromAssetsJsons(emptyMap()),
        settings = FakeSettings(),
        repo = FakeRepo(),
        memoryStore = FamiliarMemoryStore(tmp.newFolder()),
        store = InMemoryMessageStore(),
        llm = llm,
        summarizer = { "S" },
        agentFilesRoot = tmp.newFolder(),
        appContext = Application(),
        agentOkHttp = OkHttpClient(),
        hooksScope = scope,
    )

    private fun textOf(req: LlmRequest): String =
        req.history.lastOrNull()?.blocks?.filterIsInstance<dev.liuyouwu.familiar.core.llm.model.ContentBlock.Text>()
            ?.joinToString("\n") { it.text }.orEmpty()

    @Test
    fun `多轮ask历史累加 请求自带上轮上下文`() = runBlocking {
        val llm = TextScriptedLlm("第一题：顶点式是什么？", "答对了！收尾 [{\"entryId\":1,\"pass\":true}]")
        val host = hostWith(llm)
        val chat = host.reviewSession(listOf(entry(1, "顶点式"), entry(2, "对称轴")))
        assertEquals("第一题：顶点式是什么？", chat.ask("开始"))
        assertEquals("答对了！收尾 [{\"entryId\":1,\"pass\":true}]", chat.ask("顶点坐标是 (-b/2a, f(-b/2a))"))
        // 第 1 轮请求：仅本轮 user
        assertEquals(2, llm.calls.size)
        assertEquals("开始", textOf(llm.calls[0]))
        // 第 2 轮请求：上轮 user + 上轮 assistant + 本轮 user
        val h2 = llm.calls[1].history
        assertEquals(3, h2.size)
        assertTrue(textOf(llm.calls[1]).endsWith("顶点坐标是 (-b/2a, f(-b/2a))"))
        assertEquals("第一题：顶点式是什么？", h2[1].blocks
            .filterIsInstance<dev.liuyouwu.familiar.core.llm.model.ContentBlock.Text>()
            .joinToString("") { it.text })
        // system = ReviewCoachPrompt 产物：含条目清单与 JSON 收尾指令
        assertTrue(llm.calls[0].system.contains("entryId|要点"))
        assertTrue(llm.calls[0].system.contains("1|顶点式"))
        assertTrue(llm.calls[0].system.contains("JSON 收尾"))
        // 请求零工具（复习无工具面）
        assertTrue(llm.calls[0].tools.isEmpty())
    }

    @Test
    fun `不落库红线 多轮ask后store恒空`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = TextScriptedLlm("问1", "判1 [{\"entryId\":1,\"pass\":true}]")
        val host = SkillHost(
            registry = SkillRegistry.fromAssetsJsons(emptyMap()),
            settings = FakeSettings(),
            repo = FakeRepo(),
            memoryStore = FamiliarMemoryStore(tmp.newFolder()),
            store = store,
            llm = llm,
            summarizer = { "S" },
            agentFilesRoot = tmp.newFolder(),
            appContext = Application(),
            agentOkHttp = OkHttpClient(),
            hooksScope = scope,
        )
        val chat = host.reviewSession(listOf(entry(1, "顶点式")))
        chat.ask("开始")
        chat.ask("答")
        assertEquals(2, llm.calls.size)
        // 任意会话 id 下均无落库行（review 链 id + 其他 id 双查）
        assertTrue(store.list("review").isEmpty())
        assertTrue(store.list("").isEmpty())
    }

    @Test
    fun `cancel后链死 isActive翻转`() = runBlocking {
        val llm = TextScriptedLlm("问1")
        val chat = hostWith(llm).reviewSession(listOf(entry(1, "顶点式")))
        assertTrue(chat.isActive)
        chat.cancel()
        assertFalse(chat.isActive)
    }
}
