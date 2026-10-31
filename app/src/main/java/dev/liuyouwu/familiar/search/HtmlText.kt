package dev.liuyouwu.familiar.search

/**
 * 搜索结果页共用的"剥标签 + HTML 实体解码"小工具（必应/搜狗/360/维基同一套，
 * 避免三处复制产生口径漂移）。手写而非引 jsoup：只为清洗摘要不值得多拉一个依赖。
 */
object HtmlText {

    /** 命名实体 + 十进制(&#NNN;)/十六进制(&#xNN;)数字实体；单遍 replace 防 &amp;lt; 被二次解码成 <。 */
    private val ENTITY = Regex("&(amp|lt|gt|quot|nbsp|#(\\d+)|#[xX]([0-9a-fA-F]+));")

    /** 摘要/标题的最终清洗：剥标签（含 <!--注释-->，也顺带吃掉搜狗的 red_beg 标记注释）再解码再收边。 */
    fun stripTags(s: String?): String =
        decodeEntities((s ?: "").replace(Regex("<[^>]*>"), "")).trim()

    /** 未知/越界实体（如 &#x110000;）原样保留：解码失败不该让整条解析报废。 */
    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        runCatching {
            when (m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                // 摘要场景 nbsp 折算普通空格：U+00A0 不是 Char.isWhitespace，留着会让 isBlank 误判
                "nbsp" -> " "
                else -> String(
                    Character.toChars(
                        if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toInt()
                        else m.groupValues[3].toInt(16)
                    )
                )
            }
        }.getOrDefault(m.value)
    }
}
