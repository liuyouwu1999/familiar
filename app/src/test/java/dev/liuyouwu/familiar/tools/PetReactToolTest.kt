package dev.liuyouwu.familiar.tools

import com.google.gson.JsonObject
import dev.liuyouwu.familiar.pet.PetHub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pet_react 工具（P6 任务4b）：参数校验 + 动作路由 + 文本规整。perform 走注入缝
 * （断言收到的 mood/line），不触 PetHub object——JVM 未 mock 环境的 Looper 规避，仓内惯例。
 */
class PetReactToolTest {

    private fun args(vararg pairs: Pair<String, String>): JsonObject {
        val o = JsonObject()
        pairs.forEach { (k, v) -> o.addProperty(k, v) }
        return o
    }

    @Test fun `缺action抛参数异常`() = runBlocking {
        val tool = PetReactTool(perform = { _, _ -> })
        try {
            tool.execute(JsonObject()) { }
            error("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("action"))
        }
    }

    @Test fun `未知动作抛参数异常`() = runBlocking {
        val tool = PetReactTool(perform = { _, _ -> })
        try {
            tool.execute(args("action" to "fly")) { }
            error("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("未知动作"))
        }
    }

    @Test fun `合法动作路由到注入缝`() = runBlocking {
        val seen = mutableListOf<Pair<PetHub.Mood, String?>>()
        val tool = PetReactTool(perform = { m, l -> seen += m to l })
        val out = tool.execute(args("action" to "dance", "text" to "看我的")) { }
        assertEquals(listOf(PetHub.Mood.DANCE to "看我的"), seen)
        assertTrue(out.contains("dance"))
    }

    @Test fun `空白text规整为null`() = runBlocking {
        val seen = mutableListOf<Pair<PetHub.Mood, String?>>()
        val tool = PetReactTool(perform = { m, l -> seen += m to l })
        tool.execute(args("action" to "token", "text" to "   ")) { }
        assertEquals(listOf(PetHub.Mood.TOKEN to null), seen)
    }

    @Test fun `九动作全覆盖且不重名`() {
        assertEquals(9, PetReactTool.MOODS.size)
        assertEquals(9, PetReactTool.MOODS.values.toSet().size)
    }
}
