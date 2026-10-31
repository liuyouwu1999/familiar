package dev.liuyouwu.familiar.agent

import dev.liuyouwu.familiar.core.llm.model.AgentMessage

/**
 * 会话消息存储端口（Task 14 AgentSession 的存储侧原语；行语义见 spec §5.4/§8.1）。
 * 按会话（conversationId）隔离，行即 [AgentMessage]——id/turnId 由调用方铸造
 * （AgentLoop 的 produced 已带 "$runId:$turnIndex" turnId；user 侧 turnId 铸造
 * 推迟至 P2/P3 行映射任务，本阶段用户行 turnId 为 null）。
 *
 * 方法语义（RunMarkerTextsSnapshotTest 钉死——内存实现 CRUD 用例集随内核
 * 清理 A 自旧标记用例集改造更名）：
 * - [append]：按会话尾插，不查重不校验 id 唯一（调用方负责，AgentSession 串行驱动）；
 *   返回与消息**一一对齐**的行 id（P3 T2，行级 post 钩子 onPersisted 的前提）——
 *   内存实现为进程内单调合成号（跨会话不重置，仅用于钩子对位、非稳定键）；
 *   Room 实现返回该消息扇出行的**首行** id（多图 user N 行同组，读侧合并组
 *   id 即首行，[RoomMessageStore] KDoc）；assistant/TOOL_RESULT 行不扇出，
 *   钩子路径上恒 1 消息 = 1 行 = 1 id；
 * - [list]：插入序快照；会话内跨角色保序（USER/ASSISTANT/TOOL_RESULT 交错原样）；
 * - [deleteTurn]：删同 turnId 的**全部**行（assistant/TOOL_RESULT/拆条行，§5.4
 *   regenerate 删整组），返回删除行数；未知 turnId 返回 0、不动存量；
 * - [deleteById]：删单行（turnId=NULL 旧数据的降级路径），是否命中；
 * - [lastAssistantTurnId]：时间序最后一条 assistant 行的 turnId——regenerate 的
 *   分组判据；末条 assistant 为 NULL（旧数据）或无 assistant 时返回 null，调用方
 *   降级为 deleteById 删最后一行 assistant（§5.4）；末尾的 TOOL_RESULT 行
 *   （取消补落形态）不遮蔽该判据；
 * - [replaceRange]：从锚点行（含）到末尾整段替换为 replacement（editAndResend
 *   截断重发、compact 压缩为单条摘要），单步生效；锚点或会话不存在抛
 *   IllegalArgumentException（fail-fast，存量不动）。
 *
 * 方法全部 suspend：为 P3 的 Room 实现留位（IO），P1 内存实现即纯内存操作。
 *
 * 实现归属（P2 终审 #32）：生产唯一实现是 [dev.liuyouwu.familiar.data.local.RoomMessageStore]；
 * 进程内实现 InMemoryMessageStore 无生产消费者（全链装配在 P3 切 Room 完成），
 * 已迁 test 源集（app/src/test/.../agent/InMemoryMessageStore.kt，同包）——它自始
 * 就是测试记账假件，留在 main 只会暗示存在第二条生产装配路径。
 */
interface MessageStore {
    suspend fun append(conversationId: String, message: AgentMessage): List<Long>

    suspend fun list(conversationId: String): List<AgentMessage>

    suspend fun deleteTurn(conversationId: String, turnId: String): Int

    suspend fun deleteById(conversationId: String, id: String): Boolean

    suspend fun lastAssistantTurnId(conversationId: String): String?

    suspend fun replaceRange(conversationId: String, fromId: String, replacement: List<AgentMessage>)
}
