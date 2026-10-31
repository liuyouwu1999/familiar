package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetStateResolverTest {

    private fun r() = PetStateResolver(emitter = { mood, bubble -> states += mood to bubble })
    private val states = mutableListOf<Pair<PetHub.Mood, String?>>()

    @Test
    fun `streaming start enters THINKING then tool text change enters SEARCHING`() {
        val resolver = r()
        resolver.onSessionFrame(streaming = true, toolStatus = null, lastError = null)
        assertEquals(PetHub.Mood.THINKING, states.last().first)
        resolver.onSessionFrame(streaming = true, toolStatus = "正在搜索：x", lastError = null)
        assertEquals(PetHub.Mood.SEARCHING, states.last().first)
    }

    @Test
    fun `no regression to SEARCHING after settle (terminal snapshot retains toolStatus)`() {
        val resolver = r()
        resolver.onSessionFrame(true, "正在搜索：x", null)
        resolver.onSessionFrame(false, "正在搜索：x", null)   // 终态快照残留 toolStatus
        assertEquals(PetHub.Mood.DONE, states.last().first)  // 收束沿 DONE，不回跳
    }

    @Test
    fun `error frame beats DONE on same terminal frame`() {
        val resolver = r()
        resolver.onSessionFrame(true, null, null)
        resolver.onSessionFrame(false, null, lastError = "boom")
        assertEquals(PetHub.Mood.ERROR, states.last().first)
    }

    @Test
    fun `cancelled terminal frame settles silently`() {
        val resolver = r()
        resolver.onSessionFrame(true, null, null)
        resolver.onCancelled()
        assertEquals(PetHub.Mood.IDLE, states.last().first)
        assertEquals(null, states.last().second)
    }

    @Test
    fun `cancelled resets edge baseline (late terminal frame does not re-celebrate)`() {
        val resolver = r()
        resolver.onSessionFrame(true, null, null)
        resolver.onCancelled()
        assertEquals(PetHub.Mood.IDLE, states.last().first)
        resolver.onSessionFrame(false, null, null)   // 迟到终态帧：基线已重置，不再判收束沿（审查 #7）
        assertFalse(states.any { it.first == PetHub.Mood.DONE })
    }

    @Test
    fun `context switch re-establishes baseline in both directions`() {
        // 审查 N3/N11：重放帧按新基线正常处理——false 重放无沿（不误判收束沿），
        // true 重放恢复 THINKING（切回在跑会话方向也正确）
        val resolver = r()
        resolver.onSessionFrame(true, null, null)
        resolver.onContextSwitch()
        resolver.onSessionFrame(false, null, null)   // 空闲会话重放：无沿，不放假 DONE
        assertFalse(states.any { it.first == PetHub.Mood.DONE })
        resolver.onContextSwitch()
        resolver.onSessionFrame(true, null, null)    // 切回在跑会话：重放 true → THINKING
        assertEquals(PetHub.Mood.THINKING, states.last().first)
    }

    @Test
    fun `segment reveal pulls DONE back to SPEAKING then final segment back to DONE`() {
        val resolver = r()
        resolver.onSessionFrame(true, null, null)
        resolver.onSessionFrame(false, null, null)   // 收束沿 → DONE
        resolver.onSegmentRevealed(0, 3, "第一段")     // 回拉 SPEAKING
        assertEquals(PetHub.Mood.SPEAKING, states.last().first)
        assertTrue(states.last().second!!.contains("第一段"))
        resolver.onSegmentRevealed(2, 3, "末段")      // index==total-1 → 回 DONE
        assertEquals(PetHub.Mood.DONE, states.last().first)
    }

    @Test
    fun `background chain close on unattached conversation settles silently`() {
        val resolver = r()
        resolver.onSessionFrame(true, null, null)
        resolver.onChainSettledBackground()
        assertEquals(PetHub.Mood.IDLE, states.last().first)
        assertEquals(null, states.last().second)
    }
}
