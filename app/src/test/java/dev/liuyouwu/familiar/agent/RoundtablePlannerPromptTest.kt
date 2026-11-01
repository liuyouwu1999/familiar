package dev.liuyouwu.familiar.agent

import org.junit.Test

/**
 * Task 6（planner 改造）钉死 [RoundtablePlannerPrompt] 契约：
 * - system：轮尾问句规则 + 空数组交还 + 正反例 + 上限条数插值 + 材料段拼尾部；
 * - scheduleNote：≤N 句按角色分派（讲师3/其余2）、无"自然融入"、无 point 时问句兜底
 *   （ROUND_ROBIN 降级路径）。
 * chatroom 的现场文案不抽常量、不在本测试范围（行为回归红线：逐字保留现场原文）。
 */
class RoundtablePlannerPromptTest {
    @Test fun `system含轮尾问句规则与正反例与上限`() {
        val s = RoundtablePlannerPrompt.system(3, null)
        assert(s.contains("最后一条必须是某个角色向用户提出的一个具体问题"))
        assert(s.contains("空数组") && s.contains("上限 3 条") && s.contains("❌") && s.contains("✅"))
    }

    @Test fun `材料段拼在system尾部`() {
        val s = RoundtablePlannerPrompt.system(3, "牛顿第三定律材料")
        assert(s.indexOf("只输出一行 JSON") < s.indexOf("圆桌学习材料"))
    }

    @Test fun `scheduleNote按角色分派句数并删掉自然融入`() {
        assert(RoundtablePlannerPrompt.scheduleNote("讲师", "p").contains("≤3 句"))
        assert(RoundtablePlannerPrompt.scheduleNote("新手", "p").contains("≤2 句"))
        assert(!RoundtablePlannerPrompt.scheduleNote("讲师", "p").contains("自然融入"))
        val bare = RoundtablePlannerPrompt.scheduleNote("怀疑者", null)
        assert(bare.contains("最后一句必须向用户提一个具体问题"))
    }
}
