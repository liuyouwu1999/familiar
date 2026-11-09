package dev.liuyouwu.familiar.util

/** 用户可见错误单行文案（审计 A7）：首行 + 60 字截断，收口全仓 13 处复制。 */
fun Throwable.toUserLine(fallback: String = "未知错误"): String =
    (message ?: fallback).lineSequence().firstOrNull().orEmpty().ifEmpty { fallback }.take(60)
