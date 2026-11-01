package dev.liuyouwu.familiar.skills.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoundtableSummaryLogicTest {
    @Test fun `合法全量解析`() {
        val r = RoundtableSummaryParser.parse(
            """{"summary":"搞清了对照实验","understood":["对照组作用"],"stuck":["样本量"],
               "updates":[{"existingId":3,"point":"样本量要够大","status":"STUCK"}],
               "creates":[{"point":"随机分组","status":"UNDERSTOOD"}]}"""
        )!!
        assertEquals("搞清了对照实验", r.summary)
        assertEquals(listOf("对照组作用"), r.understood)
        assertEquals(1, r.updates.size); assertEquals(3L, r.updates[0].existingId)
        assertEquals("STUCK", r.updates[0].status)
        assertEquals("随机分组", r.creates[0].point)
    }

    @Test fun `markdown围栏与前后噪声可提取`() {
        val r = RoundtableSummaryParser.parse("好的，如下：\n```json\n{\"summary\":\"s\",\"understood\":[],\"stuck\":[],\"updates\":[],\"creates\":[]}\n```\n以上")!!
        assertEquals("s", r.summary)
    }

    @Test fun `缺summary或非JSON返回null`() {
        assertNull(RoundtableSummaryParser.parse("我谈谈看法"))
        assertNull(RoundtableSummaryParser.parse("""{"understood":[]}"""))
    }

    @Test fun `坏条目丢弃好条目保留`() {
        val r = RoundtableSummaryParser.parse(
            """{"summary":"s","understood":[],"stuck":[],
               "updates":[{"existingId":0,"point":"无id丢弃","status":"STUCK"},
                          {"existingId":5,"point":"坏status丢弃","status":"OK"},
                          {"existingId":6,"point":"合法","status":"understood"}],
               "creates":[{"point":"","status":"UNDERSTOOK"}]}"""
        )!!
        assertEquals(1, r.updates.size); assertEquals("UNDERSTOOD", r.updates[0].status)
        assertEquals(0, r.creates.size)   // 空 point 丢弃
    }

    @Test fun `节流判定 八条起跑`() {
        assert(RoundtableSummaryParser.shouldAutoSummarize(8))
        assert(!RoundtableSummaryParser.shouldAutoSummarize(7))
    }

    @Test fun `提取转DAO指令 updates丢不存在id creates全插入 纯函数可重复`() {
        val ex = SummaryExtraction(
            summary = "s",
            updates = listOf(ExistingUpdate(6, "更新", "UNDERSTOOD"), ExistingUpdate(99, "不存在", "STUCK")),
            creates = listOf(EntryCreate("新点", "STUCK")),
        )
        val existing = listOf(entry().copy(id = 6))
        val ins = RoundtableSummaryParser.toDaoInstructions(ex, existing)
        assertEquals(listOf(Instruction.Update(6, "更新", "UNDERSTOOD"), Instruction.Insert("新点", "STUCK")), ins)
        assertEquals(ins, RoundtableSummaryParser.toDaoInstructions(ex, existing))   // 纯函数可重复
    }

    private fun entry() = dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity(
        id = 6, topic = "t", point = "p", status = "UNDERSTOOD", sourceRoomId = 1,
        createdAt = 0, lastReviewedAt = 0, nextReviewAt = 0,
    )
}
