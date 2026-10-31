package dev.liuyouwu.familiar.core.llm.client

/**
 * 审计 P2-6：故障/恶意网关可滴字下发永不换行，BufferedReader.readLine 的
 * 行缓冲无上限会静默 OOM。逐字符读并有界，超限主动断流。
 */
internal const val MAX_LINE_CHARS = 1_000_000

internal class LineTooLongException(message: String) : java.io.IOException(message)

/** 读一行（\n / \r / \r\n 皆识别）；EOF 且无内容返回 null；行超长抛 [LineTooLongException] */
internal fun readLineBounded(reader: java.io.BufferedReader): String? {
    val sb = StringBuilder()
    while (true) {
        val c = reader.read()
        if (c == -1) return if (sb.isEmpty()) null else sb.toString()
        when (c) {
            '\n'.code -> return sb.toString()
            '\r'.code -> {
                reader.mark(1)
                if (reader.read() != '\n'.code) reader.reset()
                return sb.toString()
            }
            else -> {
                if (sb.length >= MAX_LINE_CHARS) {
                    throw LineTooLongException("SSE 响应行超过 ${MAX_LINE_CHARS} 字符，已主动断流以防内存耗尽")
                }
                sb.append(c.toChar())
            }
        }
    }
}
