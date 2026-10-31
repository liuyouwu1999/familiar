package dev.liuyouwu.familiar.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.liuyouwu.familiar.core.llm.probe.CapabilityCache
import dev.liuyouwu.familiar.core.llm.probe.ToolCapability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 能力缓存 DataStore 持久化实现（P5 T2，spec 评审 C5：探测结果缓存 DataStore，
 * 键 `toolcap:{providerId}:{normalize(baseUrl)}:{model}`——由 [probeCacheKey]
 * 单点铸造）。挂 settings 同一 DataStore 文件（键前缀 `toolcap:` 隔离命名空间）。
 *
 * 读写走**内存镜像**（ConcurrentHashMap）：modeResolver 与连接页徽章要求
 * 非挂起纯读（评审 P1-4），[peek] 直读镜像——首次挂起访问（get/put）时从
 * DataStore 一次性水合，此后写穿透（镜像即时 + DataStore 异步落盘）。
 *
 * 故障面：DataStore 读失败 → 镜像空表继续（进程内正确，重启重探）；写失败 →
 * 镜像仍生效（进程内正确，重启后缺失即重探）——缓存是优化不是正确性来源，
 * 不把磁盘故障升级为功能故障。手动清除重探（spec C5）经 [clear]。
 */
class DataStoreCapabilityCache(private val dataStore: DataStore<Preferences>) : CapabilityCache {

    private val mirror = ConcurrentHashMap<String, ToolCapability>()
    private val loaded = AtomicBoolean(false)
    private val hydration = Mutex()

    override suspend fun get(key: String): ToolCapability? {
        ensureLoaded()
        return mirror[key]
    }

    override suspend fun put(key: String, v: ToolCapability) {
        ensureLoaded()
        mirror[key] = v
        runCatching {
            dataStore.edit { it[stringPreferencesKey(key)] = v.name }
        }   // 落盘失败不升级：镜像已生效（类 KDoc 故障面）
    }

    /** 同步镜像读（modeResolver/徽章的纯读通道）：未水合完成前返回 null → 走目录先验 */
    fun peek(key: String): ToolCapability? = mirror[key]

    /** 手动清除重探（spec 评审 C5）：镜像与落盘同清 */
    suspend fun clear() {
        ensureLoaded()
        val keys = mirror.keys.toList()
        mirror.clear()
        runCatching {
            dataStore.edit { prefs -> keys.forEach { prefs.remove(stringPreferencesKey(it)) } }
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded.get()) return
        hydration.withLock {
            if (loaded.get()) return
            runCatching {
                dataStore.data.first().asMap().forEach { (key, value) ->
                    if (key.name.startsWith(KEY_PREFIX)) {
                        runCatching { mirror[key.name] = ToolCapability.valueOf(value as String) }
                    }
                }
            }
            loaded.set(true)
        }
    }

    private companion object {
        /** 与 probeCacheKey 产出键同前缀（DataStore 命名空间隔离位） */
        const val KEY_PREFIX = "toolcap:"
    }
}
