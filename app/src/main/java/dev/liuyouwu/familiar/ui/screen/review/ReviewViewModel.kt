// ReviewViewModel.kt
package dev.liuyouwu.familiar.ui.screen.review

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.liuyouwu.familiar.data.local.dao.LearningProfileDao
import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity
import dev.liuyouwu.familiar.skills.profile.LearningReview
import dev.liuyouwu.familiar.skills.profile.ReviewResultParser
import dev.liuyouwu.familiar.viewmodel.ReviewChat
import dev.liuyouwu.familiar.util.toUserLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 复习屏 VM（学习画像 Task 10 Step 4，spec §6）：驱动 [ReviewChat] 侧信道路径——
 * 消息列表为**内存态** [mutableStateListOf]（不入 Room messages 表），结束判定 =
 * 教练回复解析出 JSON 收尾行（[ReviewResultParser.parse] 非空）→ 逐条
 * [LearningReview.applyResult] 写 DAO → 置完成态展示结果卡。
 *
 * - 首条出题：init 装配后发一条「开始」让教练开考（prompt 约定逐条出题）；
 * - **落账时机与幂等**（Task 10 报告项）：只在收尾 JSON 解析成功的当下落账一次
 *   （[applied] 单飞门 + 完成态后拒绝再发）；解析失败（空列表）**零写入**；结果里
 *   不属于本屏条目（entryIds 之外/已不存在）的 id 逐条跳过——教练幻觉 id 不外溢；
 * - **中途退出语义**：仅当收尾行已到并解析成功才落账（含部分：只落 JSON 里列出的
 *   条目）；没熬到收尾就退出 = 本轮全部不落账（下次到期再复习），允许部分落账；
 * - sessionFactory 注入（生产 = ChatViewModel.reviewSession）：VM 不感知 SkillHost
 *   装配料，JVM 单测可注入假件。
 */
class ReviewViewModel(
    private val dao: LearningProfileDao,
    private val entryIds: List<Long>,
    private val sessionFactory: (List<LearningProfileEntryEntity>) -> ReviewChat,
) : ViewModel() {

    /** 内存态消息（fromUser=true 右侧气泡）。 */
    data class UiMessage(val fromUser: Boolean, val text: String)

    /** 结果卡单行（point + 本次判定）。 */
    data class ResultItem(val point: String, val pass: Boolean)

    /** 结果卡汇总：total=实落账条数（跳过条不计）。 */
    data class Summary(val total: Int, val passed: Int, val items: List<ResultItem>)

    val messages = mutableStateListOf<UiMessage>()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary.asStateFlow()

    private var chat: ReviewChat? = null

    /** 落账单飞门：一次复习会话至多 settle 一次（重复收尾行/重复解析不重复写）。 */
    private var applied = false

    init {
        viewModelScope.launch {
            val ids = entryIds.distinct()
            val entries = ids.mapNotNull { dao.byId(it) }
            if (entries.isEmpty()) {
                messages += UiMessage(false, "没有可复习的要点，先去学习圈聊几轮吧。")
                return@launch
            }
            chat = sessionFactory(entries)
            sendInternal("开始")
        }
    }

    /** 用户发送入口：忙等/已完成态拒绝（完成态 = 已落账，防二次写入）。 */
    fun send(text: String) {
        if (_busy.value || _summary.value != null) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { sendInternal(trimmed) }
    }

    private suspend fun sendInternal(text: String) {
        val session = chat ?: return
        if (_busy.value || _summary.value != null) return   // init/send 双入口重入门
        _busy.value = true
        messages += UiMessage(fromUser = true, text = text)
        try {
            val reply = session.ask(text)
            messages += UiMessage(fromUser = false, text = reply.ifBlank { "（教练走神了，再说一遍？）" })
            ReviewResultParser.parse(reply).takeUnless { it.isEmpty() }?.let { settle(it) }
        } catch (e: CancellationException) {
            throw e   // 协作取消不吞（onCleared/退出屏）
        } catch (e: Exception) {
            messages += UiMessage(false, "（这一轮没接上：${e.toUserLine()}——再发一次试试）")
        } finally {
            _busy.value = false
        }
    }

    /**
     * 收尾落账：收尾 JSON 解析成功的当下逐条 applyResult 写 DAO。幂等三重门：
     * [applied] 单飞 + 完成态拒绝再发 + 只认本屏条目 id。**解析失败不进本函数，
     * 零写入**。实落账条数可能少于 JSON 条数（幻觉 id / 条目已删被跳过）——结果卡
     * total 按实落账计。
     */
    private suspend fun settle(results: List<Pair<Long, Boolean>>) {
        if (applied) return
        applied = true
        val sessionIds = entryIds.distinct().toSet()
        val now = System.currentTimeMillis()
        val items = mutableListOf<ResultItem>()
        for ((id, pass) in results) {
            if (id !in sessionIds) continue
            val entry = dao.byId(id) ?: continue
            dao.update(LearningReview.applyResult(entry, pass, now))
            items += ResultItem(entry.point, pass)
        }
        _summary.value = Summary(total = items.size, passed = items.count { it.pass }, items = items)
    }

    override fun onCleared() {
        chat?.cancel()   // 释放专享链 scope（在飞 run 即刻中断）
    }

    companion object {
        /** 导航层工厂（entryIds 路由参数 + ChatViewModel 的 sessionFactory 注入）。 */
        fun factory(
            dao: LearningProfileDao,
            entryIds: List<Long>,
            sessionFactory: (List<LearningProfileEntryEntity>) -> ReviewChat,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReviewViewModel(dao, entryIds, sessionFactory) }
        }
    }
}
