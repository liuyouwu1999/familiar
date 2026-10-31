package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.data.remote.api.Transcriber
import dev.liuyouwu.familiar.data.repository.ChatRepository
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.util.persona.MessageSegmenter
import java.io.File

/** 语音输入状态机（自 ChatViewModel 纯移动拆出，P5 T5）。 */
enum class VoiceState { IDLE, RECORDING, TRANSCRIBING }

/**
 * 自 ChatViewModel 纯移动拆出（P5 T5）：语音输入（录音/服务端转写/系统识别回退）与
 * TTS 朗读桥。持有 TtsManager 惰性实例与朗读/曝光推进链（familiar 曝光、课堂拆条组、
 * 聊天室角色 run 三处自动朗读触发），ChatViewModel 经构造注入 UI 状态回写通道
 * （MutableStateFlow）与依赖，行为与拆分前逐行等价（guard 经 [AgentChainGuard] 共享读）。
 */
internal class VoiceController(
    private val scope: CoroutineScope,
    private val context: Application,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val transcriber: Transcriber,
    private val error: MutableStateFlow<String?>,
    private val isSpeaking: MutableStateFlow<Boolean>,
    private val autoReadEnabled: MutableStateFlow<Boolean>,
    private val voiceState: MutableStateFlow<VoiceState>,
    private val voiceResult: MutableStateFlow<String?>,
    private val familiarReveal: MutableStateFlow<Pair<Long, Int>?>,
    private val chainGuard: AgentChainGuard,
) {

    // ===== TTS 朗读 =====
    // 惰性建 TtsManager：T10 起事件桥/曝光链在 IO 协程访问（旧链仅主线程），
    // synchronized 防两线程各建一份（实例互不知晓 onSpeakingEnded 回调）
    private val ttsLock = Any()
    private var ttsInstance: dev.liuyouwu.familiar.speech.TtsManager? = null
    private val tts: dev.liuyouwu.familiar.speech.TtsManager
        get() = synchronized(ttsLock) {
            ttsInstance ?: dev.liuyouwu.familiar.speech.TtsManager(context).also {
                it.onSpeakingEnded = { isSpeaking.value = false }
                ttsInstance = it
            }
        }

    private var mediaRecorder: android.media.MediaRecorder? = null
    private var currentAudioFile: File? = null

    /** familiar 曝光推进协程（T6 onSegments 回调驱动；停止/新链取消）。 */
    private var revealJob: Job? = null

    var serverWhisperAvailable: Boolean? = null

    // ===== Voice input methods =====

    fun startVoiceRecording() {
        try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            currentAudioFile = file
            mediaRecorder = android.media.MediaRecorder().apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            voiceState.value = VoiceState.RECORDING
        } catch (e: Exception) {
            voiceState.value = VoiceState.IDLE
        }
    }

    fun stopVoiceRecordingAndTranscribe() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        mediaRecorder?.release()
        mediaRecorder = null

        val file = currentAudioFile
        if (file == null || !file.exists()) {
            voiceState.value = VoiceState.IDLE
            return
        }

        voiceState.value = VoiceState.TRANSCRIBING
        scope.launch {
            if (serverWhisperAvailable != false) {
                val baseUrl = settingsRepository.getBaseUrl()
                val apiKey = settingsRepository.getApiKey()
                val result = transcriber.transcribeAudio(baseUrl, file, apiKey)
                if (result.isSuccess) {
                    serverWhisperAvailable = true
                    voiceResult.value = result.getOrDefault("")
                    file.delete()
                    currentAudioFile = null
                    voiceState.value = VoiceState.IDLE
                    return@launch
                }
                serverWhisperAvailable = false
                // 明确告知为什么切换：llama.cpp/Ollama 没有语音转写端点
                error.value = "当前服务不支持语音转写，已改用系统语音识别"
            }

            file.delete()
            currentAudioFile = null
            voiceState.value = VoiceState.IDLE
            // Signal fallback: set result to empty string
            voiceResult.value = ""
        }
    }

    fun cancelVoiceRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        mediaRecorder?.release()
        mediaRecorder = null
        currentAudioFile?.delete()
        currentAudioFile = null
        voiceState.value = VoiceState.IDLE
    }

    fun clearVoiceResult() {
        voiceResult.value = null
    }

    fun toggleAutoRead() {
        val next = !autoReadEnabled.value
        autoReadEnabled.value = next
        if (!next) stopSpeaking()
        scope.launch {
            settingsRepository.saveAutoReadEnabled(next)
        }
    }

    /** 朗读/停止朗读最后一条 AI 回复（T5 块模型消费：与 [speakLastAssistant] 同源——
     *  思考在 Reasoning 块不进正文，旧链 stripThinkingTags 字符串清洗退役；课堂拆条
     *  会话朗读该 turnId 组全文，chat/eli5 单行组与旧"末行"等值）。 */
    fun toggleSpeakLastReply(conversationId: Long) {
        if (isSpeaking.value) {
            stopSpeaking()
            return
        }
        scope.launch { speakLastAssistant(conversationId) }
    }

    private fun stopSpeaking() {
        tts.stop()
        isSpeaking.value = false
    }

    /**
     * 成功收线的自动朗读（chat/eli5 链级桥；课堂按段回调亦复用）：
     * 读**末条 assistant turnId 组**的全文（组内各行 Text 块按行拼接——P4 T4 修正
     * 内容漂移：课堂拆条后末行只是最后一个说话人的台词，旧链语义=整段 joined 全文；
     * chat/eli5 单行组与旧"末行"等值）。turnId 为 null 的降级行（旧数据）只读该行。
     */
    suspend fun speakLastAssistant(conversationId: Long) {
        val rows = chatRepository.getAgentMessages(conversationId).first()
        val last = rows.lastOrNull { it.role == Role.ASSISTANT } ?: return
        val group = last.turnId
            ?.takeIf { it.isNotBlank() }
            ?.let { tid -> rows.filter { it.role == Role.ASSISTANT && it.turnId == tid } }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(last)
        val text = group.joinToString("\n") { row ->
            row.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        }
        if (text.isBlank()) return
        // speak 内部经 forSpeech 剥思考块（与旧链直接 speak(saved) 同效）
        if (tts.speak(text)) isSpeaking.value = true
    }

    /**
     * 课堂段级触发（P4 T4，[SkillHost] 构造注入回调 → 事件桥）：拆条组逐行落库后
     * 回调，**组末行**（segmentIndex==total-1）=一段（一次 run 的产出）完成——
     * autoRead 整段 joined 全文（[speakLastAssistant] 读该 turnId 组）。桌宠"完成"
     * 已收拢 resolver 单路（B1 Task 4③），本桥不再直调 PetHub。
     * 频率恢复旧链"每段收线朗读"；组内非末行不触发（防逐行重复朗读）。停止/打断
     * 护栏同 [onFamiliarSegments]（epoch 门）：停止路径不朗读。
     */
    fun onClassroomSegmentPersisted(conversationId: Long, turnId: String?, segmentIndex: Int, total: Int) {
        if (segmentIndex != total - 1) return   // 组未完：等末行落库后一次触发
        val meta = chainGuard.chainMeta
        if (meta == null || meta.epoch != chainGuard.stopEpoch || meta.conversationId != conversationId) return
        scope.launch {
            if (autoReadEnabled.value) speakLastAssistant(conversationId)
        }
    }

    /**
     * 聊天室/圆桌每角色触发（P4 T4，Orchestrator runOnChain 返回处回调 → 事件桥）：
     * 每个角色 run 正常收线（引擎侧已滤取消/硬失败）即 autoRead 该角色本条产出正文
     * （引擎回调携带，与落库行同文）。桌宠"完成"已收拢 resolver 单路（B1 Task 4③）。
     * 停止护栏同课堂（epoch 门——角色间 gap 的停止竞态窗口内不触发）。（回调首参
     * roleName 当前无消费方——P5 清理批删形参，回调契约不动；要区分角色时再恢复。）
     */
    fun onChatRoomRoleTurnEnded(conversationId: Long, text: String) {
        val meta = chainGuard.chainMeta
        if (meta == null || meta.epoch != chainGuard.stopEpoch || meta.conversationId != conversationId) return
        scope.launch {
            if (autoReadEnabled.value && text.isNotBlank()) {
                if (tts.speak(text)) isSpeaking.value = true
            }
        }
    }

    /**
     * familiar 曝光推进链（T6 onSegments 回调 → T10 落地）：FamiliarHooks 切段落库后
     * 回调（rowId, segs），原 segmentAndRevealFamiliar 的 delays 时序迁移——
     * - 本链被停止/打断（epoch 已变）或"即时回复"（pacing）关：全段直出
     *   （_familiarReveal=null=默认全曝光），pacing 关且未停止时 autoRead 退化为立即读；
     *   停止路径不触发朗读（旧链 animate=false 同款）
     * - 正常：_familiarReveal 0→N 逐段推进；TTS autoRead 挪到最后一段曝光时触发
     *   （T6 裁定：familiar 的 TTS 归本链，事件桥不参与——防双读）
     * - revealJob 取消（停止/新链/新回调）由 invokeOnCompletion 清曝光态=全曝光
     *
     * pacingEnabled 由调用方（AgentSessionController 的 onSegments 装配闭包）从设置
     * 快照读出传入（快照随每链启动刷新）。
     */
    fun onFamiliarSegments(rowId: Long, segs: List<String>, pacingEnabled: Boolean) {
        val content = segs.joinToString("")
        val meta = chainGuard.chainMeta
        revealJob?.cancel()
        if (meta == null || meta.epoch != chainGuard.stopEpoch || !pacingEnabled) {
            val stopped = meta == null || meta.epoch != chainGuard.stopEpoch
            familiarReveal.value = null
            // "即时回复"（pacing 关）：全段直出，autoRead 退化为聚合完成后立即读
            if (!stopped && autoReadEnabled.value && tts.speak(content)) {
                isSpeaking.value = true
            }
            return
        }
        val dl = MessageSegmenter.delays(
            content, meta.userChars, seed = rowId,
            elapsedMs = System.currentTimeMillis() - meta.startedAt,
        )
        val job = scope.launch {
            familiarReveal.value = rowId to 0
            delay(dl[0]) // 阅读延迟（聚合耗时已抵扣，长聚合时为 0）
            familiarReveal.value = rowId to 1
            // SPEAKING 镜像：曝光推进点同步 resolver（0 基段序）；单段不镜像（SPEAKING 前提）
            if (segs.size > 1) {
                dev.liuyouwu.familiar.pet.PetHub.postResolver {
                    dev.liuyouwu.familiar.pet.PetHub.resolver.onSegmentRevealed(index = 0, total = segs.size, text = segs[0])
                }
            }
            for (i in 1 until segs.size) {
                // 思考块并入首段时段数可能与 delays 内部切数差一，越界兜底
                delay(dl.getOrElse(i) { 1000L })
                familiarReveal.value = rowId to (i + 1)
                dev.liuyouwu.familiar.pet.PetHub.postResolver {
                    dev.liuyouwu.familiar.pet.PetHub.resolver.onSegmentRevealed(index = i, total = segs.size, text = segs[i])
                }
            }
            familiarReveal.value = null
            if (autoReadEnabled.value && tts.speak(content)) {
                isSpeaking.value = true
            }
        }
        // 取消收口：段间隙被取消（停止/新链）时曝光态归位（=全曝光，与重进会话/
        // 进程重建的默认一致）；正常完成路径同为 null，幂等
        job.invokeOnCompletion { familiarReveal.value = null }
        revealJob = job
    }

    /** 曝光动画取消（停止/新链时由停止路由调用；曝光态由 invokeOnCompletion 归位）。 */
    fun cancelRevealAnimation() {
        revealJob?.cancel()
    }

    /** 宿主（ChatViewModel）销毁：释放 TTS 与录音资源，收口曝光协程。 */
    fun onHostCleared() {
        ttsInstance?.release()
        cancelVoiceRecording()
        revealJob?.cancel()
    }
}
