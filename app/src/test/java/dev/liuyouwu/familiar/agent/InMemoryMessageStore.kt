package dev.liuyouwu.familiar.agent

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Role

/**
 * [MessageStore] 的进程内实现：LinkedHashMap 按会话保插入序。P1 唯一实现，
 * 同时是 Task 14/15 测试的记账假件；P3 换 Room 实现（接口签名不变）。
 *
 * 归属（P2 终审 #32，P4 T8 落地）：grep 全仓证实生产代码零消费者（SkillHost/
 * RoomMessageStore 仅 KDoc 提及）——本类自始就是测试记账假件，故迁入 test 源集
 * （同包，测试侧零改动）；生产装配唯一路径是 RoomMessageStore。
 *
 * 线程模型：无内部锁——AgentSession 按链串行调用；测试侧 runBlocking 单协程，
 * 均不构成并发面。P3 持久化实现自行保证线程安全。
 */
class InMemoryMessageStore : MessageStore {

    private val rows = LinkedHashMap<String, MutableList<AgentMessage>>()

    /** 行 id 合成号：进程内单调、跨会话不重置（仅钩子对位用，非稳定键）。 */
    private var rowIdCounter = 0L

    override suspend fun append(conversationId: String, message: AgentMessage): List<Long> {
        val rowId = ++rowIdCounter
        rows.getOrPut(conversationId) { mutableListOf() }.add(message)
        return listOf(rowId)
    }

    override suspend fun list(conversationId: String): List<AgentMessage> =
        rows[conversationId]?.toList() ?: emptyList()

    override suspend fun deleteTurn(conversationId: String, turnId: String): Int {
        val list = rows[conversationId] ?: return 0
        val before = list.size
        list.removeAll { it.turnId == turnId }
        return before - list.size
    }

    override suspend fun deleteById(conversationId: String, id: String): Boolean {
        val list = rows[conversationId] ?: return false
        return list.removeAll { it.id == id }
    }

    override suspend fun lastAssistantTurnId(conversationId: String): String? =
        rows[conversationId]?.lastOrNull { it.role == Role.ASSISTANT }?.turnId

    override suspend fun replaceRange(
        conversationId: String,
        fromId: String,
        replacement: List<AgentMessage>,
    ) {
        // 会话缺失时 target 为一次性空表 → anchor=-1 → require 抛出，rows 不动
        val target = rows[conversationId] ?: mutableListOf()
        val anchor = target.indexOfFirst { it.id == fromId }
        require(anchor >= 0) {
            "replaceRange 锚点不存在: conversationId=$conversationId, fromId=$fromId"
        }
        target.subList(anchor, target.size).clear()
        target.addAll(anchor, replacement)
    }
}
