package dev.liuyouwu.familiar.agent

import dev.liuyouwu.familiar.core.llm.model.AgentMessage

/**
 * 回合边界注入队列（P-steer，spec §3.1）：AgentSession.steer 入队，AgentLoop
 * 每回合顶排空。run 级生命周期——executeRun 造一条，run 收尾 close（close 后
 * offer 静默丢弃，isOpen 复检是 steer TOCTOU 契约的第④步）。
 * 线程约束：offer/peek 任意线程（synchronized），drain 在 loop 协程。
 */
class SteeringQueue {
    private val lock = Any()
    private val items = ArrayDeque<AgentMessage>()
    private var closed = false

    fun offer(msg: AgentMessage) = synchronized(lock) { if (!closed) items.addLast(msg) }
    fun drain(): List<AgentMessage> = synchronized(lock) { val out = items.toList(); items.clear(); out }
    fun peek(): Boolean = synchronized(lock) { items.isNotEmpty() }
    fun isOpen(): Boolean = synchronized(lock) { !closed }
    fun close() = synchronized(lock) { closed = true }
}
