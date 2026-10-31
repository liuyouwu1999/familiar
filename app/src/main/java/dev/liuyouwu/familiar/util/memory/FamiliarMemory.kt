package dev.liuyouwu.familiar.util.memory

/** 拟人精灵分层记忆——照搬 dsh-humanized-deepseek-maid（MIT）记忆系统 v2。
 *  两层：稳定事实（用户自我披露，保守抽取去重）+ 互动记录（每轮一行）。
 *  参数为 maid 原值；parse 宽松容错：未知段头/段外行不崩不丢。 */
object FamiliarMemory {

    // 留存上限在 maid 原值（20 事实/60 行）上扩容：注入预算不变（MEMORY_CONTEXT_CHARS），
    // 扩的只是召回池——聊得久，更老的互动仍有机会被"想起的相关旧事"检索回来
    const val FACT_MAX = 30
    const val MEMORY_MAX_LINES = 200
    const val PERSONA_RECENT_LINES = 5
    const val MEMORY_CONTEXT_CHARS = 500
    const val RECALL_MAX_ITEMS = 3
    const val DEDUP_SCORE = 0.6
    const val FACT_CAP = 36
    const val MEMORY_PART_CAP = 48

    data class MemoryState(val facts: List<String>, val lines: List<String>)

    private val FACT_PATTERNS = listOf(
        Regex("""(?:我的名字(?:叫|是)|我叫|我是)[^\n。！？;；，,]{1,18}"""),
        Regex("""我(?:最喜欢|喜欢|最爱|爱|讨厌|不喜欢)[^\n。！？;；，,]{1,28}"""),
        Regex("""我(?:住在|家在|来自|在)[^\n。！？;；，,]{0,16}(?:工作|上学|生活)[^\n。！？;；，,]{0,10}"""),
        Regex("""(?:请|要|别)记得[^\n。！？;；，,]{1,28}"""),
    )

    val STOP_BIGRAMS = setOf(
        "最近", "什么", "怎么", "为什么", "今天", "明天", "昨天", "现在", "这个", "那个",
        "这些", "那些", "一下", "一个", "一次", "可以", "需要", "知道", "请问", "帮我",
        "我们", "你们", "他们", "没有", "不是", "还是", "或者", "然后", "但是", "因为",
        "所以", "如果", "虽然", "还有", "比如", "例如", "一样", "就是", "是否",
        "能否", "麻烦", "谢谢", "你好", "请帮", "看看", "这边",
    )

    fun parse(raw: String): MemoryState {
        val facts = ArrayList<String>()
        val lines = ArrayList<String>()
        var inFacts = false
        for (rawLine in raw.lines()) {
            val l = rawLine.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("# ===== 对主人") || l.startsWith("# ===== 对用户")) { inFacts = true; continue }
            if (l.startsWith("# ===== 互动")) { inFacts = false; continue }
            if (l.startsWith("#")) continue
            if (l.startsWith("- ") && inFacts) {
                val f = l.removePrefix("- ").trim()
                if (f.isNotEmpty()) facts.add(f)
            } else {
                lines.add(l)
            }
        }
        return MemoryState(facts.takeLast(FACT_MAX), lines.takeLast(MEMORY_MAX_LINES))
    }

    fun serialize(facts: List<String>, lines: List<String>): String {
        val head = "# 大肥鱼记忆文件（自动维护，仅记录真实交互，保持简洁）\n"
        val f = listOf("# ===== 对用户的了解（稳定事实）=====") + facts.takeLast(FACT_MAX).map { "- $it" }
        val l = listOf("# ===== 互动记录 =====") + lines.takeLast(MEMORY_MAX_LINES)
        return head + f.joinToString("\n") + "\n" + l.joinToString("\n") + "\n"
    }

    fun extractFacts(userText: String): List<String> = FACT_PATTERNS.mapNotNull { p ->
        p.find(userText)?.let { m -> clampText(m.value, FACT_CAP).takeIf { it.length >= 2 } }
    }

    fun bigramsOf(text: String): Set<String> {
        val t = text.filter { it.code in 0x4E00..0x9FFF || it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
        val out = HashSet<String>()
        var i = 0
        while (i < t.length - 1) { out.add(t.substring(i, i + 2)); i++ }
        if (t.length == 1) out.add(t)
        return out
    }

    fun similarity(a: String, b: String): Double {
        val A = bigramsOf(a); val B = bigramsOf(b)
        if (A.isEmpty() || B.isEmpty()) return 0.0
        var inter = 0
        for (x in A) if (B.contains(x)) inter++
        return 2.0 * inter / (A.size + B.size)
    }

    fun userSideOf(line: String): String {
        val idx = line.indexOf(" → ")
        return if (idx >= 0) line.substring(0, idx) else line
    }

    /** 普通字符串 replace（非正则）：替换值含 $ 也不会走 Matcher 语义，天然安全 */
    fun relabelLine(line: String, address: String, selfName: String): String =
        line.replace("主人:", address + ":").replace("用户:", address + ":")
            .replace("女仆:", selfName + ":").replace("精灵:", selfName + ":")

    fun clampText(text: String, max: Int): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.take(max) + "…" else t
    }

    fun queryKeywords(query: String): List<String> =
        bigramsOf(query).filterNot { it in STOP_BIGRAMS }

    /** 召回：recentCount 条最近互动之外的行，bigram 命中 ≥1 按分数取前 [RECALL_MAX_ITEMS] */
    fun recall(lines: List<String>, keywords: List<String>, recentCount: Int = PERSONA_RECENT_LINES): List<String> {
        if (keywords.isEmpty() || lines.size <= recentCount) return emptyList()
        val recentTail = lines.takeLast(recentCount).toHashSet()
        return lines.filter { it !in recentTail }
            .map { l -> val lbs = bigramsOf(l); var hit = 0; for (k in keywords) if (lbs.contains(k)) hit++; l to hit }
            .filter { it.second >= 1 }
            .sortedByDescending { it.second }
            .take(RECALL_MAX_ITEMS)
            .map { it.first }
    }

    /** 组装记忆上下文（≤[MEMORY_CONTEXT_CHARS]，块级预算整块丢弃，顺序：事实→最近互动→相关旧事）*/
    fun buildContext(facts: List<String>, lines: List<String>, address: String, selfName: String, query: String): String? {
        if (facts.isEmpty() && lines.isEmpty()) return null
        val keywords = queryKeywords(query)
        val parts = ArrayList<String>()
        var budget = MEMORY_CONTEXT_CHARS
        fun push(label: String, body: String) {
            if (body.isEmpty()) return
            val block = "$label\n$body"
            if (block.length > budget) return
            parts.add(block)
            budget -= block.length
        }
        if (facts.isNotEmpty()) {
            // 事实太多放不下时丢最旧的挤进预算，而不是整块放弃（整块丢弃 = 一次都不记得，最伤）
            var fs = facts.takeLast(FACT_MAX)
            val label = "对${address}的了解："
            fun body() = fs.joinToString("\n") { "- $it" }
            while (label.length + body().length > budget && fs.size > 1) fs = fs.drop(1)
            push(label, body())
        }
        if (lines.isNotEmpty()) {
            push("最近互动：", lines.takeLast(PERSONA_RECENT_LINES).joinToString("\n") { relabelLine(userSideOf(it), address, selfName) })
        }
        if (keywords.isNotEmpty()) {
            val hits = recall(lines, keywords)
            if (hits.isNotEmpty()) {
                push("想起的相关旧事：", hits.mapIndexed { i, l -> "${i + 1}. ${relabelLine(userSideOf(l), address, selfName)}" }.joinToString("\n"))
            }
        }
        if (parts.isEmpty()) return null
        return "（记忆参考——只可使用下列已发生的事实，严禁编造记忆：\n${parts.joinToString("\n\n")}）"
    }
}
