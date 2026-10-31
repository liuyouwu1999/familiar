package dev.liuyouwu.familiar.data.update

/** Release body → changelog 行列表：```changelog 围栏优先，无围栏降级为去 Markdown 纯文本，再降级为空 */
object ChangelogExtractor {

    fun extract(body: String?, limit: Int = 12): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        fenceBlock(body)?.let { return it.take(limit) }
        return stripMarkdown(body).take(limit)
    }

    private fun fenceBlock(body: String): List<String>? {
        val lines = body.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trimStart().startsWith("```changelog")) {
                val out = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    out.add(lines[i].trim()); i++
                }
                return out.filter { it.isNotBlank() }
            }
            i++
        }
        return null
    }

    /** 确定性降级：去掉标题符号/列表符号/链接/代码标记，跳过空行、围栏行与表格行 */
    internal fun stripMarkdown(body: String): List<String> = body.lines().mapNotNull { raw ->
        var t = raw.trim()
        if (t.isEmpty() || t.startsWith("```") || t.startsWith("|")) return@mapNotNull null
        t = t.replace(Regex("^#{1,6}\\s*"), "")
        t = t.replace(Regex("^[-*+]\\s+"), "")
        t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        t = t.replace("`", "")
        t.trim().ifEmpty { null }
    }
}
