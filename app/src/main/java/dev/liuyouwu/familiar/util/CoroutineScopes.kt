package dev.liuyouwu.familiar.util

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * 宿主 scope 统一工厂（审计 R1/R4/R10，任务 1.1）：SupervisorJob + dispatcher +
 * CoroutineExceptionHandler 三件套——旁路协程（无人 await 的 launch）异常不再
 * 逃逸到全局 handler（进程级风险），统一经 handler 记日志收口。
 *
 * logger 注入缝：JVM 单测传 `{ _, _, _ -> }` 防 android.util.Log
 * "not mocked" 崩；生产缺省走 Log.e。
 */
fun appScope(
    dispatcher: CoroutineDispatcher,
    tag: String,
    // logger 注入缝：JVM 单测传 { _,_,_ -> } 防 android.util.Log "not mocked" 崩
    logger: (String, String, Throwable) -> Unit = { tag, msg, t -> Log.e(tag, msg, t) },
): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, t ->
                logger(tag, "uncaught coroutine exception", t)
            },
    )
