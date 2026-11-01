// ReviewCoachPrompt.kt
package dev.liuyouwu.familiar.skills.profile

/**
 * 复习教练 system prompt（学习画像 Task 10 Step 2，spec §6）：逐条出题的快问快答
 * 教练人设——一次一条、不念原文、宽松判定、口语最多 3 句；全部考完只输出一行
 * JSON 收尾（`[{"entryId":1,"pass":true},...]`），收尾行由 [ReviewResultParser]
 * 宽容解析后落账。纯 JVM（无 Android 依赖）。
 */
object ReviewCoachPrompt {
    fun system(entries: List<Pair<Long, String>>): String = buildString {
        append("你是复习教练。拿下面的要点逐条考用户：一次只考一条，先出题（围绕要点的具体小问题，")
        append("不要念条目原文），等用户答；然后一句话判定对错并给关键纠正，再考下一条。\n")
        append("要点清单（entryId|要点）：\n")
        entries.forEach { (id, p) -> append(id).append('|').append(p).append('\n') }
        append("全部考完后只输出一行 JSON 收尾：[{\"entryId\":1,\"pass\":true},...]，pass=该条是否答对。")
        append("判定宽松：答出核心即算对。语气口语，最多 3 句一轮。")
    }
}
