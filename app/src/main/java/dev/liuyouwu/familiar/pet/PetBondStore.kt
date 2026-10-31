package dev.liuyouwu.familiar.pet

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 独立 pet_bond 文件（单文件单持有者范式，SettingsRepository.kt:22-23——挂 settings
// 同文件会"Double property holders"启动即崩）。伴生数据有意不进备份：重装清零（spec D3）。
private val Context.bondDataStore: DataStore<Preferences> by preferencesDataStore(name = "pet_bond")

/**
 * 大肥鱼相伴数据（B1 Task 7，spec §5）：久别问候的"多久没见"与设置页
 * "已相伴 N 天 · 互动 M 次"的唯一数据源。应用级 object 单例——三挂接点
 * （ConnectionScreen/ChatListViewModel/ChatViewModel）与回合打点桥
 * （AgentSessionController）直调免 DI 传递（四轮 F3 载体）。
 *
 * 内存镜像：snapshot()/shouldGreet() 是同步口（挂接点都在主线程），镜像由
 * init 时异步载入 + 每次写后回读刷新；未载完时快照为默认值——shouldGreet
 * 恒 false（从未聊天谈不上"久别"），方向安全不误问候。
 * 读写失败静默回落默认值（spec §5 边界），只有时间戳与计数、无敏感内容。
 */
object PetBondStore {

    /** 相伴快照：设置页统计行与问候判定的共读面 */
    data class BondSnapshot(
        val firstMetAt: Long = 0L,      // 首次相伴打点（首回合/首摸）；0=尚未打点
        val lastChatAt: Long = 0L,      // familiar 回合收束时刻；0=从未聊过
        val streakDays: Int = 0,        // 连续聊天历日数（PetBondMath.nextStreak 维护）
        val interactionCount: Int = 0,  // 互动次数（摸摸头/单击等 noteTouch 计数）
    )

    /** 时钟可注入（默认系统时钟；PetBondLogicTest 只测 Math 不动这里） */
    @Volatile var now: () -> Long = { System.currentTimeMillis() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    @Volatile private var appContext: Context? = null

    private val _snapshot = MutableStateFlow(BondSnapshot())

    /** 快照流：设置页统计行 collect 它（init 载入/回合/摸摸头写后自动刷新） */
    val snapshots: StateFlow<BondSnapshot> = _snapshot.asStateFlow()

    /** 同步读口（挂接点在主线程，读镜像零挂起） */
    fun snapshot(): BondSnapshot = _snapshot.value

    // 当日已问候标记（last_greeted_day_epoch 的内存镜像；非展示字段不进 BondSnapshot）
    @Volatile private var greetedDay: Int? = null

    /** FamiliarApp.onCreate 调：存应用上下文并异步载入镜像 */
    fun init(appContext: Context) {
        this.appContext = appContext.applicationContext
        scope.launch {
            runCatching { loadIntoMemory() }
        }
    }

    private suspend fun loadIntoMemory() {
        val ctx = appContext ?: return
        val p = ctx.bondDataStore.data.first()
        greetedDay = p[KEY_LAST_GREETED_DAY]?.toInt()
        _snapshot.value = BondSnapshot(
            firstMetAt = p[KEY_FIRST_MET_AT] ?: 0L,
            lastChatAt = p[KEY_LAST_CHAT_AT] ?: 0L,
            streakDays = (p[KEY_STREAK_DAYS] ?: 0L).toInt(),
            interactionCount = (p[KEY_INTERACTION_COUNT] ?: 0L).toInt(),
        )
    }

    /**
     * familiar 回合收束打点（AgentSessionController petTurnEnded 桥调，取消回合
     * 不进来）：lastChatAt=now + streak 历日推进；首次同时立 firstMetAt。
     * NonCancellable 包写段——宿主 scope 收线时成功回合的相伴打点不丢（对齐
     * FamiliarHooks 的 appendTurn 范式）。
     */
    suspend fun noteTurn() {
        val ctx = appContext ?: return
        val t = now()
        writeMutex.withLock {
            withContext(NonCancellable) {
                ctx.bondDataStore.edit { p ->
                    val today = PetBondMath.epochDay(t)
                    val lastChat = p[KEY_LAST_CHAT_AT]
                    p[KEY_STREAK_DAYS] = if (lastChat == null) 1L
                    else PetBondMath.nextStreak((p[KEY_STREAK_DAYS] ?: 0L).toInt(), PetBondMath.epochDay(lastChat), today).toLong()
                    p[KEY_LAST_CHAT_AT] = t
                    if (p[KEY_FIRST_MET_AT] == null) p[KEY_FIRST_MET_AT] = t
                }
                runCatching { refreshMirror() }
            }
        }
    }

    /** 互动打点（摸摸头/单击等）：计数 +1、记 last_touch_at；首次立 firstMetAt */
    suspend fun noteTouch() {
        val ctx = appContext ?: return
        val t = now()
        writeMutex.withLock {
            withContext(NonCancellable) {
                ctx.bondDataStore.edit { p ->
                    p[KEY_INTERACTION_COUNT] = (p[KEY_INTERACTION_COUNT] ?: 0L) + 1L
                    p[KEY_LAST_TOUCH_AT] = t
                    if (p[KEY_FIRST_MET_AT] == null) p[KEY_FIRST_MET_AT] = t
                }
                runCatching { refreshMirror() }
            }
        }
    }

    /**
     * 久别问候到期判定（连接侧挂接点经 PetHub.maybeGreet 调）：从未聊过不问候
     * （没有"久别"可言）；其余交 PetBondMath.shouldGreet 历日比较。
     */
    fun shouldGreet(now: () -> Long = this.now): Boolean {
        val lastChatAt = _snapshot.value.lastChatAt
        if (lastChatAt == 0L) return false
        return PetBondMath.shouldGreet(
            lastChatDay = PetBondMath.epochDay(lastChatAt),
            today = PetBondMath.epochDay(now()),
            greetedDay = greetedDay,
        )
    }

    /**
     * 记当日已问候（先改内存镜像再异步落盘）：同日重连不再置 pending——
     * "当日仅一次"不依赖盘写时序。落盘失败不影响本轮内存标记。
     */
    fun markGreeted(now: () -> Long = this.now) {
        val ctx = appContext ?: return
        val day = PetBondMath.epochDay(now())
        greetedDay = day
        scope.launch {
            runCatching {
                ctx.bondDataStore.edit { it[KEY_LAST_GREETED_DAY] = day.toLong() }
            }
        }
    }

    /** 写后回读刷新镜像（持 writeMutex 调，读到的必是本写入之后的态） */
    private suspend fun refreshMirror() {
        val ctx = appContext ?: return
        val p = ctx.bondDataStore.data.first()
        _snapshot.value = BondSnapshot(
            firstMetAt = p[KEY_FIRST_MET_AT] ?: 0L,
            lastChatAt = p[KEY_LAST_CHAT_AT] ?: 0L,
            streakDays = (p[KEY_STREAK_DAYS] ?: 0L).toInt(),
            interactionCount = (p[KEY_INTERACTION_COUNT] ?: 0L).toInt(),
        )
    }

    private val KEY_FIRST_MET_AT = longPreferencesKey("first_met_at")
    private val KEY_LAST_CHAT_AT = longPreferencesKey("last_chat_at")
    private val KEY_LAST_TOUCH_AT = longPreferencesKey("last_touch_at")
    private val KEY_STREAK_DAYS = longPreferencesKey("streak_days")
    private val KEY_LAST_GREETED_DAY = longPreferencesKey("last_greeted_day_epoch")
    private val KEY_INTERACTION_COUNT = longPreferencesKey("interaction_count")
}
