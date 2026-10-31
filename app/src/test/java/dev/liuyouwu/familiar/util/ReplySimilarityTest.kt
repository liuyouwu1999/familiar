package dev.liuyouwu.familiar.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySimilarityTest {

    @Test
    fun `nearly identical long replies are similar`() {
        val a = "哼，才不是特意为你做的呢。本鲸只是刚好路过厨房，顺手把面煮了而已，别多想。"
        val b = "哼，才不是特意为你做的呢。本鲸只是刚好路过厨房，顺手把饭焖了而已，别多想。"
        assertTrue(ReplySimilarity.similar(a, b))
    }

    @Test
    fun `different replies are not similar`() {
        val a = "哼，才不是特意为你做的呢。本鲸只是刚好路过厨房，顺手把面煮了而已，别多想。"
        val b = "深夜了还不睡？明天还要早起，先去把灯关了躺下，事情明天再想也一样。"
        assertFalse(ReplySimilarity.similar(a, b))
    }

    @Test
    fun `short texts never trigger`() {
        assertFalse(ReplySimilarity.similar("好。", "好。"))
        assertFalse(ReplySimilarity.similar("好。", "这是一段足够长长长长长长长长长长长长长长长的话但另一条很短"))
    }

    @Test
    fun `near-identical long pair sits above threshold`() {
        // 阈值方向性钉子（审查 P2-4→N4）：31 字基文本仅末二字不同——trigram 重叠远高于 0.6
        val base = "今天下午我们在会议室讨论了关于新产品的发布计划和时间安排的事情"
        val variant = "今天下午我们在会议室讨论了关于新产品的发布计划和时间安排的事宜"
        assertTrue(ReplySimilarity.similar(base, variant))
    }

    @Test
    fun `long unrelated cjk texts are not similar`() {
        // B2 台账：双方都 ≥30 CJK 字但内容无关——钉住长文 Jaccard 真实计算路径
        // （双长文不被短文本门槛短路，且无关内容重叠远低于 0.6）
        val a = "深夜的图书馆里只剩下翻书的声音，窗外的雨敲打着玻璃，她把最后一页笔记抄完，合上了厚厚的课本。"
        val b = "清晨的菜市场人声鼎沸，摊主们摆出新鲜的蔬菜和水果，讨价还价的声音此起彼伏，充满了烟火气。"
        assertFalse(ReplySimilarity.similar(a, b))
    }
}
