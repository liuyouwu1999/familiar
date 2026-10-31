package dev.liuyouwu.familiar.util.persona

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 拟人节奏（纯函数+注入种子，可测）：切段按句边界均衡 2-4 段；
 *  延迟=阅读(1s+30ms/字,封顶6s,可被聚合耗时抵扣)+段间隔(800-2500ms) */
object MessageSegmenter {
    private val BOUNDARY = Regex("(?<=[。！？!?…\n])")

    fun split(text: String, maxSegments: Int = 4): List<String> {
        val t = text.trim()
        if (t.length < 12) return listOf(t)
        val sentences = t.split(BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size < 2) return listOf(t)
        val target = min(maxSegments, sentences.size)
        val per = (sentences.size + target - 1) / target
        return sentences.chunked(per).map { it.joinToString("") }
    }

    fun delays(text: String, userChars: Int, seed: Long, elapsedMs: Long = 0): List<Long> {
        val rng = Random(seed)
        val reading = max(0L, min(1000L + userChars * 30L, 6000L) - elapsedMs)
        val n = split(text).size
        return listOf(reading) + List(n - 1) { 800L + rng.nextLong(1700L) }
    }
}
