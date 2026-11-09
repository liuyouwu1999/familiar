package dev.liuyouwu.familiar.data.local

import dev.liuyouwu.familiar.core.llm.model.BlockCodec
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 8 测试矩阵（spec §8.2 读时规范化）：
 * 判定门：blocks==null && role=="assistant" && content 含 `<think`（大小写不敏感）或
 * `<|channel|>`；blocks 已非空（幂等）/ user 行 / 无标记行 → false。
 * 拆分：ThinkSplitter 全量 feed+flush（非流式一次性，P1 扩展识别集全走查）；
 * 未闭合 → 全 thinking 正文空；多段（多段落）think 整体归 reasoning 不做边界切分；
 * 多个 think 块合并为单个 Thinking/Text 块（blocks 形状恒 [Thinking?, Text?]）。
 *
 * 纯函数测试：不起 Room/Robolectric。
 */
class LazyNormalizerTest {

    private fun legacyAssistant(content: String, blocks: String? = null) = MessageEntity(
        id = 1L,
        conversationId = 7L,
        role = "assistant",
        content = content,
        blocks = blocks,
    )

    // ---------------------------------------------------------------- needsNormalize 判定门

    @Test
    fun `gate - assistant legacy row with think marker needs normalize`() {
        assertTrue(LazyNormalizer.needsNormalize(legacyAssistant("<think>推演</think>正文")))
    }

    @Test
    fun `gate - think marker matching is case-insensitive`() {
        assertTrue(LazyNormalizer.needsNormalize(legacyAssistant("<Think>推演</Think>正文")))
        assertTrue(LazyNormalizer.needsNormalize(legacyAssistant("<THINK>x")))
    }

    @Test
    fun `gate - channel marker fires with or without think tags`() {
        assertTrue(LazyNormalizer.needsNormalize(legacyAssistant("<|channel|>thought思考<|channel|>final正文")))
        assertTrue(LazyNormalizer.needsNormalize(legacyAssistant("正文里提到 <|channel|> 标记")))
    }

    @Test
    fun `gate - blocks already set skips - idempotent`() {
        // 回写后的行 blocks 非空（哪怕是空数组），再次读取不得重拆
        assertFalse(LazyNormalizer.needsNormalize(legacyAssistant("<think>推演</think>正文", blocks = "[]")))
        assertFalse(
            LazyNormalizer.needsNormalize(
                legacyAssistant("正文", blocks = BlockCodec.toJson(listOf(ContentBlock.Text("正文"))))
            )
        )
    }

    @Test
    fun `gate - user row with markers is skipped`() {
        val userRow = legacyAssistant("<think>推演</think>正文").copy(role = "user")
        assertFalse(LazyNormalizer.needsNormalize(userRow))
    }

    @Test
    fun `gate - assistant row without markers is skipped`() {
        assertFalse(LazyNormalizer.needsNormalize(legacyAssistant("普通回复，无任何标记")))
        assertFalse(LazyNormalizer.needsNormalize(legacyAssistant("")))
    }

    // ---------------------------------------------------------------- normalize 拆分

    @Test
    fun `think prefix splits into reasoning and body`() {
        val out = LazyNormalizer.normalize(legacyAssistant("<think>推演</think>正文"))
        assertEquals("推演", out.reasoning)
        assertEquals("正文", out.content)
        // blocks 形状恒 [Thinking?, Text?]，可直接 BlockCodec 解码回块
        val blocks = BlockCodec.fromJson(out.blocks!!)
        assertEquals(listOf(ContentBlock.Thinking("推演"), ContentBlock.Text("正文")), blocks)
    }

    @Test
    fun `P1 extended tag recognition set walks through all variants`() {
        // P1 扩展集：think/thinkyle/thinking/reasoning/thought（闭标签须同名）+ 大小写不敏感
        for (tag in listOf("think", "thinkyle", "thinking", "reasoning", "thought")) {
            val out = LazyNormalizer.normalize(legacyAssistant("<$tag>推演</$tag>正文"))
            assertEquals("推演", out.reasoning)
            assertEquals("正文", out.content)
        }
        val mixed = LazyNormalizer.normalize(legacyAssistant("<Reasoning>推演</REASONING>正文"))
        assertEquals("推演", mixed.reasoning)
        assertEquals("正文", mixed.content)
    }

    @Test
    fun `gemma channel pair splits thought into reasoning and final into body`() {
        val out = LazyNormalizer.normalize(legacyAssistant("<|channel|>thought思考内容<|channel|>final正文"))
        assertEquals("思考内容", out.reasoning)
        assertEquals("正文", out.content)
    }

    @Test
    fun `unclosed think yields all reasoning and empty body`() {
        val out = LazyNormalizer.normalize(legacyAssistant("<think>整段都是思考没闭合"))
        assertEquals("整段都是思考没闭合", out.reasoning)
        assertEquals("", out.content)
        // 无正文 → blocks 只含 Thinking，不造空 Text 块
        assertEquals(listOf(ContentBlock.Thinking("整段都是思考没闭合")), BlockCodec.fromJson(out.blocks!!))
    }

    @Test
    fun `multi-paragraph think block is taken whole as reasoning`() {
        // 多段（多段落）think 整体归 reasoning，不做段落边界切分（ThinkSplitter 标签语义，
        // 非 deferred 边界切分）
        val out = LazyNormalizer.normalize(legacyAssistant("<think>第一段推演\n\n第二段推演</think>正文"))
        assertEquals("第一段推演\n\n第二段推演", out.reasoning)
        assertEquals("正文", out.content)
    }

    @Test
    fun `multiple think blocks merge into single thinking and text blocks`() {
        // blocks 形状恒 [Thinking?, Text?]：多个 think 段合并为单个 Thinking（\n 连接）、
        // 多个正文段合并为单个 Text，无内容丢失
        val out = LazyNormalizer.normalize(legacyAssistant("<think>一</think>中间<think>二</think>尾"))
        assertEquals("一\n二", out.reasoning)
        assertEquals("中间\n尾", out.content)
        assertEquals(
            listOf(ContentBlock.Thinking("一\n二"), ContentBlock.Text("中间\n尾")),
            BlockCodec.fromJson(out.blocks!!)
        )
    }

    @Test
    fun `marker-looking literal without real tag degrades to plain text`() {
        // 门以子串 `<think` 判定（宁多勿漏），但内容并非真实标签：拆分不误伤，
        // 原文整体进 Text、reasoning 为 null；回写后 blocks 非空同样幂等
        val content = "我说的是 <think twice> 不是标签"
        val row = legacyAssistant(content)
        assertTrue(LazyNormalizer.needsNormalize(row))
        val out = LazyNormalizer.normalize(row)
        assertNull(out.reasoning)
        assertEquals(content, out.content)
        assertEquals(listOf(ContentBlock.Text(content)), BlockCodec.fromJson(out.blocks!!))
    }

    @Test
    fun `normalized row feeds idempotent write-back`() {
        // 调用方回写模拟：copy 回 reasoning/blocks/content 三列后，再次判定必须为 false
        val row = legacyAssistant("<think>推演</think>正文")
        val out = LazyNormalizer.normalize(row)
        val written = row.copy(reasoning = out.reasoning, blocks = out.blocks, content = out.content)
        assertFalse(LazyNormalizer.needsNormalize(written))
    }

    // -------------------------------- 轻量投影行重载（审计 P1-4 聊天读路径）：
    // 判定/拆分与 Entity 版本同语义（blocks/role/content 三列）

    private fun legacyLightRow(content: String, blocks: String? = null) =
        dev.liuyouwu.familiar.data.local.dao.MessageLightRow(
            id = 1L, conversationId = 7L, role = "assistant", content = content, blocks = blocks, timestamp = 1L,
        )

    @Test
    fun `light row overload - gate and split match entity semantics`() {
        val row = legacyLightRow("<think>推演</think>正文")
        assertTrue(LazyNormalizer.needsNormalize(row))
        val out = LazyNormalizer.normalize(row)
        assertEquals("推演", out.reasoning)
        assertEquals("正文", out.content)
        assertEquals(
            listOf(ContentBlock.Thinking("推演"), ContentBlock.Text("正文")),
            BlockCodec.fromJson(out.blocks!!)
        )
        // 回写模拟：blocks 非空后幂等跳过
        assertFalse(LazyNormalizer.needsNormalize(row.copy(blocks = out.blocks)))
        assertFalse(LazyNormalizer.needsNormalize(legacyLightRow("<think>推演</think>正文", blocks = "[]")))
        assertFalse(LazyNormalizer.needsNormalize(legacyLightRow("普通回复")))
        assertFalse(LazyNormalizer.needsNormalize(legacyLightRow("<think>x</think>").copy(role = "user")))
    }
}
