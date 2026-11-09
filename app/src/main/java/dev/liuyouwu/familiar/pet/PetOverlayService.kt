package dev.liuyouwu.familiar.pet

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import dev.liuyouwu.familiar.FamiliarApp
import dev.liuyouwu.familiar.MainActivity
import dev.liuyouwu.familiar.data.repository.RoomReviewReminderSource
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.util.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 大肥鱼桌宠——全应用唯一一只（重写版）。应用内、桌面、其他应用上面都是这同一个
 * 悬浮窗对象（TYPE_APPLICATION_OVERLAY 小窗常驻）；帧动画全状态、漫画式对话泡、
 * 单击台词/双击撒花/长按菜单、拖动保持抓取偏移、甩动晕船吐槽、闲置 45-90s 冒泡、
 * 3 分钟无互动打盹。
 *
 * 生命周期：设置里唯一的「桌宠」开关 = DataStore petEnabled。开（且已授权悬浮窗）→
 * MainActivity 拉起本服务；关（或长按菜单"躲起来"）→ 收窗自杀。后台守卫（P6 任务2）：
 * 退后台默认收窗（detach 摘窗不自杀，服务与状态机照跑、回前台无缝重挂），
 * petShowInBackground=true 才保留旧版跨应用常驻行为。PetHub.connected=false 不出现。
 *
 * 职责拆分（审计 A4 拆件）：本类只剩生命周期接线、渲染/帧动画、菜单动作与循环壳——
 * 窗口状态机（四套 LayoutParams/挂载互斥/Insets 校准/触摸手势，含 R6 修复）在
 * [OverlayWindowController]；菜单视觉在 [PetMenuBuilder]；闲置冒泡策略（纯 Kotlin
 * 可单测）在 [AmbientScheduler]；复习数据经 [ReviewReminderPort] 注入（不直连 Room DAO）。
 */
class PetOverlayService : Service() {

    // 宿主 scope 装统一 handler（审计 R1，任务 1.1）：旁路协程异常收口记日志
    private val scope = appScope(Dispatchers.Main.immediate, "PetOverlayService")
    private lateinit var repo: SettingsRepository
    private val mainHandler = Handler(Looper.getMainLooper())

    // A4 拆件依赖：窗口状态机/调度策略/复习端口/菜单构建（onCreate 装配）
    private lateinit var controller: OverlayWindowController
    private lateinit var scheduler: AmbientScheduler
    private lateinit var reviewPort: ReviewReminderPort
    private lateinit var menuBuilder: PetMenuBuilder

    private var skinId = "orange"
    private var currentSkin: PetSkin? = null
    private var currentMood: PetHub.Mood = PetHub.Mood.IDLE
    private var frameTick: Runnable? = null
    private var swing: android.animation.ObjectAnimator? = null
    private var tiltAnim: android.animation.ValueAnimator? = null
    private var settleJob: Job? = null
    private var lastInteractionAt = System.currentTimeMillis()

    // 鱼/撒花视图生命周期随挂载态归控制器，这里只读引用（帧动画/庆祝用）
    private val fishView: ImageView? get() = controller.fishView
    private val confettiView: ConfettiView? get() = controller.confettiView

    /** 复习提醒开关缓存（settings flow 收集维护，ambientLoop 每拍读） */
    @Volatile private var reviewReminderOn = true
    /** 有未归档学习条目（菜单 gate + 复习拍门共用；菜单构建主线程不可查库）。
     *  唯一权威 = reviewPort.observeHasActive() 流（Room 失效追踪响应式）：due() 只能证明
     *  "有到期"，证明不了"没有未归档"，复习拍查询不回写 false */
    @Volatile private var hasUnarchivedEntries = false

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(this)
        reviewPort = RoomReviewReminderSource(this)
        scheduler = AmbientScheduler(reviewPort)
        menuBuilder = PetMenuBuilder(
            context = this,
            density = resources.displayMetrics.density,
            hasUnarchivedEntries = { hasUnarchivedEntries },
            closeMenu = { controller.closeMenu() },
            actions = buildMenuActions(),
        )
        controller = OverlayWindowController(
            context = this,
            menuProvider = { menuBuilder.build() },
            onMountFailed = { stopSelf() },
            onReviewBubbleTap = {
                PetHub.settle()
                // 终审 Important（Task 10 fix）：先拉 app 前台再导航（写法同「转到对话」）——
                // 浮窗常驻其他应用/桌面时裸 navigate 只改后台返回栈；钩子炸了吞掉
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                runCatching { PetHub.onOpenReview?.invoke() }
            },
            touchHooks = PetTouchHooks(
                wake = ::wake,
                noteTouch = { scope.launch { PetBondStore.noteTouch() } },
                celebrate = ::celebrate,
                startSwing = ::startSwing,
                stopSwing = ::stopSwing,
                squashWobble = ::playSquashWobble,
                savePosition = { nx, ny ->
                    // 旁路 launch 补捕获（审计 R1，任务 1.1）：位置保存失败只告警，不逃逸
                    scope.launch {
                        try {
                            repo.setPetPosition(nx, ny)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("PetOverlay", "保存桌宠位置失败", e)
                        }
                    }
                },
            ),
        )
        // 总开关（唯一开关，设置里那个按钮；长按菜单"躲起来"=写它）：关 → 收窗自杀
        scope.launch {
            repo.petEnabled.collect { on -> if (!on) detachAndStop() }
        }
        // 后台守卫（P6 任务2）：退后台是否留窗由 petShowInBackground 决定；收窗=detach()
        // （摘三窗、停帧动画，服务不死）；回前台或任一输入变化经本收集器重挂——挂载互斥锁
        // + 锁内复核，与 onStartCommand / 状态收集器兜底同一纪律（防双挂"幽灵鱼"）
        scope.launch {
            combine(
                repo.petEnabled,
                repo.petShowInBackground,
                FamiliarApp.inForeground,
                PetHub.state,
            ) { enabled, showBg, fg, s ->
                shouldBeVisible(s.connected, enabled, fg, showBg)
            }.collect { visible ->
                // 快路径：可见态已挂/不可见态已摘就别进锁（streaming 期状态帧高频）
                if (visible == controller.isMounted) return@collect
                controller.withMountLock {
                    if (visible && !controller.isMounted && !controller.mountFailed &&
                        repo.petEnabled.first()
                    ) {
                        doAttach(repo.petColor.first())
                    } else if (!visible && controller.isMounted) detach()
                }
            }
        }
        // 台词称呼跟随设置
        scope.launch { repo.familiarAddress.collect { PetHub.address = it } }
        // 复习拍开关缓存（学习画像 spec §6）：ambientLoop 每拍读缓存，不查 DataStore
        scope.launch { repo.reviewReminder().collect { reviewReminderOn = it } }
        // 未归档学习条目存在性（菜单 gate + 复习拍门）：Room 失效追踪响应式维护，
        // 条目建/归档即翻转，主线程菜单 gate 直接读 @Volatile
        scope.launch { reviewPort.observeHasActive().collect { hasUnarchivedEntries = it } }
        // 配色即时切换 → 皮肤切换（不重建窗口，只换帧序列 + 预热；帧源=皮肤包 assets/pets/）
        scope.launch {
            repo.petColor.collect { c ->
                if (c != skinId) {
                    skinId = c
                    refreshSkin()
                    if (controller.isMounted) restartFramePlayer()
                }
            }
        }
        // 桌宠渲染：状态 → 气泡/帧动画/歪头/4 秒归位；顺带兜底挂载：服务活着但窗口没挂时
        // （STICKY 重启后 connected 才翻 true、或启动竞态），任何一次状态发射都会补上挂载
        scope.launch {
            PetHub.state.collect { s ->
                if (!controller.isMounted && s.connected && repo.petEnabled.first()) {
                    mount(repo.petColor.first())
                }
                render(s)
            }
        }
        // 闲置冒泡 + 打盹
        scope.launch { ambientLoop() }
        scope.launch { napLoop() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (controller.isMounted) return START_STICKY
        scope.launch {
            // 只说连上模型之后的事：没连上模型她不出现（与产品规则一致）
            if (!repo.petEnabled.first() || !PetHub.state.value.connected) return@launch
            mount(repo.petColor.first())
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后屏幕尺寸变了：旧窗口坐标可能把鱼甩出屏外，重钳；泡/菜单收起后按当前
        // 状态恢复（Zzz 是长态泡，收了不恢复会整段消失；菜单不恢复）
        controller.onScreenConfigChanged()
        render(PetHub.state.value)
    }

    // ===== 生命周期：挂载/摘除（窗口状态机在 OverlayWindowController）=====

    /** 挂载单飞（onStartCommand / 状态收集器兜底共用）：锁内复核开关与挂载态
     *  （防双挂"幽灵鱼"）；R6：挂载失败（mountFailed）后本实例不再重试——
     *  服务自杀后 STICKY 重启由新实例再试 */
    private suspend fun mount(petSkinId: String) {
        if (controller.mountFailed || controller.isMounted) return
        controller.withMountLock {
            // 锁内复核：开关可能在等锁期间被关（服务自杀在途），别再挂窗
            if (controller.mountFailed || controller.isMounted || !repo.petEnabled.first()) {
                return@withMountLock
            }
            doAttach(petSkinId)
        }
    }

    /** 挂载接线：窗口创建/校准/触摸安装归控制器，皮肤/帧播放器/状态对齐归本服务
     *  （须在挂载锁内调用） */
    private suspend fun doAttach(petSkinId: String) {
        skinId = petSkinId
        refreshSkin()
        val ratio = runCatching {
            repo.petPosition.first()?.split(",")?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 2 }
                ?.let {
                    // 落库允许负值/超 1（贴边大幅出屏停放），恢复端同口径放宽，位置不瞬移
                    it[0].coerceIn(-0.6f, 1.6f) to it[1].coerceIn(-0.6f, 1.6f)
                }
        }.getOrNull()
        if (!controller.attach(ratio)) return   // R6：控制器已清态置标志，onMountFailed 已自杀
        currentMood = PetHub.state.value.mood
        restartFramePlayer()
        fishView?.let { controller.installTouchHandlers(it) }
        // 挂载后立即对齐当前状态（泡/姿态不丢）
        render(PetHub.state.value)
    }

    private fun detach() {
        frameTick?.let { mainHandler.removeCallbacks(it) }
        frameTick = null
        settleJob?.cancel()
        settleJob = null
        stopSwing()
        tiltAnim?.cancel()
        tiltAnim = null
        controller.detachWindows()   // 拖动状态回收 + 泡/菜单/撒花收口 + 鱼窗摘除
    }

    private fun detachAndStop() { detach(); stopSelf() }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        detach()
        controller.shutdown()   // 触摸 pending 等回调已随拆件移到控制器 handler，销毁时一并清
        scope.cancel()
        super.onDestroy()
    }

    // ===== 渲染 =====

    private fun render(s: PetHub.PetState) {
        // 真正的"活动"（生成/庆祝/出错/害羞）才算她醒着；ambient 自言自语走 IDLE+泡
        // 不刷新——否则 45-90s 一冒泡，3 分钟打盹永远够不着（复审中-1）
        if (s.mood != PetHub.Mood.IDLE) {
            lastInteractionAt = System.currentTimeMillis()
        }
        if (s.mood != currentMood) {
            currentMood = s.mood
            restartFramePlayer()
        }
        animateTilt(if (s.mood == PetHub.Mood.ERROR) -10f else 0f)
        val review = s.mood == PetHub.Mood.REVIEW   // 复习泡变体（spec §6）：12s 沉降 + 可点击
        if (s.bubble != null) {
            scheduler.onBubbleShown()   // 防烦联动（spec §3 判二）：任意气泡显示计时
            controller.showBubble(s.bubble, sleep = s.mood == PetHub.Mood.SLEEP, review = review)
        } else controller.hideBubble()
        if (s.mood == PetHub.Mood.DONE) scheduler.onSessionSettled()   // 防烦判一计时
        // 沉降分级（spec §3）：4 秒定时只管 DONE/ERROR/互动泡（打盹 Zzz 不归位，等互动唤醒）；
        // 流式驱动态 THINKING/SEARCHING/SPEAKING 不排 settleJob——由状态迁移驱动沉降（多源
        // 搜索常 >4s，照稿会在中途被打回 IDLE 自败镜像目标）。排程前先 cancel 旧 job。
        // 复习泡变体 12s：等用户点泡跳复习，4s 太短来不及点
        settleJob?.cancel()
        val streamingDriven = s.mood == PetHub.Mood.THINKING ||
            s.mood == PetHub.Mood.SEARCHING || s.mood == PetHub.Mood.SPEAKING
        if (s.bubble != null && s.mood != PetHub.Mood.SLEEP && !streamingDriven) {
            settleJob = scope.launch {
                delay(if (review) 12_000L else 4_000L)
                PetHub.settle()
            }
        }
    }

    /** 皮肤解析 + 预热：onCreate 收集器与挂载都调。get 失败（两源皆失/解析拒载）
     *  回落内置蓝；蓝也缺失则 currentSkin=null——帧播放器保持现帧（Log.w，绝不 !!） */
    private fun refreshSkin() {
        currentSkin = PetSkinStore.get(skinId) ?: PetSkinStore.get("blue")
        val skin = currentSkin
        if (skin == null) Log.w("PetOverlay", "皮肤缺失 skinId=$skinId，保持现帧")
        else scope.launch(Dispatchers.IO) { PetSkinStore.warmUp(skin) }
    }

    /** 帧推进：循环则取模，否则播完定住末帧（Handler 换帧，悬浮窗标准做法）。
     *  帧源为皮肤包位图（P5）；坏图 frameBitmap 返 null 时保持上一帧。开头现扫重解析：
     *  运行中删皮肤后旧引用仍可解析出帧，必须每次现取（零缓存契约内 Main 线程毫秒级） */
    private fun restartFramePlayer() {
        currentSkin = PetSkinStore.get(skinId) ?: PetSkinStore.get("blue") ?: currentSkin
        frameTick?.let { mainHandler.removeCallbacks(it) }
        frameTick = null
        val iv = fishView ?: return
        val skin = currentSkin
        if (skin == null) { Log.w("PetOverlay", "皮肤缺失 skinId=$skinId，保持现帧"); return }   // 绝不 !!
        val clip = skin.clips[currentMood] ?: skin.clips.getValue(PetHub.Mood.IDLE)
        // 本 clip 整段后台预解码（IO）：512px 帧主线程现解码 5-15ms/张是卡顿源；
        // 首帧仍同步解码兜底（协程未跑完时 tick 靠 LruCache 未命中路径现解，不空帧）
        scope.launch(Dispatchers.IO) {
            clip.frames.forEach { PetSkinStore.frameBitmap(skin, it.first) }
        }
        val tick = object : Runnable {
            var index = 0
            var elapsed = 0L
            override fun run() {
                val total = clip.frames.sumOf { it.second }
                val t = if (clip.loop) elapsed % total else elapsed
                var acc = 0L
                var f = clip.frames.size - 1
                for ((i, fr) in clip.frames.withIndex()) {
                    acc += fr.second
                    if (t < acc) { f = i; break }
                }
                index = f
                PetSkinStore.frameBitmap(skin, clip.frames[f].first)?.let { iv.setImageBitmap(it) }   // null=坏图保持上一帧
                if (!clip.loop && elapsed >= total) {   // 播完定住末帧
                    // 表演动作（P6 任务4b）：末帧停 500ms 自动收束；lambda 带 mood 守卫：
                    // 期间状态变了（新帧播放器已接管）就不越权收束
                    if (PetHub.isPerformance(currentMood)) {
                        val played = currentMood
                        mainHandler.postDelayed({
                            if (currentMood == played) PetHub.finishPerformance()
                        }, 500L)
                    }
                    return
                }
                val d = clip.frames[index].second
                elapsed += d
                mainHandler.postDelayed(this, d)
            }
        }
        frameTick = tick
        PetSkinStore.frameBitmap(skin, clip.frames[0].first)?.let { iv.setImageBitmap(it) }
        mainHandler.postDelayed(tick, clip.frames[0].second)
    }

    /** ERROR 歪头；拖动挣扎（swing）优先，不抢旋转 */
    private fun animateTilt(target: Float) {
        val iv = fishView ?: return
        if (swing != null) return
        tiltAnim?.cancel()
        tiltAnim = android.animation.ValueAnimator.ofFloat(iv.rotation, target).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (swing == null) iv.rotation = it.animatedValue as Float
            }
            start()
        }
    }

    private fun startSwing() {
        val iv = fishView ?: return
        tiltAnim?.cancel()
        tiltAnim = null
        swing = android.animation.ObjectAnimator.ofFloat(iv, View.ROTATION, -3f, 3f).apply {
            duration = 180
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopSwing() {
        // cancel 会停在当前角度：必须把鱼摆正（歪头态回到歪头角），否则收手后永久歪着
        swing?.cancel()
        swing = null
        fishView?.rotation = if (currentMood == PetHub.Mood.ERROR) -10f else 0f
    }

    /** 压扁弹回（P6 任务4b）：甩远收手后的 squash & stretch——纯缩放动画器，不依赖新素材帧。
     *  pivot 在鱼底（落地感）；scale 与 rotation 是不同属性，不与歪头/swing 互抢 */
    private fun playSquashWobble() {
        val iv = fishView ?: return
        iv.pivotX = iv.width / 2f
        iv.pivotY = iv.height.toFloat()
        val sy = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.62f, 1.12f, 0.95f, 1f)
        val sx = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f, 0.92f, 1.02f, 1f)
        android.animation.ValueAnimator.ofPropertyValuesHolder(sx, sy).apply {
            duration = 620
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    /** 随机表演（P6 任务4b）：从皮肤实际声明了 clip 的表演动作里抽一个——缺失键的皮肤
     *  （橘版未跟量）不进抽选，抽中的必真演；一个都没有退回冷笑话台词 */
    private fun performRandom() {
        val options = PetHub.PERFORMANCE_MOODS.filter { currentSkin?.clips?.containsKey(it) == true }
        if (options.isEmpty()) {
            PetHub.say(PetHub.pickLine("joke"))
            return
        }
        PetHub.perform(options[Random.nextInt(options.size)], PetHub.pickLine("tap"))
    }

    /** 菜单动作装配（审计 A4 拆件：视觉归 PetMenuBuilder，动作/守卫留本服务） */
    private fun buildMenuActions(): PetMenuActions = PetMenuActions(
        openChat = {
            // 先把 app 拉到前台（进程死时重建任务），同进程导航钩子还在就直跳陪伴对话；
            // 钩子可能指向已销毁的 NavController，炸了就吞掉
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            runCatching { PetHub.openChat() }
        },
        openReview = {
            // 终审 Important（Task 10 fix）：先拉前台再导航（写法同「转到对话」）；钩子炸了吞掉
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            runCatching { PetHub.onOpenReview?.invoke() }
        },
        stroke = {
            wake()
            // 流式态 stroke 守卫（spec §3 三态全含）：显式传 SHY 会把流式态踢飞且无还原点——
            // 菜单可弹出，该项响应受守卫忽略
            val m = PetHub.state.value.mood
            if (m != PetHub.Mood.THINKING && m != PetHub.Mood.SEARCHING && m != PetHub.Mood.SPEAKING) {
                PetHub.say(PetHub.pickLine("stroke"), PetHub.Mood.SHY)
                scope.launch { PetBondStore.noteTouch() }   // 守卫内才计数：被忽略的摸摸头不计
            }
        },
        joke = {
            wake()
            PetHub.say(PetHub.pickLine("joke"))
        },
        feed = {
            wake()
            // 流式态守卫（摸摸头同款）：生成中不打断，喂食动作不进门也不计互动
            val m = PetHub.state.value.mood
            if (m != PetHub.Mood.THINKING && m != PetHub.Mood.SEARCHING &&
                m != PetHub.Mood.SPEAKING
            ) {
                // 偷吃 token 表演（32 帧 one-shot，播完自动收束）+ 互动计数（spec §5 互动面）
                PetHub.perform(PetHub.Mood.TOKEN, PetHub.pickLine("feed"))
                // 旁路 launch 补捕获（审计 R1，任务 1.1）：计数失败只告警，不逃逸
                scope.launch {
                    try {
                        PetBondStore.noteTouch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("PetOverlay", "互动计数失败", e)
                    }
                }
            }
        },
        performRandom = {
            wake()
            performRandom()
        },
        hideAway = {
            // 与设置里「桌宠」开关同一体：关掉总开关（设置按钮同步变关），全应用收回
            PetHub.say(PetHub.pickLine("farewell"))
            // 旁路 launch 补捕获（审计 R1，任务 1.1）：关开关失败只告警，不逃逸
            scope.launch {
                try {
                    delay(1600)
                    repo.setPetEnabled(false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("PetOverlay", "关闭桌宠开关失败", e)
                }
            }
        },
    )

    private fun wake() { lastInteractionAt = System.currentTimeMillis() }

    private fun celebrate() {
        wake()
        PetHub.say(PetHub.pickLine("doubleTap"))
        confettiView?.burst()
    }

    // ===== 闲置行为（策略判定在 AmbientScheduler，副作用留本服务）=====

    private suspend fun ambientLoop() {
        while (true) {
            delay(scheduler.beatDelayMs())
            // 防烦联动（spec §3）判一：DONE 后重置 ambient 计时器——会话刚收束的下一拍不开口，
            // 本次到点作废、重新起算（复位阈值取调度下限，之后还有 60s 气泡闸再兜一层）
            if (scheduler.inSettleQuietWindow()) continue
            // 复习拍（spec §6）：到期条目优先于闲聊；2h 静默期在 RoomReviewReminderSource 的
            // isNudgeable 口径内。门：非 IDLE/有泡不踢（REVIEW 是显式 mood，say 走显式分支不保
            // 流式态，流式中插话会踢飞且无还原点）；connected/挂窗也在门内——say 对未连接是
            // no-op 而 markReminded 照写库，不开门会白烧掉 2h 静默期。判一在其上
            val ps = PetHub.state.value
            val reviewGates = AmbientGates(
                visible = controller.isMounted,
                connected = ps.connected,
                idle = ps.mood == PetHub.Mood.IDLE,
                bubbleShowing = ps.bubble != null,
            )
            if (scheduler.shouldNudgeReview(reviewReminderOn, hasUnarchivedEntries, reviewGates)) {
                val top = reviewPort.dueTop()
                if (top != null) {
                    val now = System.currentTimeMillis()
                    // 先记催再开口：一拍只取一条，写库远在下一拍（45-90s 后）前完成，
                    // isNudgeable 的 2h 闸防同条目跨拍重复催
                    scope.launch { reviewPort.markReminded(top.id, now) }
                    PetHub.say(
                        PetHub.pickLine("review").replace("课题", top.topic.take(8)),
                        PetHub.Mood.REVIEW,
                    )
                    continue   // 本拍不闲聊
                }
            }
            // 判二：距上次任意气泡显示 <60s 不发——互动泡/镜像段/收束庆祝后不紧跟着闲聊
            // （查询可能跨挂起点，状态在此重读一次再裁定）
            val s = PetHub.state.value
            val chatGates = AmbientGates(
                visible = controller.isMounted,
                connected = s.connected,
                idle = s.mood == PetHub.Mood.IDLE,
                bubbleShowing = s.bubble != null,
            )
            if (scheduler.shouldAmbient(chatGates)) {
                // 久别问候优先（spec §5）：pending 由连接侧 maybeGreet 置位、markGreeted 已保证
                // 当日仅一次，此处只管消费
                if (PetHub.bondGreetingPending) {
                    PetHub.bondGreetingPending = false
                    PetHub.say(PetHub.pickLine("greeting"))
                } else {
                    // 空闲微动作（P6 任务4b）：环境泡本身就是 45-90s 一拍，这里再 1/8 抽中才无声
                    // 演一下（哼歌/晃尾巴），点缀不抢戏；皮肤没实现（橘版未跟量）就照旧说话
                    val micro = listOf(PetHub.Mood.HUM, PetHub.Mood.TAIL)
                        .filter { currentSkin?.clips?.containsKey(it) == true }
                    if (micro.isNotEmpty() && Random.nextInt(8) == 0) {
                        PetHub.perform(micro[Random.nextInt(micro.size)])
                    } else {
                        PetHub.say(PetHub.pickLine("ambient"))
                    }
                }
            }
        }
    }

    private suspend fun napLoop() {
        while (true) {
            delay(30_000L)
            val s = PetHub.state.value
            if (controller.isMounted && s.connected && s.mood == PetHub.Mood.IDLE && s.bubble == null &&
                System.currentTimeMillis() - lastInteractionAt > 180_000L
            ) {
                PetHub.say("Zzz……", PetHub.Mood.SLEEP)
            }
        }
    }

    companion object {
        /** 悬浮窗可见判定（纯函数，JVM 可测）：连上模型 + 总开关开才存在；
         *  退后台时按 petShowInBackground 决定去留（false=收窗，true=跨应用常驻） */
        fun shouldBeVisible(
            connected: Boolean,
            enabled: Boolean,
            foreground: Boolean,
            showInBackground: Boolean,
        ): Boolean = connected && enabled && (foreground || showInBackground)

        /** 「桌宠」开关打开（且已授权悬浮窗）时调用；服务常驻（STICKY） */
        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, PetOverlayService::class.java))
        }

        /** 开关关闭时停掉（服务自己也会因 petEnabled 变化自杀，这里是即时响应） */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PetOverlayService::class.java))
        }
    }
}
