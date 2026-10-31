package dev.liuyouwu.familiar.viewmodel

import android.content.Context
import dev.liuyouwu.familiar.core.llm.probe.CapabilityReader
import dev.liuyouwu.familiar.core.llm.probe.CapabilitySnapshot
import dev.liuyouwu.familiar.core.llm.probe.ModelCatalog
import dev.liuyouwu.familiar.core.llm.probe.probeCacheKey
import dev.liuyouwu.familiar.data.repository.DataStoreCapabilityCache
import dev.liuyouwu.familiar.data.repository.dataStore

/**
 * 能力探测的进程级装配（P5 T2）：目录 + DataStore 缓存 + 纯读器三件的**单例**
 * 持有位。单例的原因：DataStoreCapabilityCache 的内存镜像必须全局共享——
 * 连接页预热写入的值，SkillHost 的 modeResolver（聊天页）要能即时读到。
 * 两个消费方（ChatViewModel / ConnectionViewModel）经此取同一套件。
 *
 * - **目录**：assets/model_capabilities.json 一次性加载（ModelCatalog.fromJson，
 *   损坏 fail-fast——随包静态内容属构建期缺陷，见 P1 KDoc）；首触读 assets，
 *   调用方装配段应在 IO。
 * - **缓存**：DataStoreCapabilityCache（settings 同一 DataStore 文件，
 *   `toolcap:` 键前缀隔离；peek = 非挂起镜像读）。
 * - **纯读器**：CapabilityReader——覆盖 > 缓存快照 > 目录先验 > UNKNOWN 折叠。
 *   全链无网络路径（评审 P1-4 全局约束的接线形态：网络探测只发生在连接页
 *   后台预热，本对象没有任何传输引用）。
 */
internal object AgentCapability {

    private const val CATALOG_ASSET = "model_capabilities.json"

    @Volatile private var cache: DataStoreCapabilityCache? = null
    @Volatile private var reader: CapabilityReader? = null
    @Volatile private var catalog: ModelCatalog? = null

    /** 进程单例缓存（镜像跨页面共享的前提） */
    fun cache(context: Context): DataStoreCapabilityCache =
        cache ?: synchronized(this) {
            cache ?: DataStoreCapabilityCache(context.applicationContext.dataStore).also { cache = it }
        }

    /** 目录（连接页预热构造 CapabilityProber 用；与 reader 内部同实例） */
    fun catalog(context: Context): ModelCatalog =
        catalog ?: synchronized(this) {
            catalog ?: loadCatalog(context).also { catalog = it }
        }

    /** 纯读器（SkillHost modeResolver 与连接页徽章共用） */
    fun reader(context: Context): CapabilityReader =
        reader ?: synchronized(this) {
            reader ?: CapabilityReader(catalog(context), snapshotOf(cache(context))).also { reader = it }
        }

    /** 快照缝：镜像同步读 + probeCacheKey 单点取键（与 prober 写入同键） */
    private fun snapshotOf(cache: DataStoreCapabilityCache) = CapabilitySnapshot { p, b, m ->
        cache.peek(probeCacheKey(p, b, m))
    }

    private fun loadCatalog(context: Context): ModelCatalog =
        ModelCatalog.fromJson(
            context.applicationContext.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        )
}
