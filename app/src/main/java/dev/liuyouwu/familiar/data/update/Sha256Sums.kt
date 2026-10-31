package dev.liuyouwu.familiar.data.update

import java.io.File
import java.security.MessageDigest

/** SHA256SUMS.txt 双格式兼容（`<64hex> *<file>` 二进制星号格式与 `<64hex>  <file>` 文本格式） */
object Sha256SumsParser {
    private val line = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")
    fun parse(text: String): Map<String, String> = text.lines().mapNotNull {
        val m = line.find(it.trim()) ?: return@mapNotNull null
        m.groupValues[2].trim() to m.groupValues[1].lowercase()
    }.toMap()
}

object Sha256 {
    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expectedHex: String): Boolean = ofFile(file) == expectedHex.lowercase()
}
