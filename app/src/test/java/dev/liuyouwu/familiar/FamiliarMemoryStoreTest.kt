package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FamiliarMemoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `首次使用自动建文件并追加轮次`() {
        val store = FamiliarMemoryStore(tmp.root)
        store.appendTurn("我叫阿友", "记住啦", "主人", "小鱼", 1_768_800_000_000)
        val s = store.current()
        assertTrue(s.facts.contains("我叫阿友"))
        assertTrue(s.lines.single().contains("主人: 我叫阿友 → 小鱼: 记住啦"))
    }

    @Test
    fun `手改坏的文件按宽松解析继续工作`() {
        // 无段头乱码：宽松解析归互动行（不崩不丢），追加继续可用
        tmp.newFile("FamiliarMemory").writeText("{{{乱码没有段头")
        val store = FamiliarMemoryStore(tmp.root)
        store.appendTurn("你好", "好呀", "主人", "小鱼", 0)
        val s = store.current()
        assertEquals(2, s.lines.size)                       // 1 行乱码 + 1 行新互动
        assertTrue(s.lines.last().contains("主人: 你好 → 小鱼: 好呀"))
    }

    @Test
    fun `目录不可写时不崩`() {
        val store = FamiliarMemoryStore(tmp.newFolder())    // 正常目录
        store.appendTurn("a", "b", "主人", "小鱼", 0)
        store.clear()
        assertEquals(0, store.current().lines.size)
    }
}
