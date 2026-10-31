package dev.liuyouwu.familiar.pet

import kotlin.random.Random

/**
 * 洗牌袋（B1）：按随机序吐完全池前不重复，吐空自动重洗——替换 random() 直抽，
 * 根治"连抽重样"。线程约束：桌宠全部交互在主线程（PetOverlayService Handler），
 * 不加锁。
 */
class ShuffleBag(private val items: List<String>) {

    init { require(items.isNotEmpty()) { "ShuffleBag 需要非空池" } }

    private var bag = items.shuffled(Random.Default)
    private var pos = 0

    fun draw(addr: String? = null): String {
        if (pos >= bag.size) { bag = items.shuffled(Random.Default); pos = 0 }
        return bag[pos++].replace("{addr}", addr ?: "")
    }
}
