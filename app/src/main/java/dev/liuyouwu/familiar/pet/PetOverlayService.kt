package dev.liuyouwu.familiar.pet

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.liuyouwu.familiar.FamiliarApp
import dev.liuyouwu.familiar.MainActivity
import dev.liuyouwu.familiar.R
import dev.liuyouwu.familiar.data.local.AppDatabase
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.skills.profile.LearningReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.random.Random

/**
 * 大肥鱼桌宠——全应用唯一一只（重写版）。应用内、桌面、其他应用上面都是这同一个
 * 悬浮窗对象（TYPE_APPLICATION_OVERLAY 小窗常驻，没有"前台守卫/退后台才浮出"，
 * 也不再有应用内第二套 Compose 渲染：两套代码导致气泡样式、拖动手感、行为全不一致）。
 *
 * 行为与应用内旧版对齐：帧动画全状态（待机呼吸/嚼 token/庆祝/出错歪头/害羞/转身/打盹）、
 * 漫画式对话泡（圆角白底+描边+尾巴贴住鱼头，泡高按文本实测、不再悬空）、
 * 单击台词/双击撒花/长按菜单（转到对话/摸摸头/冷笑话/躲起来）、
 * 拖动保持手指抓取偏移（rawX-窗口坐标，touchSlop 区分点按）、甩动晕船吐槽、
 * 闲置 45-90s 冒泡、3 分钟无互动打盹（Zzz 气泡下沉贴住睡姿）。
 *
 * 生命周期：设置里唯一的「桌宠」开关 = DataStore petEnabled。
 * 开（且已授权悬浮窗）→ MainActivity 拉起本服务；关（或长按菜单"躲起来"）→ 收窗自杀。
 * 后台守卫（P6 任务2）：退后台默认收窗（detach 摘窗不自杀，服务与状态机照跑、回前台无缝重挂），
 * petShowInBackground=true 才保留旧版跨应用常驻行为。
 * 她只说连上模型之后的事：PetHub.connected 为 false 时不出现。
 *
 * 窗口结构：鱼窗恒为鱼身大小（吞触摸区=鱼身矩形，无隐形吞触摸区）；泡窗不可触摸纯
 * 展示、菜单窗可触摸，均为独立小窗、整体钳在屏幕内——鱼贴边时泡/菜单不出界。
 */
class PetOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: SettingsRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    private var root: FrameLayout? = null
    private var fishView: ImageView? = null
    private var confettiView: ConfettiView? = null
    private var wm: WindowManager? = null
    private var winParams: WindowManager.LayoutParams? = null
    // 泡与菜单是独立悬浮窗（不再塞进鱼窗里伸缩高度）：鱼窗恒为鱼身大小，
    // 泡/菜单作为独立窗口可以整体钳在屏幕内——鱼贴边时泡不再被裁出界。
    // 泡窗 FLAG_NOT_TOUCHABLE 纯展示（手势穿透，与应用内旧版"泡区不挡点击"一致）；
    // 复习泡变体（mood=REVIEW）去掉该 flag：泡体可点（点击=收泡跳学习画像）；
    // 菜单窗可触摸。z 序：后 add 的在上（泡/菜单盖住鱼头顶的沉入部分）
    private var bubbleWin: BubbleView? = null
    private var bubbleWinParams: WindowManager.LayoutParams? = null
    /** 当前泡是否复习变体（flags/点击监听只在创建路径装，变体切换走拆掉重建） */
    private var bubbleIsReview = false
    private var menuWin: LinearLayout? = null
    private var menuWinParams: WindowManager.LayoutParams? = null

    private var fishPx = 0
    private var screenW = 0
    private var screenH = 0
    private var skinId = "orange"
    private var currentSkin: PetSkin? = null
    private var currentMood: PetHub.Mood = PetHub.Mood.IDLE
    private var frameTick: Runnable? = null
    private var swing: android.animation.ObjectAnimator? = null
    private var tiltAnim: android.animation.ValueAnimator? = null
    private var settleJob: Job? = null
    private var menuHide: Runnable? = null
    private var confettiTick: Runnable? = null
    private var dragTracker: VelocityTracker? = null
    /** attach 单飞锁：两个挂载入口（onStartCommand / state collector 兜底）都跨挂起点，
     *  check-then-act 会双挂窗口出"幽灵鱼"，必须在锁内二次判空 */
    private val attachMutex = Mutex()
    private var lastInteractionAt = System.currentTimeMillis()
    /** 防烦联动（spec §3）：距上次任意气泡显示 <60s ambient 不发；DONE 时重置 ambient 计时器 */
    private var lastBubbleShownAt = 0L
    private var ambientResetAt = 0L

    // ===== 学习画像复习拍（spec §6）=====
    /** 到期查询/记催的 DAO：服务原本不碰库，懒加载（首次复习拍或菜单 gate 收集才建实例） */
    private val profileDao by lazy { AppDatabase.getInstance(this).learningProfileDao() }
    /** 复习提醒开关缓存（settings flow 收集维护，ambientLoop 每拍读） */
    @Volatile private var reviewReminderOn = true
    /** 有未归档学习条目（菜单「复习一下」gate + 复习拍门共用；buildMenu 主线程不可查库）。
     *  唯一权威 = profileDao.hasActive() 流（Room 失效追踪响应式）：due() 只能证明
     *  "有到期"，证明不了"没有未归档"，复习拍查询不回写 false */
    @Volatile private var hasUnarchivedEntries = false

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(this)
        // 总开关（唯一开关，设置里那个按钮；长按菜单"躲起来"=写它）：关 → 收窗自杀
        scope.launch {
            repo.petEnabled.collect { on -> if (!on) detachAndStop() }
        }
        // 后台守卫（P6 任务2）：总开关开的前提下，退后台是否留窗由 petShowInBackground 决定。
        // 收窗=detach()（摘鱼/泡/菜单三窗、停帧动画，服务不死；ambientLoop 的 root!=null 守卫
        // 令隐藏期自动静默）；回前台或任一输入变化经本收集器重挂——复用挂载互斥锁 + 锁内复核
        // （与 onStartCommand / 状态收集器兜底两个挂载点同一纪律，防双挂"幽灵鱼"）
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
                if (visible == (root != null)) return@collect
                attachMutex.withLock {
                    if (visible && root == null && repo.petEnabled.first()) attach(repo.petColor.first())
                    else if (!visible && root != null) detach()
                }
            }
        }
        // 台词称呼跟随设置
        scope.launch { repo.familiarAddress.collect { PetHub.address = it } }
        // 复习拍开关缓存（学习画像 spec §6）：ambientLoop 每拍读缓存，不查 DataStore
        scope.launch { repo.reviewReminder().collect { reviewReminderOn = it } }
        // 未归档学习条目存在性（菜单 gate + 复习拍门）：Room 失效追踪响应式维护，
        // 条目建/归档即翻转，主线程菜单 gate 直接读 @Volatile
        scope.launch { profileDao.hasActive().collect { hasUnarchivedEntries = it } }
        // 配色即时切换 → 皮肤切换（不重建窗口，只换帧序列 + 预热；帧源=皮肤包 assets/pets/）
        scope.launch {
            repo.petColor.collect { c ->
                if (c != skinId) {
                    skinId = c
                    refreshSkin()
                    if (root != null) restartFramePlayer()
                }
            }
        }
        // 桌宠渲染：状态 → 气泡/帧动画/歪头/4 秒归位；顺带兜底挂载：
        // 服务活着但窗口没挂时（STICKY 重启后 connected 才翻 true、或启动竞态），
        // 任何一次状态发射都会补上 attach
        scope.launch {
            PetHub.state.collect { s ->
                if (root == null && s.connected && repo.petEnabled.first()) {
                    attachMutex.withLock {
                        // 锁内复核：开关可能在等锁期间被关（detachAndStop 在途），
                        // 此时不再挂载，让服务安心走完自杀流程
                        if (root == null && repo.petEnabled.first()) attach(repo.petColor.first())
                    }
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
        if (root != null) return START_STICKY
        scope.launch {
            // 只说连上模型之后的事：没连上模型她不出现（与产品规则一致）
            if (!repo.petEnabled.first() || !PetHub.state.value.connected) return@launch
            val color = repo.petColor.first()
            attachMutex.withLock {
                // 锁内复核：开关可能在等锁期间被关（服务自杀在途），别再挂窗
                if (root == null && repo.petEnabled.first()) attach(color)
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后屏幕尺寸变了：旧窗口坐标可能把鱼甩出屏外，重钳；泡/菜单收起后按当前
        // 状态恢复（Zzz 是长态泡，收了不恢复会整段消失；菜单不恢复）
        refreshScreenBounds()
        hideBubble()
        closeMenu()
        syncWindowLayout()
        render(PetHub.state.value)
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
            lastBubbleShownAt = System.currentTimeMillis()   // 防烦联动：任意气泡显示计时
            showBubble(s.bubble, sleep = s.mood == PetHub.Mood.SLEEP, review = review)
        } else hideBubble()
        if (s.mood == PetHub.Mood.DONE) ambientResetAt = System.currentTimeMillis()
        // 沉降分级（spec §3）：4 秒定时只管 DONE/ERROR/互动泡（打盹 Zzz 不归位，等互动唤醒）；
        // 流式驱动态 THINKING/SEARCHING/SPEAKING 不排 settleJob——由状态迁移驱动沉降：
        // 多源搜索常 >4s、SPEAKING 段间隙更长，照稿会在中途被打回 IDLE 自败镜像目标。
        // 排程前先 cancel 旧 job，SPEAKING→DONE 切回定时管时不残留旧调度。
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

    /** 皮肤解析 + 预热：onCreate 收集器与 attach 都调。get 失败（两源皆失/解析拒载）
     *  回落内置蓝；蓝也缺失则 currentSkin=null——帧播放器保持现帧（Log.w，绝不 !!） */
    private fun refreshSkin() {
        currentSkin = PetSkinStore.get(skinId) ?: PetSkinStore.get("blue")
        val skin = currentSkin
        if (skin == null) Log.w("PetOverlay", "皮肤缺失 skinId=$skinId，保持现帧")
        else scope.launch(Dispatchers.IO) { PetSkinStore.warmUp(skin) }
    }

    /** 帧推进：循环则取模，否则播完定住末帧（Handler 换帧，悬浮窗标准做法）。
     *  帧源为皮肤包位图（P5）；坏图 frameBitmap 返 null 时保持上一帧。
     *  开头现扫重解析：运行中删皮肤后 currentSkin 旧引用仍可解析出帧，必须每次现取——
     *  mood 变化频率低，零缓存契约内 Main 线程毫秒级；双 null 保留旧引用走既有 Log.w 路径 */
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
                    // 表演动作（P6 任务4b）：末帧停 500ms 自动收束（finishPerformance 按会话态回落）。
                    // lambda 带 mood 守卫：期间状态变了（新帧播放器已接管）就不越权收束
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

    // ===== 气泡（独立悬浮窗）=====

    /** 压扁弹回（P6 任务4b，调研阶段3）：甩远收手后的 squash & stretch——纯缩放动画器，
     *  不依赖新素材帧（出处项目同款手感）。链：纵向压扁 0.62+横向鼓 1.18 → 过冲回弹 → 归位；
     *  pivot 在鱼底（落地感）。scale 与 rotation 是不同属性，不与歪头/swing 互抢 */
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

    /** 冒泡。review=true 为复习泡变体（spec §6，mood=REVIEW 专用）：
     *  泡窗去 FLAG_NOT_TOUCHABLE（泡体可点，点击=收泡+跳学习画像），沉降 12s 由 render 管；
     *  review=false 与旧版闲聊泡行为完全一致（flags 同款、手势穿透、原位更新路径原样） */
    private fun showBubble(text: String, sleep: Boolean, review: Boolean = false) {
        winParams ?: return   // 挂载守卫：服务未挂窗时不冒泡
        closeMenu()
        val existing = bubbleWin
        if (existing != null) {
            if (review != bubbleIsReview) {
                // 变体切换（复习泡↔闲聊泡）：拆掉重建走下方创建路径——flags 与点击监听只在
                // 创建时装，原位更新路径改它们会碰闲聊泡的逐字不动约束
                hideBubble()
            } else {
                existing.text = text
                // 完整短句（spec §4）：文本变了重新适配窗宽（12sp 起、超 4 行降 10sp、
                // 钳 [鱼身宽, 60% 屏宽]），改写 LayoutParams.width 后 updateViewLayout 才生效
                bubbleWinParams!!.width = existing.fitContent((screenW * 0.6f).toInt())
                layoutBubbleParams(bubbleWinParams!!, existing, measuredHeightOf(existing), sleep)
                runCatching { wm?.updateViewLayout(existing, bubbleWinParams!!) }
                // 文本行数变化后 v.height 仍是旧文本的高（WRAP 窗口随后才重定高）：
                // 等一帧新高度落地再重钳位一次，泡底贴鱼头的重叠量才准（审计中-1）
                existing.post {
                    if (bubbleWin === existing) repositionOverlays()
                }
                return
            }
        }
        val b = BubbleView(this)
        b.text = text
        if (review) {
            // 复习泡点击（泡窗 root 视图）：收泡 + 跳学习画像。onOpenReview 由 AppNavigation
            // 注册（Task 10），未挂时 no-op 不崩。settle 同步清态：防 12s 内旋转等 render 重放
            // 把已点掉的泡复活
            b.setOnClickListener {
                hideBubble()
                PetHub.settle()
                PetHub.onOpenReview?.invoke()
            }
        }
        val p = WindowManager.LayoutParams(
            b.fitContent((screenW * 0.6f).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不可触摸：泡区手势穿透（应用内旧版同语义）。alpha 0.99 绕开
            // Android 12+ 对"全不透明透传窗"的不可信触摸限制。
            // 复习泡变体去掉 FLAG_NOT_TOUCHABLE：泡体收点击跳复习，泡外区域照常穿透
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (review) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.99f
        }
        bubbleWinParams = p
        layoutBubbleParams(p, b, measuredHeightOf(b), sleep)
        runCatching { wm?.addView(b, p) }
            .onFailure {
                bubbleWin = null
                bubbleWinParams = null
                return
            }
        bubbleWin = b
        bubbleWinParams = p
        bubbleIsReview = review
    }

    private fun hideBubble() {
        val b = bubbleWin ?: return
        bubbleWin = null
        bubbleWinParams = null
        bubbleIsReview = false
        runCatching { wm?.removeView(b) }
    }

    /** 泡窗位置：水平居中于鱼、整体钳在屏内（鱼贴边时泡被推回，尾巴滑向鱼头）；
     *  垂直贴鱼头、泡底下沉 overlap 与鱼身重叠；打盹泡再沉 26dp 贴住蜷姿。
     *  view 显式传入：新建路径调用时 bubbleWin 还没赋值，尾巴指向不能靠它取（审计低-3）。
     *  水平以泡窗实际宽度（fitContent 定档，存于 p.width）居中，不再写死鱼身宽 */
    private fun layoutBubbleParams(p: WindowManager.LayoutParams, view: BubbleView, bubbleH: Int, sleep: Boolean) {
        val wp = winParams ?: return
        val w = if (p.width > 0) p.width else fishPx
        val fishCx = wp.x + fishPx / 2f
        p.x = (fishCx - w / 2f).toInt().coerceIn(0, (screenW - w).coerceAtLeast(0))
        val sink = if (sleep) dp(40f) else dp(14f)
        p.y = (wp.y + sink - bubbleH).coerceIn(0, (screenH - bubbleH).coerceAtLeast(0))
        view.tailTargetPx = fishCx - p.x
    }

    // ===== 菜单（独立悬浮窗，可触摸）=====

    private fun closeMenu() {
        val m = menuWin ?: return
        menuWin = null
        menuWinParams = null
        menuHide?.let { mainHandler.removeCallbacks(it) }
        menuHide = null
        runCatching { wm?.removeView(m) }
    }

    private fun toggleMenu() {
        // 挂载守卫：detach 收窗后迟到的长按 Runnable 会把菜单窗挂到 (0,0) 成"无鱼鬼菜单"
        if (winParams == null || wm == null) return
        if (menuWin != null) {
            closeMenu()
            return
        }
        hideBubble()
        val m = buildMenu()
        val p = WindowManager.LayoutParams(
            fishPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        menuWinParams = p
        layoutMenuParams(p, measuredHeightOf(m))
        runCatching { wm?.addView(m, p) }
            .onFailure {
                menuWin = null
                menuWinParams = null
                return
            }
        menuWin = m
        menuWinParams = p
        menuHide?.let { mainHandler.removeCallbacks(it) }
        menuHide = Runnable { closeMenu() }
        mainHandler.postDelayed(menuHide!!, 8000L)
    }

    /** 菜单窗位置：水平随鱼、钳在屏内；垂直在鱼头顶上方 6dp，顶不出屏时贴屏顶 */
    private fun layoutMenuParams(p: WindowManager.LayoutParams, menuH: Int) {
        val wp = winParams ?: return
        p.x = wp.x.coerceIn(0, (screenW - fishPx).coerceAtLeast(0))
        p.y = (wp.y - menuH - dp(6f)).coerceIn(0, (screenH - menuH).coerceAtLeast(0))
    }

    /** 拖动/旋转后泡、菜单跟随鱼重新钳位 */
    private fun repositionOverlays() {
        bubbleWin?.let { b ->
            bubbleWinParams?.let { p ->
                layoutBubbleParams(p, b, measuredHeightOf(b), currentMood == PetHub.Mood.SLEEP)
                runCatching { wm?.updateViewLayout(b, p) }
            }
        }
        menuWin?.let { m ->
            menuWinParams?.let { p ->
                layoutMenuParams(p, measuredHeightOf(m))
                runCatching { wm?.updateViewLayout(m, p) }
            }
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

    private fun buildMenu(): LinearLayout {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFF8E7.toInt())
                setStroke(dp(2f), 0xFF8B5A2B.toInt())
                cornerRadius = dpf(8f)
            }
        }
        fun row(label: String, action: () -> Unit) {
            if (menu.childCount > 0) {
                menu.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                    setMargins(dp(8f), 0, dp(8f), 0)
                }.also { (it as? LinearLayout.LayoutParams)!!.gravity = Gravity.CENTER_VERTICAL })
                (menu.getChildAt(menu.childCount - 1) as View).background =
                    android.graphics.drawable.ColorDrawable(0x338B5A2B.toInt())
            }
            menu.addView(
                TextView(this).apply {
                    text = label
                    setTextColor(0xFF5A4632.toInt())
                    textSize = 14f
                    setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                    setOnClickListener { closeMenu(); action() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
        row("转到对话") {
            // 先把 app 拉到前台（进程死时重建任务），同进程导航钩子还在就直跳陪伴对话；
            // 钩子可能指向已销毁的 NavController（进程活 activity 没了），炸了就吞掉
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            runCatching { PetHub.openChat() }
        }
        // 复习一下（学习画像 spec §6）：有未归档条目才显示（@Volatile 缓存 gate，
        // buildMenu 主线程不可查库）；点击跳学习画像——AppNavigation 路由 Task 10 注册，
        // 未挂时 no-op 不崩
        if (hasUnarchivedEntries) {
            row("复习一下") {
                PetHub.onOpenReview?.invoke()
            }
        }
        row("摸摸头") {
            wake()
            // 流式态 stroke 守卫（spec §3 三态全含）：显式传 SHY 会把 THINKING/SEARCHING/
            // SPEAKING 踢飞且无还原点（镜像被打断）——菜单可弹出，该项响应受守卫忽略
            val m = PetHub.state.value.mood
            if (m != PetHub.Mood.THINKING && m != PetHub.Mood.SEARCHING && m != PetHub.Mood.SPEAKING) {
                PetHub.say(PetHub.pickLine("stroke"), PetHub.Mood.SHY)
                scope.launch { PetBondStore.noteTouch() }   // 守卫内才计数：被忽略的摸摸头不计
            }
        }
        row("讲个冷笑话") {
            wake()
            PetHub.say(PetHub.pickLine("joke"))
        }
        row("喂一口") {
            wake()
            // 流式态守卫（摸摸头同款）：生成中不打断，喂食动作不进门也不计互动
            val m = PetHub.state.value.mood
            if (m != PetHub.Mood.THINKING && m != PetHub.Mood.SEARCHING &&
                m != PetHub.Mood.SPEAKING
            ) {
                // 偷吃 token 表演（32 帧 one-shot，播完自动收束）+ 互动计数（spec §5 互动面）
                PetHub.perform(PetHub.Mood.TOKEN, PetHub.pickLine("feed"))
                scope.launch { PetBondStore.noteTouch() }
            }
        }
        row("来段表演") {
            wake()
            performRandom()
        }
        row("躲起来") {
            // 与设置里「桌宠」开关同一体：关掉总开关（设置按钮同步变关），全应用收回
            PetHub.say(PetHub.pickLine("farewell"))
            scope.launch {
                delay(1600)
                repo.setPetEnabled(false)
            }
        }
        return menu
    }

    // ===== 窗口布局 =====

    /** 鱼窗恒为鱼身大小（泡/菜单已拆独立窗，无伸缩跳动）。只做半出屏钳制，
     *  然后让可见的泡/菜单跟随重新钳位（它们整体钳在屏内，鱼贴边不出界）。 */
    private fun syncWindowLayout() {
        val wp = winParams ?: return
        val r = root ?: return
        wp.x = PetWindowBounds.clampX(wp.x, screenW, fishPx)
        wp.y = PetWindowBounds.clampY(wp.y, screenH, fishPx)
        runCatching { wm?.updateViewLayout(r, wp) }
        repositionOverlays()
    }

    /** 真实显示区（含导航栏/刘海）：displayMetrics 是 app 显示区，heightPixels 不含
     *  导航栏——贴底永远差一个导航栏高度（手势 ~24dp/三键 ~48dp），鱼尾贴不到物理底边 */
    private fun refreshScreenBounds() {
        runCatching {
            val wmgr = getSystemService(WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wmgr.maximumWindowMetrics.bounds
                screenW = b.width()
                screenH = b.height()
            } else {
                @Suppress("DEPRECATION") val d = wmgr.defaultDisplay
                val m = DisplayMetrics()
                @Suppress("DEPRECATION") d.getRealMetrics(m)
                screenW = m.widthPixels
                screenH = m.heightPixels
            }
        }.onFailure {
            // 拿不到真实尺寸就退回 app 显示区，至少不崩
            val m = resources.displayMetrics
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
    }

    /** 视图未走布局时（刚 setText/post 前）也能拿到实测高度。泡窗先经 fitContent 定档宽
     *  （measuredWidth>0），按定档宽测高——仍写死鱼身宽会把多行泡的高测错 */
    private fun measuredHeightOf(v: View): Int {
        if (v.height > 0) return v.height
        val w = if (v.measuredWidth > 0) v.measuredWidth else fishPx
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredHeight
    }

    // ===== 生命周期：挂载/摘除 =====

    private suspend fun attach(petSkinId: String) {
        skinId = petSkinId
        refreshSkin()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        fishPx = dp(140f)
        refreshScreenBounds()

        var fx = 0.92f
        var fy = 0.68f
        runCatching {
            repo.petPosition.first()?.split(",")?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 2 }
                ?.let {
                    // 落库允许负值/超 1（贴边大幅出屏停放），恢复端同口径放宽，位置不瞬移
                    fx = it[0].coerceIn(-0.6f, 1.6f)
                    fy = it[1].coerceIn(-0.6f, 1.6f)
                }
        }

        val wp = WindowManager.LayoutParams(
            fishPx,
            fishPx,   // 窗口恒=鱼身大小（泡/菜单独立窗口，永不伸缩）
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 保存/恢复同一口径：窗口顶=鱼可视顶（恒成立，泡/菜单不再改变窗口高度）
            x = PetWindowBounds.clampX((fx * (screenW - fishPx)).toInt(), screenW, fishPx)
            y = PetWindowBounds.clampY((fy * (screenH - fishPx)).toInt(), screenH, fishPx)
        }
        winParams = wp

        val rootLayout = FrameLayout(this)
        val confetti = ConfettiView(this).apply { visibility = View.GONE }
        rootLayout.addView(
            confetti,
            FrameLayout.LayoutParams(fishPx, fishPx, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        rootLayout.addView(
            iv,
            FrameLayout.LayoutParams(fishPx, fishPx, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )

        runCatching { wm?.addView(rootLayout, wp) }
            .onFailure {
                stopSelf()
                return
            }
        root = rootLayout
        fishView = iv
        confettiView = confetti
        currentMood = PetHub.state.value.mood
        restartFramePlayer()
        installTouchListener(iv)
        // 挂载后立即对齐当前状态（泡/姿态不丢）
        render(PetHub.state.value)
    }

    private fun detach() {
        frameTick?.let { mainHandler.removeCallbacks(it) }
        frameTick = null
        settleJob?.cancel()
        settleJob = null
        menuHide?.let { mainHandler.removeCallbacks(it) }
        menuHide = null
        confettiTick?.let { mainHandler.removeCallbacks(it) }
        confettiTick = null
        stopSwing()
        tiltAnim?.cancel()
        tiltAnim = null
        dragTracker?.recycle()   // 拖动中途服务被收窗：没有后续事件派发，必须手动回收
        dragTracker = null
        hideBubble()
        closeMenu()
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
        fishView = null
        confettiView = null
        winParams = null
    }

    private fun detachAndStop() {
        detach()
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        detach()
        scope.cancel()
        super.onDestroy()
    }

    // ===== 触摸：拖动 / 单击 / 双击 / 长按 =====

    private fun wake() {
        lastInteractionAt = System.currentTimeMillis()
    }

    private fun installTouchListener(iv: ImageView) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var dragDownX = 0f
        var dragDownY = 0f
        var dragStartX = 0
        var dragStartY = 0
        var moved = false
        var lastTapAt = 0L
        var longPressDone = false
        var longPressPending: Runnable? = null
        var singleTapPending: Runnable? = null

        // 多指场景 rawX/rawY 恒指 0 号指针：换指锚定必须换算。视图原点的屏幕坐标
        // 用「0 号指针 raw - 其视图坐标」反推：部分 ROM（vivo 等）会给悬浮窗视图
        // 原点垫上状态栏/挖孔 inset，wp.y 与视图原点差一大截——直接用 wp 会抓哪都
        // 先跳一截（拖动抓取点跳变的真凶）。偏移被自动吸收，且全程只施加增量。
        fun reAnchor(ev: MotionEvent, idx: Int) {
            val wp = winParams ?: return
            val originX = ev.rawX - ev.getX(0)
            val originY = ev.rawY - ev.getY(0)
            dragDownX = originX + ev.getX(idx)
            dragDownY = originY + ev.getY(idx)
            dragStartX = wp.x
            dragStartY = wp.y
            // 注意不重置 moved：拖动中换锚不应降级成单击
        }

        iv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wake()
                    dragTracker?.recycle()
                    dragTracker = VelocityTracker.obtain()
                    dragTracker!!.addMovement(ev)
                    reAnchor(ev, ev.actionIndex)
                    moved = false
                    longPressDone = false
                    longPressPending = Runnable {
                        if (!moved) {
                            longPressDone = true
                            singleTapPending?.let { mainHandler.removeCallbacks(it) }
                            wake()
                            toggleMenu()
                            scope.launch { PetBondStore.noteTouch() }   // 长按开菜单即互动（菜单无守卫，任意态可弹）
                        }
                    }
                    mainHandler.postDelayed(longPressPending!!, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    longPressPending?.let { mainHandler.removeCallbacks(it) }
                    // 新指接管拖动锚点（不重置 moved：拖动中换指不降级成单击）
                    reAnchor(ev, ev.actionIndex)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    dragTracker?.addMovement(ev)
                    val nx = dragStartX + (ev.rawX - dragDownX).toInt()
                    val ny = dragStartY + (ev.rawY - dragDownY).toInt()
                    if (!moved && (abs(nx - dragStartX) > touchSlop || abs(ny - dragStartY) > touchSlop)) {
                        moved = true
                        longPressPending?.let { mainHandler.removeCallbacks(it) }
                        singleTapPending?.let { mainHandler.removeCallbacks(it) }
                        startSwing()   // 真拖起来才挣扎；轻点不晃
                    }
                    if (moved) {
                        // 标准偏移拖动：按住哪里，哪里就不跳（手指相对鱼身的位置全程不变）。
                        // 钳制统一走 PetWindowBounds（与 syncWindowLayout/attach 同口径），
                        // 都拖得回来。必须传窗口根视图：传子视图会更新失败、视图树写坏整窗
                        // 消失（历史恶性 bug）
                        winParams?.let { wp ->
                            wp.x = PetWindowBounds.clampX(nx, screenW, fishPx)
                            wp.y = PetWindowBounds.clampY(ny, screenH, fishPx)
                            runCatching { root?.let { wm?.updateViewLayout(it, wp) } }
                            repositionOverlays()   // 泡/菜单跟随，整体钳在屏内
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressPending?.let { mainHandler.removeCallbacks(it) }
                    longPressPending = null
                    stopSwing()
                    dragTracker?.addMovement(ev)
                    dragTracker?.computeCurrentVelocity(1000)
                    if (!moved && !longPressDone) {
                        // 流式态交互守卫（spec §3 分态裁定）：tap 守卫集合=THINKING/SEARCHING——
                        // 她专心干活，单击/双击一律忽略；SPEAKING 不拦（单击放行给镜像期
                        // openChat，双击照常庆祝）。守卫优先级高于一切台词/庆祝分支
                        val mood = PetHub.state.value.mood
                        val tapGuarded = mood == PetHub.Mood.THINKING || mood == PetHub.Mood.SEARCHING
                        val now = System.currentTimeMillis()
                        if (!tapGuarded && now - lastTapAt < 350) {
                            // 双击：撤掉还挂着的单击台词，只出庆祝（否则双击会先冒一句再撒花）
                            singleTapPending?.let { mainHandler.removeCallbacks(it) }
                            singleTapPending = null
                            lastTapAt = 0
                            celebrate()
                        } else if (!tapGuarded) {
                            lastTapAt = now
                            // 单击台词延迟过双击窗口再冒，双击就不会先说一句再庆祝。
                            // 执行时（而非进窗时）查态：进窗到执行隔 360ms 会被竞态骗（spec §4.3）——
                            // 镜像期单击=进 familiar 对话（D2）；执行时已入 THINKING/SEARCHING 则守卫优先忽略
                            val line = PetHub.pickLine("tap")
                            val r = Runnable {
                                when (PetHub.state.value.mood) {
                                    PetHub.Mood.SPEAKING -> PetHub.openChat()
                                    PetHub.Mood.THINKING, PetHub.Mood.SEARCHING -> Unit
                                    else -> {
                                        PetHub.say(line)
                                        scope.launch { PetBondStore.noteTouch() }   // 互动计数：守卫态走上方 Unit，不落这里
                                    }
                                }
                            }
                            singleTapPending = r
                            mainHandler.postDelayed(r, 360L)
                        }
                    } else if (moved) {
                        // 甩远吐槽：收手瞬间速度超阈值
                        val vx = dragTracker?.xVelocity ?: 0f
                        val vy = dragTracker?.yVelocity ?: 0f
                        if (abs(vx) > 2500f || abs(vy) > 2500f) {
                            // 流式态 fling 守卫（四轮 F2）：显式 mood=TURN 不走 say 保持分支，
                            // 会把流式态踢成 TURN 且无还原点——流式期间甩动忽略，其余维持晕船吐槽
                            val m = PetHub.state.value.mood
                            if (m != PetHub.Mood.THINKING && m != PetHub.Mood.SEARCHING &&
                                m != PetHub.Mood.SPEAKING
                            ) {
                                PetHub.say(PetHub.pickLine("fling"), PetHub.Mood.TURN)
                            }
                            // 压扁弹回（P6 任务4b）：纯视觉缩放，不改状态，流式期间也安全
                            playSquashWobble()
                        }
                        // 先重钳+更新窗口，再按同步后的鱼位置保存（窗口顶=鱼顶），口径一致
                        syncWindowLayout()
                        winParams?.let { wp ->
                            val sw = (screenW - fishPx).coerceAtLeast(1).toFloat()
                            val shh = (screenH - fishPx).coerceAtLeast(1).toFloat()
                            // 存原始比例（可为负/超 1，贴边半出屏停放），恢复端放宽钳制
                            val nx = wp.x / sw
                            val ny = wp.y / shh
                            scope.launch { repo.setPetPosition(nx, ny) }
                        }
                    }
                    dragTracker?.recycle()
                    dragTracker = null
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 主指抬起：剩余指接锚继续拖（不瞬移）；副指抬起按主指继续，无需处理
                    val remaining = if (ev.actionIndex == 0) 1 else 0
                    reAnchor(ev, remaining)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 手势被系统打断：摇摆必须停，否则永远歪着
                    longPressPending?.let { mainHandler.removeCallbacks(it) }
                    longPressPending = null
                    singleTapPending?.let { mainHandler.removeCallbacks(it) }
                    singleTapPending = null
                    stopSwing()
                    dragTracker?.recycle()
                    dragTracker = null
                    true
                }
                else -> false
            }
        }
    }

    private fun celebrate() {
        wake()
        PetHub.say(PetHub.pickLine("doubleTap"))
        confettiView?.burst { tick -> confettiTick = tick }
    }

    // ===== 闲置行为 =====

    private suspend fun ambientLoop() {
        while (true) {
            delay(45_000L + Random.nextLong(45_000L))
            // 防烦联动（spec §3）判一：DONE 后重置 ambient 计时器——会话刚收束的下一拍不开口，
            // 本次到点作废、重新起算（复位阈值取调度下限，之后还有 60s 气泡闸再兜一层）
            if (System.currentTimeMillis() - ambientResetAt < 45_000L) continue
            // 复习拍（spec §6）：到期条目优先于闲聊；2h 静默期走 LearningReview.isNudgeable（T2 纯函数）。
            // 门（评审核验补）：非 IDLE/有泡不踢——REVIEW 是显式 mood，say 走显式分支不保流式态，
            // 流式中插话会把 THINKING/SEARCHING/SPEAKING 踢飞且无还原点（PetHub 对 SHY 有同款警告先例）。
            // connected/root 也在门内：say 对未连接是 no-op 而 markReminded 照写库，不开门会把
            // 催提醒白烧掉 2h 静默期（挂窗但未连接同理只在空中冒泡）。判一在其上，会话刚收束不催
            if (reviewReminderOn && hasUnarchivedEntries) {
                val ps = PetHub.state.value
                if (root != null && ps.connected &&
                    ps.mood == PetHub.Mood.IDLE && ps.bubble == null
                ) {
                    val now = System.currentTimeMillis()
                    val due = profileDao.due(now).filter { LearningReview.isNudgeable(it, now) }
                    if (due.isNotEmpty()) {
                        val top = due.first()
                        // 先记催再开口：一拍只取 first()，写库远在下一拍（45-90s 后）前完成，
                        // isNudgeable 的 2h 闸防同条目跨拍重复催
                        scope.launch { profileDao.markReminded(top.id, now) }
                        PetHub.say(
                            PetHub.pickLine("review").replace("课题", top.topic.take(8)),
                            PetHub.Mood.REVIEW,
                        )
                        continue   // 本拍不闲聊
                    }
                }
            }
            val s = PetHub.state.value
            // 判二：距上次任意气泡显示 <60s 不发——互动泡/镜像段/收束庆祝后不紧跟着闲聊
            if (root != null && s.connected && s.mood == PetHub.Mood.IDLE && s.bubble == null &&
                System.currentTimeMillis() - lastBubbleShownAt >= 60_000L
            ) {
                // 久别问候优先（spec §5：替换当日首次 ambient 闲聊；Task 6 遗留消费挂点）——
                // pending 由连接侧 maybeGreet 置位、markGreeted 已保证当日仅一次，此处只管消费
                if (PetHub.bondGreetingPending) {
                    PetHub.bondGreetingPending = false
                    PetHub.say(PetHub.pickLine("greeting"))
                } else {
                    // 空闲微动作（P6 任务4b）：环境泡本身就是 45-90s 一拍，这里再 1/8 抽中
                    // 才无声演一下（哼歌/晃尾巴）——平均约 6-12 分钟一次，点缀不抢戏
                    // （1/3 实测太频繁，用户验收调低）；皮肤没实现（橘版未跟量）就照旧说话
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
            if (root != null && s.connected && s.mood == PetHub.Mood.IDLE && s.bubble == null &&
                System.currentTimeMillis() - lastInteractionAt > 180_000L
            ) {
                PetHub.say("Zzz……", PetHub.Mood.SLEEP)
            }
        }
    }

    // ===== 自绘视图 =====

    /** 漫画式对话泡：圆角白底+深描边+底边尾巴指向鱼头；文字由 TextView 本体画 */
    private inner class BubbleView(context: Context) : TextView(context) {
        private val tailH = dpf(8f)
        private val tailW = dpf(12f)
        private val radius = dpf(10f)
        private val border = dpf(1.5f)
        private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A4A63.toInt(); style = Paint.Style.FILL }
        private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

        /** 尾巴指向目标（鱼心）在泡本体内的 x 坐标：泡被钳回屏内后，尾巴滑向鱼头方向 */
        var tailTargetPx = dpf(70f)

        init {
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            textSize = 12f
            setTextColor(0xFF2A3550.toInt())
            // 底部多留尾巴高度：最后一行不压进尾巴区
            setPadding(dp(10f), dp(7f), dp(10f), dp(15f))
        }

        /** 完整短句适配（spec §4，镜像模式同策略）：按给定上限宽实测内容宽；末行出现
         *  省略号即超 4 行，字号 12sp→10sp 降一档重测，仍溢出由 ellipsize END 兜底。
         *  返回定档窗宽（含左右内边距），钳在 [鱼身宽, 上限]——任何输入不糊满屏幕 */
        fun fitContent(maxW: Int): Int {
            textSize = 12f  // 终审 I-1：先复位再降档，防字号粘滞（View 复用，长泡降档后短泡不回 12f）
            val cap = maxW.coerceAtLeast(fishPx)
            measure(
                View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val l = layout
            if (l != null && l.lineCount >= 4 && l.getEllipsisCount(l.lineCount - 1) > 0) {
                textSize = 10f
                measure(
                    View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
            return measuredWidth.coerceIn(fishPx, cap)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val body = RectF(0f, 0f, w, h - tailH)
            // 尾巴滑向泡本体内"鱼心"的位置（泡整体钳回屏内后仍指向鱼头），钳在泡身内
            val tailX = tailTargetPx.coerceIn(tailW, w - tailW)
            // 描边：先画加粗深色底（泡身+尾巴整体轮廓），再叠白色内层
            val outline = android.graphics.Path().apply {
                addRoundRect(body, radius, radius, android.graphics.Path.Direction.CW)
                moveTo(tailX - tailW / 2, h - tailH - 1f)
                lineTo(tailX, h)
                lineTo(tailX + tailW / 2, h - tailH - 1f)
                close()
            }
            canvas.drawPath(outline, darkPaint)
            val inner = android.graphics.Path().apply {
                addRoundRect(
                    RectF(border, border, w - border, h - tailH - border),
                    radius, radius, android.graphics.Path.Direction.CW
                )
                moveTo(tailX - tailW / 2 + border, h - tailH - 1f)
                lineTo(tailX, h - border)
                lineTo(tailX + tailW / 2 - border, h - tailH - 1f)
                close()
            }
            canvas.drawPath(inner, lightPaint)
            super.onDraw(canvas)
        }
    }

    /** 双击撒花：从胸口炸开的小方块彩纸（900ms 一轮，画在鱼身窗口内） */
    private inner class ConfettiView(context: Context) : View(context) {
        private var progress = 1f
        private val colors = intArrayOf(
            0xFFF5A623.toInt(), 0xFF4C8DF6.toInt(), 0xFF7ED321.toInt(), 0xFFFF6F91.toInt(), 0xFF9B59B6.toInt()
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun burst(register: (Runnable) -> Unit) {
            progress = 0f
            visibility = View.VISIBLE
            val start = SystemClock.uptimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    progress = ((SystemClock.uptimeMillis() - start) / 900f).coerceAtMost(1f)
                    invalidate()
                    if (progress < 1f) {
                        mainHandler.postDelayed(this, 16L)
                    } else {
                        visibility = View.GONE
                    }
                }
            }
            register(tick)
            mainHandler.post(tick)
        }

        override fun onDraw(canvas: Canvas) {
            if (progress >= 1f) return
            val cx = width / 2f
            val cy = height * 0.75f
            val n = 10
            for (i in 0 until n) {
                val angle = Math.PI * 2 * i / n + i * 0.7
                val dist = progress * minOf(width, height) * 0.8f
                val x = cx + (Math.cos(angle) * dist).toFloat()
                val y = cy + (Math.sin(angle) * dist).toFloat() - progress * dp(30f)
                val s = (1f - progress) * dp(6f)
                paint.color = colors[i % colors.size]
                paint.alpha = ((1f - progress) * 255).toInt()
                canvas.drawRect(x - s / 2, y - s / 2, x + s / 2, y + s / 2, paint)
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
