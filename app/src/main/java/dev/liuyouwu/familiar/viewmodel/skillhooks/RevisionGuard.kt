package dev.liuyouwu.familiar.viewmodel.skillhooks

/** L4 核验的纯决策面（spec §6.2 二轮 N3）：挂载未变 + 历史末行仍是目标行。
 *  超时/等待分支在 Controller 协程壳（不可 JVM 单测，手工清单验收）。 */
object RevisionGuard {
    fun shouldLaunch(attached: Boolean, lastRowId: Long?, targetRowId: Long): Boolean =
        attached && lastRowId != null && lastRowId == targetRowId
}
