package dev.liuyouwu.familiar.util.memory

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** FamiliarMemory 的文件层：ensure 重建 / 读失败回退空 / 写失败不阻塞（maid 容错链）。
 *  原子写：tmp + rename。跨会话共享一份（app 单例）。
 *  log 可注入（默认无操作）：纯 JVM 单测不依赖 android.util.Log，Android 侧注入 Log.w。 */
class FamiliarMemoryStore(
    private val dir: File,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {

    private val file = File(dir, "FamiliarMemory")
    private val lock = Any()

    init { ensure() }

    fun current(): FamiliarMemory.MemoryState = synchronized(lock) {
        try { FamiliarMemory.parse(file.readText()) }
        catch (e: Exception) { log("读取失败回退空记忆", e); FamiliarMemory.MemoryState(emptyList(), emptyList()) }
    }

    /** 落一轮互动 + 抽取稳定事实（Dice≥阈值去重）；写失败静默不阻塞调用方 */
    fun appendTurn(userText: String, assistantText: String, address: String, selfName: String, now: Long) {
        synchronized(lock) {
            try {
                val state = current()
                val facts = state.facts.toMutableList()
                for (c in FamiliarMemory.extractFacts(userText)) {
                    if (facts.none { FamiliarMemory.similarity(it, c) >= FamiliarMemory.DEDUP_SCORE }) facts.add(c)
                }
                val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(now))
                val u = FamiliarMemory.clampText(userText, FamiliarMemory.MEMORY_PART_CAP)
                val a = FamiliarMemory.clampText(assistantText, FamiliarMemory.MEMORY_PART_CAP)
                val line = when {
                    u.isNotEmpty() && a.isNotEmpty() -> "[$stamp] $address: $u → $selfName: $a"
                    u.isNotEmpty() -> "[$stamp] $address: $u"
                    else -> "[$stamp] $selfName: $a"
                }
                writeAtomic(FamiliarMemory.serialize(facts, state.lines + line))
            } catch (e: Exception) {
                log("写入失败（不影响对话）", e)
            }
        }
    }

    fun clear() = synchronized(lock) {
        try { writeAtomic(FamiliarMemory.serialize(emptyList(), emptyList())) } catch (e: Exception) { log("清空失败", e) }
    }

    /** 备份恢复：外部文本经 parse→serialize 归一化后覆盖落盘（脏数据/超上限内容在此被剪掉） */
    fun restore(text: String) = synchronized(lock) {
        try {
            val s = FamiliarMemory.parse(text)
            writeAtomic(FamiliarMemory.serialize(s.facts, s.lines))
        } catch (e: Exception) {
            log("恢复失败（保持原记忆）", e)
        }
    }

    private fun ensure() {
        try {
            dir.mkdirs()
            if (!file.exists()) writeAtomic(FamiliarMemory.serialize(emptyList(), emptyList()))
        } catch (e: Exception) { log("初始化失败（下次访问重试）", e) }
    }

    private fun writeAtomic(content: String) {
        val tmp = File(dir, "FamiliarMemory.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) file.writeText(content)
        }
    }
}
