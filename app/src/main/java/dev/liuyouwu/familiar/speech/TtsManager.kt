package dev.liuyouwu.familiar.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 系统 TTS 封装：懒初始化、朗读时跳过思考块与代码块（把本地思考模型的
 * 思考内容念出来是灾难）、新朗读自动打断旧的。
 * 生命周期跟随调用方（ChatViewModel.onCleared 时 release）。
 */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    // 初始化在途标记：防止初始化未完成时 speak 再次触发 initTts 重复创建引擎（防重入）
    private var initializing = false
    private var pendingText: String? = null
    // 初始化失败标记：下次 speak 时据此重建引擎自动重试，而不是永久返回 false
    private var initFailed = false
    // 代际计数：release() 自增，使 release 后才到达的 init 回调全部失效
    private var generation = 0

    /** 朗读自然结束/出错时回调（UI 用来同步按钮状态） */
    var onSpeakingEnded: (() -> Unit)? = null

    /** 当前是否正在朗读 */
    @Volatile
    var isSpeaking: Boolean = false
        private set

    private fun initTts() {
        initializing = true
        val gen = generation
        // 重建场景：先释放旧实例，避免多次重试累积泄漏底层服务连接
        tts?.shutdown()
        tts = TextToSpeech(context.applicationContext) { status ->
            // 无论回调是否过期，本次初始化都已结束
            initializing = false
            // 代际校验：release 后才到达的旧回调不得置 ready / 触发朗读
            if (gen != generation) return@TextToSpeech
            ready = status == TextToSpeech.SUCCESS
            initFailed = !ready
            if (ready) {
                tts?.language = Locale.CHINA
                pendingText?.let { p ->
                    pendingText = null
                    speakNow(p)
                }
            }
        }
    }

    /**
     * 朗读文本；自动剥思考块/代码块/Markdown 标记。
     * @return 是否真正受理（对外契约）：清洗后无可读内容（空白）返回 false，
     *   调用方不得进入"朗读中"状态；受理（立即开读，或排队等初始化完成后开读）
     *   返回 true。初始化失败不再永久锁死：下次 speak 会复位失败标记、
     *   重建引擎自动重试（初始化在途时不重复 init，只排队）。
     *   朗读自然结束/出错时通过 [onSpeakingEnded] 回调。
     */
    fun speak(text: String): Boolean {
        val clean = forSpeech(text)
        if (clean.isBlank()) return false
        if (ready) {
            speakNow(clean)
            return true
        }
        // 初始化在途：排队等回调开读即可，不重复 init（防重入）
        if (initializing) {
            pendingText = clean
            return true
        }
        // 首次调用（引擎未创建）或上次初始化失败：复位失败标记并重建引擎，
        // 失败不再导致 speak 永远返回 false
        initFailed = false
        pendingText = clean
        initTts()
        return true
    }

    private fun speakNow(clean: String) {
        val t = tts ?: return
        if (t.isSpeaking) t.stop()
        isSpeaking = true
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                onSpeakingEnded?.invoke()
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                onSpeakingEnded?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) {
                isSpeaking = false
                onSpeakingEnded?.invoke()
            }
        })
        // QUEUE_FLUSH：新段直接打断上一段（familiar 分段连播/课堂逐行），不做排队——
        // P5 裁定：排队策略留真机观察后定（打断丢段尚未实测成问题，先不加复杂度）
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        pendingText = null
        isSpeaking = false
        tts?.stop()
    }

    fun release() {
        // 代际自增：使在途的 init 回调失效（不得再置 ready / 触发朗读）
        generation++
        stop()
        tts?.shutdown()
        tts = null
        ready = false
        // 释放后允许下次 speak 重新走完整初始化流程
        initializing = false
        initFailed = false
    }

    companion object {
        /** 供 UI/自动朗读统一使用的文本清洗 */
        fun forSpeech(content: String): String {
            var s = content
            // 剥思考块（新/旧标签 + 未闭合）
            for (tag in listOf("think", "thinkyle")) {
                s = s.replace(Regex("<$tag>[\\s\\S]*?</$tag>"), "")
                val idx = s.indexOf("<$tag>")
                if (idx >= 0) s = s.substring(0, idx)
            }
            // 代码块与行内代码不念
            s = s.replace(Regex("```[\\s\\S]*?```"), "（代码略）")
            s = s.replace(Regex("`([^`]+)`"), "$1")
            // 图片/链接只念文字
            s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            // 常用 Markdown 标记
            s = s.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            s = s.replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "")
            s = s.replace("|", "，")
            return s.trim()
        }
    }
}
