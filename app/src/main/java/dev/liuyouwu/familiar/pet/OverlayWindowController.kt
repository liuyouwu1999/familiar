package dev.liuyouwu.familiar.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex

/** 触摸手势的业务反应集（审计 A4 拆件）：手势识别与窗口操作归控制器，
 *  台词/庆祝/动画/计数/位置持久化等宠物业务留 Service */
class PetTouchHooks(
    val wake: () -> Unit,
    val noteTouch: () -> Unit,
    val celebrate: () -> Unit,
    val startSwing: () -> Unit,
    val stopSwing: () -> Unit,
    val squashWobble: () -> Unit,
    val savePosition: (nx: Float, ny: Float) -> Unit,
)

/**
 * 悬浮窗窗口控制器（审计 A4 拆件）：鱼/泡/菜单三窗的四套 LayoutParams、attach/detach
 * 状态机与挂载单飞锁、Insets 校准、贴边钳制、触摸手势识别全部收口在这里——
 * PetOverlayService 只剩生命周期接线与宠物业务（台词/菜单动作/庆祝动画）。
 * 业务不经本类：复习泡点击/菜单构建/手势反应经构造回调打回 Service。
 *
 * 挂载纪律：所有挂载入口（onStartCommand / 后台守卫收集器 / 状态收集器兜底）都跨
 * 挂起点，check-then-act 会双挂窗口出"幽灵鱼"——必须在 withMountLock 内二次复核
 * （isMounted / mountFailed / 开关）后再 attach。
 *
 * R6（审计顺带修复）：attach 的 addView onFailure 时同步清 winParams/wm 并置
 * mountFailed——原实现失败后只 stopSelf，winParams 残留非空：showBubble 的挂载守卫
 * 会被半挂态骗过（泡窗挂到无鱼窗上），收集器也会反复重试 addView。mountFailed 阻断
 * 本实例所有重试；服务自杀后 STICKY 重启由新实例再试。
 */
class OverlayWindowController(
    private val context: Context,
    /** R6：挂载失败回调（Service 侧 stopSelf）；此时 winParams/wm 已清、mountFailed 已置 */
    private val onMountFailed: () -> Unit = {},
    /** 复习泡点击业务（收泡由控制器做；settle/拉前台/跳学习画像归 Service） */
    private val onReviewBubbleTap: () -> Unit = {},
    /** 长按菜单内容构建（PetMenuBuilder 在 Service 装配动作后提供） */
    private val menuProvider: () -> LinearLayout = { LinearLayout(context) },
    /** 触摸手势的宠物业务反应（台词/庆祝/摇摆动画/互动计数/位置保存） */
    private val touchHooks: PetTouchHooks = PetTouchHooks({}, {}, {}, {}, {}, {}, { _, _ -> }),
) {

    private val density get() = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    private val mainHandler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    /** 鱼身视图（帧动画/触摸宿主）与撒花视图：控制器创建持有、生命周期随挂载态，Service 只读接线 */
    var fishView: ImageView? = null
        private set
    var confettiView: ConfettiView? = null
        private set

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
    /** 当前泡是否打盹（Zzz）变体：重钳位时泡底下沉量取此值（原实现读 Service 的
     *  currentMood==SLEEP；拆件后由 showBubble 入参维护，有泡时两者恒等价） */
    private var bubbleSleep = false
    private var menuWin: LinearLayout? = null
    private var menuWinParams: WindowManager.LayoutParams? = null
    private var menuHide: Runnable? = null
    private var dragTracker: VelocityTracker? = null

    private var fishPx = 0
    private var screenW = 0
    private var screenH = 0
    // 纵向钳制原点偏移（挂载后 calibrateContentBounds 用 rootWindowInsets 实测校准）：
    // NO_LIMITS 后鱼窗请求坐标的挂载原点在状态栏下，物理底=screenH——不知道这个
    // 偏移则纵向贴边数学全错（模拟器实测 originTop=136）。x 无偏移
    private var originTop = 0

    /** attach 单飞锁：多个挂载入口（onStartCommand / 后台守卫收集器 / state collector 兜底）
     *  都跨挂起点，check-then-act 会双挂窗口出"幽灵鱼"，必须在锁内二次判空 */
    private val attachMutex = Mutex()

    /** R6：挂载失败标志——置位后本实例不再重试（收集器/onStartCommand 全部短路） */
    var mountFailed = false
        private set

    /** 挂载态：鱼窗已挂（Service 收集器快路径与各门控读取） */
    val isMounted: Boolean get() = root != null

    /** 挂载入口互斥（防双挂"幽灵鱼"）：check-then-act 复核必须在锁内做。
     *  （Mutex.withLock 是 inline 非 suspend lambda，包一层显式 lock/unlock 才能收 suspend 块） */
    suspend fun <T> withMountLock(block: suspend () -> T): T {
        attachMutex.lock()
        try {
            return block()
        } finally {
            attachMutex.unlock()
        }
    }

    // ===== 生命周期：挂载/摘除 =====

    /** 服务销毁收口：清本控制器 handler 上全部待发回调（触摸 pending 等，原 onDestroy
     *  removeCallbacksAndMessages 的等价路径）；窗口摘除走 [detachWindows] */
    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 挂载鱼窗（含撒花/鱼身子视图）。调用方必须在 withMountLock 内且已复核
     * isMounted / mountFailed / 开关。initialRatio=落库位置比例（null→默认 0.92/0.68，
     * 调用方按落库口径放宽钳制后传入）。挂载成功后即做纵向原点校准；
     * 帧/触摸/状态对齐由 Service 在拿到 true 后接线。
     * 返回是否挂载成功；失败时已清 winParams/wm、置 mountFailed 并回调 onMountFailed（R6）。
     */
    fun attach(initialRatio: Pair<Float, Float>?): Boolean {
        if (mountFailed) return false
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fishPx = dp(140f)
        refreshScreenBounds()

        val (fx, fy) = initialRatio ?: 0.92f to 0.68f

        val wp = WindowManager.LayoutParams(
            fishPx,
            fishPx,   // 窗口恒=鱼身大小（泡/菜单独立窗口，永不伸缩）
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NO_LIMITS 是贴边手感的前提：WM 会把无此 flag 的悬浮窗静默钳回屏内
            // （模拟器 dumpsys 实证：请求 x=869 → 实际 frame x=695=屏宽-窗宽），
            // 贴边所需的透明边距出屏量全成空文；加上后请求坐标才真实落地（原点偏移
            // 由 calibrateContentBounds 实测校准，钳制语义=角色贴缘不出屏）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 保存/恢复同一口径：窗口顶=鱼可视顶（恒成立，泡/菜单不再改变窗口高度）
            x = PetWindowBounds.clampX((fx * (screenW - fishPx)).toInt(), screenW, fishPx)
            y = PetWindowBounds.clampY((fy * (screenH - fishPx)).toInt(), originTop, screenH, fishPx)
        }
        winParams = wp

        val rootLayout = FrameLayout(context)
        val confetti = ConfettiView(context, density).apply { visibility = View.GONE }
        rootLayout.addView(
            confetti,
            FrameLayout.LayoutParams(fishPx, fishPx, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        rootLayout.addView(
            iv,
            FrameLayout.LayoutParams(fishPx, fishPx, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )

        runCatching { wm?.addView(rootLayout, wp) }
            .onFailure { e ->
                // R6：挂载失败同步清 winParams/wm 并置失败标志——半挂态（winParams 非空）
                // 会骗过 showBubble 的挂载守卫把泡窗挂到无鱼窗上；置位阻断收集器重试
                Log.w("PetOverlay", "鱼窗挂载失败", e)
                winParams = null
                wm = null
                mountFailed = true
                onMountFailed()
                return false
            }
        root = rootLayout
        fishView = iv
        confettiView = confetti
        // 挂载即校准纵向原点（addView 后 rootWindowInsets 已可读）；insets 若未派发则下一帧补校
        calibrateContentBounds()
        if (originTop == 0) root?.post { calibrateContentBounds() }
        return true
    }

    /** 摘除全部窗口（泡/菜单/撒花收口 + 鱼窗摘除）并复位挂载态。
     *  Service 先自行停帧动画/动画器，再调本方法 */
    fun detachWindows() {
        dragTracker?.recycle()   // 拖动中途服务被收窗：没有后续事件派发，必须手动回收
        dragTracker = null
        hideBubble()
        closeMenu()
        confettiView?.stop()
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
        fishView = null
        confettiView = null
        winParams = null
    }

    // ===== 气泡（独立悬浮窗）=====

    /** 冒泡。review=true 为复习泡变体（spec §6，mood=REVIEW 专用）：
     *  泡窗去 FLAG_NOT_TOUCHABLE（泡体可点，点击=收泡+跳学习画像），沉降 12s 由 render 管；
     *  review=false 与旧版闲聊泡行为完全一致（flags 同款、手势穿透、原位更新路径原样） */
    fun showBubble(text: String, sleep: Boolean, review: Boolean = false) {
        winParams ?: return   // 挂载守卫：服务未挂窗时不冒泡
        bubbleSleep = sleep
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
        val b = BubbleView(context, density, fishPx)
        b.text = text
        if (review) {
            // 复习泡点击（泡窗 root 视图）：收泡 + 跳学习画像。onOpenReview 由 AppNavigation
            // 注册（Task 10），未挂时 no-op 不崩。settle 同步清态：防 12s 内旋转等 render 重放
            // 把已点掉的泡复活
            b.setOnClickListener {
                hideBubble()
                onReviewBubbleTap()
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

    fun hideBubble() {
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

    fun closeMenu() {
        val m = menuWin ?: return
        menuWin = null
        menuWinParams = null
        menuHide?.let { mainHandler.removeCallbacks(it) }
        menuHide = null
        runCatching { wm?.removeView(m) }
    }

    /** 长按菜单开合。build=菜单内容构建（Service 侧 PetMenuBuilder，业务动作留 Service） */
    fun toggleMenu(build: () -> LinearLayout) {
        // 挂载守卫：detach 收窗后迟到的长按 Runnable 会把菜单窗挂到 (0,0) 成"无鱼鬼菜单"
        if (winParams == null || wm == null) return
        if (menuWin != null) {
            closeMenu()
            return
        }
        hideBubble()
        val m = build()
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
                layoutBubbleParams(p, b, measuredHeightOf(b), bubbleSleep)
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

    // ===== 布局/钳制 =====

    /** 鱼窗恒为鱼身大小（泡/菜单已拆独立窗，无伸缩跳动）。只做半出屏钳制，
     *  然后让可见的泡/菜单跟随重新钳位（它们整体钳在屏内，鱼贴边不出界）。 */
    fun syncWindowLayout() {
        val wp = winParams ?: return
        val r = root ?: return
        wp.x = PetWindowBounds.clampX(wp.x, screenW, fishPx)
        wp.y = PetWindowBounds.clampY(wp.y, originTop, screenH, fishPx)
        runCatching { wm?.updateViewLayout(r, wp) }
        repositionOverlays()
    }

    /** 拖动落点：标准偏移拖动（按住哪里，哪里就不跳——手指相对鱼身的位置全程不变），
     *  钳制统一走 PetWindowBounds（与 syncWindowLayout/attach 同口径），都拖得回来。
     *  必须传窗口根视图更新：传子视图会更新失败、视图树写坏整窗消失（历史恶性 bug） */
    fun applyDrag(nx: Int, ny: Int) {
        val wp = winParams ?: return
        wp.x = PetWindowBounds.clampX(nx, screenW, fishPx)
        wp.y = PetWindowBounds.clampY(ny, originTop, screenH, fishPx)
        runCatching { root?.let { wm?.updateViewLayout(it, wp) } }
        repositionOverlays()   // 泡/菜单跟随，整体钳在屏内
    }

    /** 鱼窗当前请求坐标（拖动锚定口径）。多指场景 rawX/rawY 恒指 0 号指针，换指锚定
     *  用「0 号指针 raw - 其视图坐标」反推视图原点——部分 ROM（vivo 等）会给悬浮窗视图
     *  原点垫上状态栏/挖孔 inset，请求坐标与视图原点差一大截，锚定只施加增量自动吸收 */
    fun fishWindowPos(): Pair<Int, Int>? = winParams?.let { it.x to it.y }

    /** 保存口径（syncWindowLayout 同步后调用）：原始比例，可为负/超 1（贴边半出屏停放），
     *  恢复端同口径放宽钳制，位置不瞬移 */
    fun fishPositionRatio(): Pair<Float, Float>? {
        val wp = winParams ?: return null
        val sw = (screenW - fishPx).coerceAtLeast(1).toFloat()
        val shh = (screenH - fishPx).coerceAtLeast(1).toFloat()
        return (wp.x / sw) to (wp.y / shh)
    }

    /** 旋转后：重测真实显示区 + 收泡/菜单（Zzz 长态泡由 Service render 按状态恢复）+ 重校准 */
    fun onScreenConfigChanged() {
        refreshScreenBounds()
        hideBubble()
        closeMenu()
        calibrateContentBounds()
    }

    /** 真实显示区（含导航栏/刘海）：displayMetrics 是 app 显示区，heightPixels 不含
     *  导航栏——贴底永远差一个导航栏高度（手势 ~24dp/三键 ~48dp），鱼尾贴不到物理底边 */
    private fun refreshScreenBounds() {
        runCatching {
            val wmgr = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            val m = context.resources.displayMetrics
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
        // 初始估计用系统状态栏高资源（±几 px）：恢复钳制发生在挂载前，若按 originTop=0
        // 钳一次会把顶缘负坐标截损（-232→-96 不可逆）；挂载后 calibrate 实测覆盖
        originTop = statusBarFallback()
    }

    private fun statusBarFallback(): Int = runCatching {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    /** 挂载后实测校准纵向原点偏移：NO_LIMITS 悬浮窗的 rootWindowInsets 恒为 0（实测），
     *  改用 locationOnScreen - 请求 y 作差（贴附原点=状态栏高，模拟器实测 136）。
     *  必须在首次 layout 后测（否则 locationOnScreen 未定会测出假值）——
     *  未 layout 时挂 GlobalLayout 监听兜底。校准完重钳一次。 */
    private fun calibrateContentBounds() {
        val r = root ?: return
        if (r.isLaidOut && applyOriginCalibration(r)) return
        val vto = r.viewTreeObserver
        if (!vto.isAlive) return
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (r.viewTreeObserver.isAlive) r.viewTreeObserver.removeOnGlobalLayoutListener(this)
                applyOriginCalibration(r)
            }
        })
    }

    private fun applyOriginCalibration(r: View): Boolean {
        val wp = winParams ?: return false
        val loc = IntArray(2)
        r.getLocationOnScreen(loc)
        originTop = (loc[1] - wp.y).coerceAtLeast(0)
        Log.d("PetOverlay", "calibrate originTop=$originTop screenH=$screenH fishPx=$fishPx")
        syncWindowLayout()
        return true
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

    // ===== 触摸：拖动 / 单击 / 双击 / 长按（识别与窗口操作在此，业务反应经 touchHooks）=====

    /** 装触摸手势到鱼 ImageView。识别归控制器（winParams 拖动/菜单开合/位置钳制），
     *  台词/庆祝/摇摆/计数/持久化经 [touchHooks] 打回 Service；PetHub 同包直调 */
    fun installTouchHandlers(iv: ImageView) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
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
        // 原点垫上状态栏/挖孔 inset，请求坐标与视图原点差一大截——直接拿请求坐标
        // 当视图原点会抓哪都先跳一截（拖动抓取点跳变的真凶）。偏移被自动吸收，
        // 且全程只施加增量。
        fun reAnchor(ev: MotionEvent, idx: Int) {
            val (startX, startY) = fishWindowPos() ?: return
            val originX = ev.rawX - ev.getX(0)
            val originY = ev.rawY - ev.getY(0)
            dragDownX = originX + ev.getX(idx)
            dragDownY = originY + ev.getY(idx)
            dragStartX = startX
            dragStartY = startY
            // 注意不重置 moved：拖动中换锚不应降级成单击
        }

        iv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchHooks.wake()
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
                            touchHooks.wake()
                            toggleMenu(menuProvider)
                            touchHooks.noteTouch()   // 长按开菜单即互动（菜单无守卫，任意态可弹）
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
                        touchHooks.startSwing()   // 真拖起来才挣扎；轻点不晃
                    }
                    if (moved) {
                        applyDrag(nx, ny)   // 标准偏移拖动：按住哪里，哪里就不跳（见 applyDrag 注释）
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressPending?.let { mainHandler.removeCallbacks(it) }
                    longPressPending = null
                    touchHooks.stopSwing()
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
                            touchHooks.celebrate()
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
                                        touchHooks.noteTouch()   // 互动计数：守卫态走上方 Unit，不落这里
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
                            touchHooks.squashWobble()   // 压扁弹回：纯视觉缩放，不改状态，流式期间也安全
                        }
                        // 先重钳+更新窗口，再按同步后的鱼位置保存（窗口顶=鱼顶），口径一致
                        syncWindowLayout()
                        fishPositionRatio()?.let { (nx, ny) ->
                            // 存原始比例（可为负/超 1，贴边半出屏停放），恢复端放宽钳制
                            touchHooks.savePosition(nx, ny)
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
                    touchHooks.stopSwing()
                    dragTracker?.recycle()
                    dragTracker = null
                    true
                }
                else -> false
            }
        }
    }
}
