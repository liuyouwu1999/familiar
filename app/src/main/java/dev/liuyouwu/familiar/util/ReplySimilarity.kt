package dev.liuyouwu.familiar.util

/**
 * familiar 回复相似度（B2-L4，spec §6.1）：双方取 CJK 字符子序列构字符 trigram 集，
 * Jaccard ≥ 0.6 判相似；任一方 CJK 子序列 < 30 字恒 false（短文本 Jaccard 不稳定，
 * 宁可不触发）。纯函数，无状态。
 */
object ReplySimilarity {

    private const val THRESHOLD = 0.6
    private const val MIN_CJK = 30

    fun similar(a: String, b: String): Boolean {
        val ga = trigrams(a) ?: return false
        val gb = trigrams(b) ?: return false
        val inter = ga.count { it in gb }
        val union = ga.size + gb.size - inter
        return union > 0 && inter.toDouble() / union >= THRESHOLD
    }

    /** CJK 子序列的 trigram 集；子序列不足 MIN_CJK 返回 null（不可判定） */
    private fun trigrams(text: String): Set<String>? {
        val cjk = text.filter { it.code in 0x4E00..0x9FFF }
        if (cjk.length < MIN_CJK) return null
        return (0..cjk.length - 3).mapTo(HashSet()) { cjk.substring(it, it + 3) }
    }
}
