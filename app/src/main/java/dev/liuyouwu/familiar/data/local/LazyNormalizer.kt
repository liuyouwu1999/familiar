package dev.liuyouwu.familiar.data.local

import dev.liuyouwu.familiar.core.llm.model.BlockCodec
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.think.ThinkSplitter
import dev.liuyouwu.familiar.data.local.entity.MessageEntity

/**
 * 存量思考前缀读时拆分（Agent 内核 P2 Task 8，spec §8.2 读时规范化）——唯一存量清洗
 * 入口：`<think>` 拆分**不在 migration 做**，repository 读取路径内联调用（IO 调度器），
 * 读出旧消息 → [needsNormalize] 判定 → [normalize] 拆块 → 调用方后台回写三列 → 幂等跳过。
 *
 * ## 判定门（needsNormalize）
 * blocks==null && role=="assistant" && content 含 `<think`（大小写不敏感）或 `<|channel|>`。
 * 三个条件缺一不可：blocks 已非空（哪怕是 `"[]"`）即回写过的行 → false（幂等）；user 行
 * 与无标记行不进拆分。门用子串匹配宁多勿漏（"<think twice>" 这类字面量也进门），误进门
 * 的行由 [normalize] 无损兜底（见下）。旧消息不补 turnId。
 *
 * ## 拆分（normalize）
 * [ThinkSplitter]（core/llm/think，P1 扩展识别集：think/thinkyle/thinking/reasoning/thought
 * 标签 + Gemma `<|channel|>thought/final` 对，大小写不敏感）全量 feed+flush 一次性拆分
 * ——非流式、非 deferred（不做段落边界切分，多段 think 整体归 reasoning）。
 * 产出 [NormalizedRow]：reasoning=思考段 `\n` 连接（无则 null）、content=正文段 `\n`
 * 连接、blocks=`BlockCodec.toJson([Thinking?, Text?])`（各至多一块，多段合并；无正文
 * 不造空 Text 块）。**不改行其他列**——调用方负责 `copy(reasoning, blocks, content)` 回写
 * （回写后 blocks 非空，下次判定为 false，整体幂等）。
 *
 * 字面量误进门（content 含 `<think` 子串但非真实标签）：拆分器识别不到标签，原文整体
 * 进 Text 块、reasoning=null——无损兜底，回写后同样幂等。
 */
object LazyNormalizer {

    /** 拆分产出：调用方以 row.copy(reasoning=…, blocks=…, content=…) 回写这三列 */
    data class NormalizedRow(val reasoning: String?, val blocks: String?, val content: String)

    fun needsNormalize(row: MessageEntity): Boolean =
        row.blocks == null && row.role == ROLE_ASSISTANT && containsThinkMarker(row.content)

    fun normalize(row: MessageEntity): NormalizedRow {
        val splitter = ThinkSplitter()
        val segments = splitter.feed(row.content) + splitter.flush()
        val reasoning = segments.filter { it.isReasoning }.joinToString("\n") { it.text }.ifEmpty { null }
        val content = segments.filter { !it.isReasoning }.joinToString("\n") { it.text }
        val blocks = buildList {
            if (reasoning != null) add(ContentBlock.Thinking(reasoning))
            if (content.isNotEmpty()) add(ContentBlock.Text(content))
        }
        return NormalizedRow(
            reasoning = reasoning,
            blocks = BlockCodec.toJson(blocks),
            content = content,
        )
    }

    // ---------------------------------------------------------------- 内部

    private const val ROLE_ASSISTANT = "assistant"

    /** 门标记：与 ThinkSplitter 的识别集同口径（大小写不敏感） */
    private fun containsThinkMarker(content: String): Boolean {
        val lower = content.lowercase()
        return lower.contains("<think") || lower.contains("<|channel|>")
    }
}
