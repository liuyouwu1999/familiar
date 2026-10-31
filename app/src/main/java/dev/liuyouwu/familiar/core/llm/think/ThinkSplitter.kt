package dev.liuyouwu.familiar.core.llm.think

/**
 * 把推理模型内联在正文里的思考段标签从正文分流出来。
 * 识别的开标签集见 [OPEN_TAGS]（think/thinkyle/thinking/reasoning/thought，大小写
 * 不敏感、配对开闭——闭标签必须与当前开标签同名，防交叉错配）；另识别 Gemma 系
 * `<|channel|>thought ... <|channel|>final` 标记对（thought 进思考态、final 出）。
 * 标签可能被 SSE 分块切断（"<thi" + "nk>"），所以用跨 chunk 的状态机；
 * 闭标签/final 之后正文开头的空白会被清理，未闭合的思考段由 flush() 归还。
 *
 * 额外处理一类不走思考标签的模型（qwen 系实测）：回复以纯文本思考开头，
 * 如 "Thinking Process: …"、"Let me think…"。首个正文增量命中 [LEAD_THINK_PATTERNS]
 * 时进入 lead-think 态（等价于隐式思考标签已开）：后续内容按 reasoning 分流，
 * 直到空行（段落边界）或超过 [LEAD_THINK_MAX_CHARS]（无边界超长视为误判，整段
 * 按正文放行——宁可漏分，不吞正文）。探测只发生在流的开头，中途出现的这些词
 * 不受影响。全部缓冲只在 pending 一个队列里，状态机与标签路径共用。
 */
class ThinkSplitter(deferred: Boolean = false) {

    data class Segment(val isReasoning: Boolean, val text: String)

    private companion object {
        /** 识别的思考开标签集（大小写不敏感；闭标签必须与开标签同名，配对开闭） */
        val OPEN_TAGS = listOf("<think>", "<thinkyle>", "<thinking>", "<reasoning>", "<thought>")

        /** Gemma 系 channel 标记：thought 进思考态、final 出（final 按闭标签同语义 trimStart） */
        const val CHANNEL_THOUGHT = "<|channel|>thought"
        const val CHANNEL_FINAL = "<|channel|>final"

        /** 非思考态下扫描的全部入口候选：5 变体开标签 + channel thought + 孤立 final */
        val ENTRY_MARKERS = OPEN_TAGS + listOf(CHANNEL_THOUGHT, CHANNEL_FINAL)

        /** 无标签模型的纯文本思考开头（前导空白剥离后、大小写不敏感前缀匹配） */
        val LEAD_THINK_PATTERNS = listOf(
            "thinking process",
            "thought process",
            "thought:",
            "thinking:",
            "internal thought",
            "let me think",
            "let's think",
            "first, let",
            "okay, let",
            "ok, let",
        )

        /**
         * 分析腔关键词：开头窗口内命中 ≥2 即判为思考（覆盖以 "1. Analyze the Request:"
         * 这类编号分析开场的模型，前缀表接不住）。取强特征词，日常中文回复几乎不会
         * 在前 300 字符里凑齐两个
         */
        val LEAD_ANALYSIS_KEYWORDS = listOf(
            "analyze the request", "analyze the user", "mandatory identity", "role:",
            "tone/style", "immersion", "constraint", "memory reference", "final polish",
            "final answer", "draft:", "step 1:", "the user's message", "my draft",
        )

        const val LEAD_SCAN_WINDOW = 300

        /** lead-think 态正文恢复边界/放弃上限：空行边界更早则提前恢复 */
        const val LEAD_THINK_MAX_CHARS = 1200
    }

    private var inThink = false

    /**
     * 标签态下当前命中的开标签（规范小写形如 "<thinkyle>"）；null 表示非标签态
     * （未开或 channel 态）。闭标签只认 "</同名>"，防 <think>…</thinking> 交叉错配
     */
    private var openTag: String? = null

    /** lead 探测三态：null=首增量未判定；true=lead-think 进行中；false=已排除 */
    private var leadThink: Boolean? = null
    private var skipLeadingWs = true
    private val pending = StringBuilder()
    private val out = mutableListOf<Segment>()

    /**
     * 延迟分类模式（familiar 等不流式正文的会话用）：lead-think 判定成立后不再
     * 流式下发任何片段，全部缓冲到流结束，在最后一个段落边界切分——思考墙取边界
     * 之前，正文取之后。原因：裸思考模型（finetune 不打 <think> 标签）的思考与
     * 回复常无空行分隔且首段边界必落在思考内部，流式首个边界规则必然切错；
     * 而这类会话全程只显示打字指示，延迟切分零体验成本。
     */
    private var deferred = deferred
    private val deferredReasoning = StringBuilder()
    private val deferredContent = StringBuilder()

    private fun emit(reasoning: Boolean, text: String) {
        var t = text
        if (!reasoning && skipLeadingWs) {
            t = t.trimStart()
            if (t.isEmpty()) return
            skipLeadingWs = false
        }
        if (t.isEmpty()) return
        if (deferred) {
            // 延迟模式：思考类一律缓冲到收尾；正文在 lead-think 未触发时照常即时下发
            // （只有裸思考墙需要延迟切分，普通回复没必要陪绑）
            if (reasoning) {
                deferredReasoning.append(t)
                return
            }
            if (leadThink == true) {
                deferredContent.append(t)
                return
            }
        }
        out.add(Segment(reasoning, t))
    }

    /** 标签匹配大小写不敏感（审计 P3：<Think>/<THINK> 变体也识别）；下发文本保持原大小写 */
    private fun indexOfTag(s: StringBuilder, tag: String): Int {
        if (s.length < tag.length) return -1
        outer@ for (i in 0..s.length - tag.length) {
            for (j in tag.indices) {
                if (s[i + j].lowercaseChar() != tag[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** s 的最长后缀同时是 tag 的前缀的长度（大小写不敏感，与 [indexOfTag] 同口径） */
    private fun tagPrefixLen(s: StringBuilder, tag: String): Int {
        val max = minOf(s.length, tag.length - 1)
        for (len in max downTo 1) {
            var match = true
            for (i in 0 until len) {
                if (s[s.length - len + i].lowercaseChar() != tag[i]) {
                    match = false
                    break
                }
            }
            if (match) return len
        }
        return 0
    }

    private fun drain(keepLen: Int) {
        val emitLen = pending.length - keepLen
        if (emitLen > 0) {
            val flushed = pending.substring(0, emitLen)
            emit(inThink, flushed)
        }
        pending.delete(0, emitLen)
    }

    /**
     * lead 探测：pending 头部（剥前导空白、小写）与模式表比对。
     * 三种出口：命中→leadThink=true（等价隐式开 think）；仍是某模式真前缀→保持
     * null 再等下一个 chunk（pending 原地悬置，不下发）；否则→false 并原样继续
     * 标签状态机。悬置上限：pending 超过模式最长长度仍无命中即排除。
     */
    private fun scanLead() {
        val head = pending.trimStart().toString().lowercase()
        if (head.isEmpty()) return
        for (p in LEAD_THINK_PATTERNS) {
            if (head.startsWith(p)) {
                leadThink = true
                inThink = true
                skipLeadingWs = false
                return
            }
        }
        // 分析腔启发式：开头窗口内命中 ≥2 个强特征词即判为思考（无需前缀对齐）
        if (head.length >= 12) {
            val window = head.take(LEAD_SCAN_WINDOW)
            val hits = LEAD_ANALYSIS_KEYWORDS.count { window.contains(it) }
            if (hits >= 2) {
                leadThink = true
                inThink = true
                skipLeadingWs = false
                return
            }
        }
        // 延迟模式下证据不足时先悬置：SSE 分块很小，第一个 chunk 往往只够装半个
        // 关键词——攒到 60 字符（或确认非模式前缀）再下结论；familiar 不流式正文，
        // 多等几个 chunk 零体验成本
        if (deferred && head.length < 60) return
        val mayBePrefix = LEAD_THINK_PATTERNS.any { it.startsWith(head) && head.length < it.length }
        if (!mayBePrefix || pending.length > LEAD_THINK_MAX_CHARS) {
            leadThink = false
        }
    }

    /** 喂入一段新文本，返回被拆出的完整片段（可能为空列表） */
    fun feed(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        out.clear()
        pending.append(text)

        // 首增量探测（未闭合 think 内不适用；leadThink!=null 后永不再进）
        if (leadThink == null) {
            scanLead()
            if (leadThink == null) {
                // 仍悬置（可能是跨 chunk 模式前缀）：本次无输出
                return emptyList()
            }
        }

        if (leadThink == true) {
            if (deferred) {
                // 延迟模式：整墙缓冲，flush 时按最后一个段落边界切分
                return emptyList()
            }
            // 隐式 think：找段落边界（空行）恢复正文；无边界且超长则放弃分流整段放行
            val boundary = pending.indexOf("\n\n")
            if (boundary < 0) {
                if (pending.length <= LEAD_THINK_MAX_CHARS) {
                    // 继续缓冲（可能边界在后续 chunk）
                    return emptyList()
                }
                // 超长仍无边界：误判保护，全部按正文放行
                emit(false, pending.toString())
                pending.setLength(0)
                leadThink = false
                inThink = false
                skipLeadingWs = false
                return ArrayList(out)
            }
            if (boundary <= LEAD_THINK_MAX_CHARS) {
                emit(true, pending.substring(0, boundary))
                pending.delete(0, boundary + 2)
                leadThink = false
                inThink = false
                skipLeadingWs = true
                // 边界后剩余部分回到标签状态机
                processPending()
            } else {
                // 边界在超远处：超长保护，整段放行
                emit(false, pending.toString())
                pending.setLength(0)
                leadThink = false
                inThink = false
                skipLeadingWs = false
            }
            return ArrayList(out)
        }

        processPending()
        return ArrayList(out)
    }

    /** 当前思考态的出口标记：标签态=同名闭标签；channel 态=final 标记 */
    private fun exitMarker(): String =
        openTag?.let { "</" + it.substring(1) } ?: CHANNEL_FINAL

    private fun processPending() {
        while (true) {
            var matchIdx = -1
            var matchTag: String? = null
            if (inThink) {
                val exit = exitMarker()
                val idx = indexOfTag(pending, exit)
                if (idx >= 0) {
                    matchIdx = idx
                    matchTag = exit
                }
            } else {
                // 非思考态：全部入口候选取最早出现者（同一下标不可能命中两个不同标记）
                for (t in ENTRY_MARKERS) {
                    val idx = indexOfTag(pending, t)
                    if (idx >= 0 && (matchTag == null || idx < matchIdx)) {
                        matchIdx = idx
                        matchTag = t
                    }
                }
            }
            if (matchTag != null) {
                if (matchIdx > 0) emit(inThink, pending.substring(0, matchIdx))
                pending.delete(0, matchIdx + matchTag.length)
                when {
                    inThink -> {
                        // 出思考态（同名闭标签或 final）：正文恢复，开头空白清理
                        inThink = false
                        openTag = null
                        skipLeadingWs = true
                    }
                    matchTag == CHANNEL_FINAL -> {
                        // 孤立 final（流直接从 final 段开始）：只吃掉标记本身，
                        // 按闭标签同语义 trimStart 后照常走正文
                        skipLeadingWs = true
                    }
                    matchTag == CHANNEL_THOUGHT -> {
                        inThink = true
                        openTag = null // channel 态的出口是 final 而非闭标签
                    }
                    else -> {
                        inThink = true
                        openTag = matchTag // 记住开标签名，闭标签必须同名
                    }
                }
                continue
            }
            // 没有完整标记：只保留可能是任一候选标记前缀的尾巴，其余立刻下发
            val candidates = if (inThink) listOf(exitMarker()) else ENTRY_MARKERS
            var keep = 0
            for (t in candidates) keep = maxOf(keep, tagPrefixLen(pending, t))
            drain(keep)
            break
        }
    }

    /** 流结束时调用，冲出还在缓冲里的内容 */
    fun flush(): List<Segment> {
        val result = mutableListOf<Segment>()
        // 延迟模式 + 裸思考：整墙在 pending 里，按最后一个段落边界切分——
        // 思考在前、正文（模型的最终回复总在末尾）在后；连边界都没有则整段按正文
        if (deferred && leadThink == true) {
            val text = pending.toString()
            pending.setLength(0)
            val cut = text.lastIndexOf("\n\n")
            if (cut >= 0) {
                val r = text.substring(0, cut).trim()
                val c = text.substring(cut + 2).trim()
                if (r.isNotEmpty()) result.add(Segment(true, r))
                if (c.isNotEmpty()) result.add(Segment(false, c))
            } else if (text.isNotBlank()) {
                result.add(Segment(false, text.trim()))
            }
            return result
        }
        if (deferred) {
            // 探测未触发（普通回复/正规标签）：悬置的 pending 补走标签状态机
            // （思考→缓冲，正文→即时），思考缓冲收尾冲出，正文殿后
            processPending()
            if (pending.isNotEmpty()) {
                val t = pending.toString()
                pending.setLength(0)
                emit(inThink, t)
            }
            if (deferredReasoning.isNotEmpty()) {
                result.add(Segment(true, deferredReasoning.toString()))
                deferredReasoning.setLength(0)
            }
            result.addAll(out)
            out.clear()
            return result
        }
        if (pending.isNotEmpty()) {
            val text = pending.toString()
            pending.setLength(0)
            // lead 悬置（流在探测完成前就结束）：按正文放行；lead-think 进行中按 reasoning
            val reasoning = inThink && leadThink != false || leadThink == true
            var t = text
            if (!reasoning && skipLeadingWs) {
                t = t.trimStart()
                if (t.isNotEmpty()) skipLeadingWs = false
            }
            if (t.isNotEmpty()) result.add(Segment(reasoning, t))
        }
        return result
    }
}
