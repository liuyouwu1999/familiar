package dev.liuyouwu.familiar.skills

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 课堂剧本单条台词（说话人由 speakers 参数决定，UI 徽章显示说话人）。
 */
data class SpeakerTurn(val speaker: String, val text: String)

/**
 * ContentProtocol.parse 产物（ProtocolOutcome）。
 * - [turns]：剧本拆条；null = 无任何说话人标记（旧式单条回复，走单条路径）
 * - [finalText]：全管线清洗后的终稿全文（含说话人标记，TS 标记已剥）
 * - [isDraft]：英文废稿判定（savedCjk < 30，现 :1245 的文本条件；
 *   autoDepth==0/非续讲段等运行时条件由调用方叠加）
 * - [savedCjk]：落库语义的中文字数——有拆条时取全部台词拼接（不含说话人标记），
 *   否则取 finalText（现 :1189/:1229）
 * - [phase]：TS 状态标记提取的课堂阶段（teaching/await_user）；无有效标记为 null
 * - [stateJson]：TS 状态标记的**完整解析产物再序列化**（sections/current/phase/
 *   quiz/weak/digest 全量，现 :1140 的 gson.toJson(newState) 落库语义；P3 T6
 *   ClassroomHooks 落 tutorState 列用）；无有效标记为 null。phase 是它的投影。
 */
data class ProtocolOutcome(
    val turns: List<SpeakerTurn>?,
    val finalText: String,
    val isDraft: Boolean,
    val savedCjk: Int,
    val phase: String?,
    val stateJson: String? = null,
)

/**
 * 课堂剧本收线协议（spec §6.3 contentProtocol=classroomScript，收线后置解析）。
 *
 * 对 run 终稿做纯函数解析，管线顺序与现 ChatViewModel 课堂收线路径同源：
 * 分隔符截断（:1104）→ 英文废稿清洗（:1118）→ 锚点剥离（:1127）→ TS 状态提取
 * （:1135）→ 剧本拆条（:1164）；CoT 草稿头剥除迁自 familiar 兜底路径（:1150）。
 * 逻辑复制自 util/TutorPrompts.kt（不 import；P4 改造前双份并存，spec §11），
 * 唯一行为差异：拆条正则的说话人由参数传入（multiSpeakerScript.speakers），
 * 替代原硬编码 [老师|小柯|阿穗]。与流式思考提取分层（spec §4.2 边界）：
 * 本对象只在收线后跑一次，无状态。
 */
object ContentProtocol {

    /** 课堂正文分隔符：之前是模型推演（提示词约定整段丢弃），之后才是学生可见内容 */
    private const val SEPARATOR = "---课堂---"

    /** 开场白固定锚（提示词约定第一句「你好！我是你的×××私教。」） */
    private const val OPENING_ANCHOR = "你好！我是你的"

    /** 剧本默认第一说话人标记（锚点剥离用；拆条正则以 speakers 参数为准） */
    private const val TEACHER_TAG = "[老师]"

    const val STATE_MARKER_BEGIN = "<!--TS:"
    const val STATE_MARKER_END = "-->"

    private val gson = Gson()

    /**
     * 解析课堂终稿。纯函数：不改入参、不落库、不发 run；续讲/废稿重试等
     * 后续动作由 hooks.onRunEnd 按 [ProtocolOutcome] 判定发起。
     */
    fun parse(raw: String, speakers: List<String>): ProtocolOutcome {
        // 1) 分隔符截取。lastIndexOf：模型的自检清单常引用「---课堂---」字样，
        //    切第一个会把清单尾巴+真分隔符+正文整段留下（推演泄漏）
        var text = raw
        if (text.contains(SEPARATOR)) {
            text = text.substring(text.lastIndexOf(SEPARATOR) + SEPARATOR.length).trim()
        }
        // 2) CoT 草稿步骤头剥除（数字+分隔符+拉丁短语开头紧跟中文；全角/零宽容忍）
        text = stripCoTStepHead(text)
        // 3) 英文废稿清洗：剥代码块与开头连续英文段。返回 null = 剥完没有中文
        //    正文，保留原文（是否废稿由 savedCjk<30 判定，重试是调用方语义）
        val body = stripEnglishPreamble(text)
        if (body != null) text = body
        // 4) 锚点剥离（最后一道）：开场白与剧本锚之前的内容无论什么语言一律剥掉
        val anchors = listOf(text.indexOf(OPENING_ANCHOR), text.indexOf(TEACHER_TAG))
            .filter { it >= 0 }
        if (anchors.isNotEmpty() && anchors.min() > 0) {
            text = text.substring(anchors.min())
        }
        // 5) TS 状态提取（残缺截尾、占位值护栏），标记同时从正文移除
        val sb = StringBuilder(text)
        val state = extractState(sb)
        text = sb.toString().trim()
        // 6) 剧本拆条（说话人参数化；无任何标记返回 null，走单条路径）
        val turns = parseClassroomTurns(text, speakers)
        // saved 语义 = 落库回复文本：课堂片段取全部台词拼接（现 :1189）
        val saved = turns?.joinToString("\n") { it.text } ?: text
        val savedCjk = saved.count { it.code in 0x4E00..0x9FFF }
        return ProtocolOutcome(
            turns = turns,
            finalText = text,
            isDraft = savedCjk < 30,
            savedCjk = savedCjk,
            phase = state?.phase,
            stateJson = state?.let { gson.toJson(it) },
        )
    }

    /**
     * 草稿步骤标记头剥离：gemma-4 uncensored 系把分步推演漏进正文时，长英文头
     * 剥离（≥40 拉丁字符）够不着最后一步的短标记——"7. Generate Output.哼，鸡胸肉。"
     * 里标记只有十几个字符。本函数专剥「数字+分隔符+拉丁短语」开头、紧跟中文的
     * 标记头；兼容全角数字/句点与零宽字符前缀。不带编号分隔符的英文开头
     * （"HTTP 就是…"、纯 "404"）与无中文跟随时原样返回。
     * （迁自 util/TutorPrompts.stripCoTStepHead，逻辑不改）
     */
    private fun stripCoTStepHead(text: String): String {
        // 锚定首个 CJK：从位置 0 锚正则会输给输出里偶发的零宽字符；头部先压实再验形状
        val i = text.indexOfFirst { it.code in 0x4E00..0x9FFF }
        if (i <= 0 || i > 80) return text
        val headSolid = text.substring(0, i).filter {
            !it.isWhitespace() && it !in "*>#-" && it.code !in 0x200B..0x200F && it.code != 0xFEFF
        }
        if (!Regex("^[０-９\\d]{1,3}[.．、)）][A-Za-z]").containsMatchIn(headSolid)) return text
        return text.substring(i).trim()
    }

    /**
     * 无标签思考的启发式剥离（英文推演裸奔进正文）：按空行分段，从尾部收集连续
     * 的中文主导段，遇到英文主导段即停——思考是英文、课堂正文是中文。全文中文
     * 不足 30 字判定为废稿返回 null（调用方保留原文）。纯中文回复不受影响。
     * （迁自 util/TutorPrompts.stripEnglishPreamble，逻辑不改）
     */
    private fun stripEnglishPreamble(text: String): String? {
        // 1) 代码块整块剥：思考里的 JSON 大纲草稿常以 ```json 落地，块内含中文照删
        var t = text.replace(Regex("(?s)```.*?```"), "").trim()
        // 2) 行级剥离：跳过开头连续「英文为主」的行（思考推演），从首个中文为主行起保留；
        //    正文中间的英文行（代码示例、术语行）不受影响——只剥开头连续段
        val lines = t.lines().toMutableList()
        fun cjkOf(s: String) = s.count { it.code in 0x4E00..0x9FFF }
        fun latinOf(s: String) = s.count { it in 'a'..'z' || it in 'A'..'Z' }
        while (lines.isNotEmpty() &&
            (lines.first().isBlank() || (latinOf(lines.first()) > cjkOf(lines.first()) && cjkOf(lines.first()) < 6))
        ) lines.removeAt(0)
        t = lines.joinToString("\n").trim()
        if (t.isEmpty()) return null
        // 3) 段级兜底：思考与正文粘连无空行时，从尾部收集连续中文主导段
        val paras = t.split(Regex("\n\\s*\n"))
        if (paras.isEmpty()) return null
        var start = paras.size
        for (i in paras.indices.reversed()) {
            val p = paras[i]
            val cjk = p.count { it.code in 0x4E00..0x9FFF }
            val latin = p.count { it in 'a'..'z' || it in 'A'..'Z' }
            if (cjk >= 6 || (cjk > 0 && cjk >= latin)) start = i else break
        }
        if (start >= paras.size) return null
        val body = paras.subList(start, paras.size).joinToString("\n\n").trim()
        val cjk = body.count { it.code in 0x4E00..0x9FFF }
        return if (cjk >= 30) body else null
    }

    /**
     * 从回复正文提取全部状态标记并从正文中删除（多个标记取最后一个有效值）。
     * 解析失败/内容像占位值时返回 null；残缺或解析失败的尾部一律截除，不留碎片。
     * （迁自 util/TutorPrompts.extractState；状态类同构复制，不 import）
     */
    private fun extractState(fullResponse: StringBuilder): ClassroomState? {
        var last: ClassroomState? = null
        while (true) {
            val text = fullResponse.toString()
            val begin = text.indexOf(STATE_MARKER_BEGIN)
            if (begin < 0) break
            val end = text.indexOf(STATE_MARKER_END, begin)
            if (end < 0) {
                // 标记不完整（流中断），截掉残缺尾部
                fullResponse.setLength(begin)
                break
            }
            val json = text.substring(begin + STATE_MARKER_BEGIN.length, end).trim()
            fullResponse.delete(begin, end + STATE_MARKER_END.length)
            val parsed = try {
                gson.fromJson(json, ClassroomState::class.java)
            } catch (_: Exception) {
                null
            }?.takeIf { st ->
                // 占位值护栏：弱模型可能照抄提示词里的示例
                (st.sections == null || st.sections.none {
                    it.t == "节标题" || it.t.isBlank()
                }) && st.digest?.let { it == "一两句最近学习摘要" } != true
            }
            if (parsed != null) last = parsed
        }
        return last
    }

    /**
     * 剧本拆条：把 [说话人] 标记的剧本拆成 (说话人, 台词) 列表，每条成为独立
     * 消息。相邻同人合并、首个标记前的引导文本并入第一条；没有任何标记（旧式
     * 单条回复）返回 null，走原路径。
     * （迁自 util/TutorPrompts.parseClassroomTurns；唯一差异：硬编码
     * [老师|小柯|阿穗] 改为 speakers 参数建正则，说话人含正则元字符时转义）
     */
    private fun parseClassroomTurns(text: String, speakers: List<String>): List<SpeakerTurn>? {
        if (speakers.isEmpty()) return null
        val rx = Regex(speakers.joinToString("|", prefix = "\\[(", postfix = ")\\]") { Regex.escape(it) })
        val matches = rx.findAll(text).toList()
        if (matches.isEmpty()) return null
        val preamble = text.substring(0, matches[0].range.first).trim()
        val turns = mutableListOf<SpeakerTurn>()
        for ((i, m) in matches.withIndex()) {
            val start = m.range.last + 1
            val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
            val body = text.substring(start, end).trim()
            // 剥掉台词开头的残留思考尾（无中文的非空行，启发式只剥首行避免误伤）
            val lines = body.lines().toMutableList()
            while (lines.size > 1 && lines.first().isNotBlank() &&
                lines.first().count { it.code in 0x4E00..0x9FFF } == 0
            ) lines.removeAt(0)
            val bodyClean = lines.joinToString("\n").trim()
            if (bodyClean.isEmpty()) continue
            val speaker = m.groupValues[1]
            val prev = turns.lastOrNull()?.takeIf { it.speaker == speaker }
            if (prev != null) {
                turns[turns.lastIndex] = SpeakerTurn(speaker, prev.text + "\n\n" + bodyClean)
            } else {
                val content = if (i == 0 && preamble.isNotEmpty()) preamble + "\n\n" + bodyClean else bodyClean
                turns.add(SpeakerTurn(speaker, content))
            }
        }
        return turns.ifEmpty { null }
    }

    /** TS 状态标记结构（TutorPrompts.TutorState 同构复制；@SerializedName 锁 JSON 键名） */
    private data class ClassroomState(
        @SerializedName("v") val v: Int = 1,
        @SerializedName("sections") val sections: List<ClassroomSection>? = null,
        @SerializedName("current") val current: Int? = null,
        // 课堂阶段：teaching=节内讲解中（客户端自动续段）；await_user=等学生作答/提问
        @SerializedName("phase") val phase: String? = null,
        @SerializedName("quizRight") val quizRight: Int? = null,
        @SerializedName("quizTotal") val quizTotal: Int? = null,
        @SerializedName("weak") val weak: List<String>? = null,
        @SerializedName("digest") val digest: String? = null
    )

    private data class ClassroomSection(
        @SerializedName("t") val t: String,
        @SerializedName("goal") val goal: String? = null,
        @SerializedName("st") val st: String? = null
    )
}
