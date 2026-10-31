package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.liuyouwu.familiar.data.repository.dataStore
import dev.liuyouwu.familiar.data.update.KEY_UPDATE_AUTO_CHECK
import dev.liuyouwu.familiar.data.update.KEY_UPDATE_DOWNLOAD_ID
import dev.liuyouwu.familiar.data.update.KEY_UPDATE_LAST_CHECK_MILLIS
import dev.liuyouwu.familiar.data.update.KEY_UPDATE_PENDING_VERSION
import dev.liuyouwu.familiar.data.update.OkHttpReleaseHttp
import dev.liuyouwu.familiar.data.update.Sha256
import dev.liuyouwu.familiar.data.update.UpdateCheckResult
import dev.liuyouwu.familiar.data.update.UpdateDmState
import dev.liuyouwu.familiar.data.update.UpdateDownloader
import dev.liuyouwu.familiar.data.update.UpdateRepository
import dev.liuyouwu.familiar.ui.component.UpdateAboutDialog
import dev.liuyouwu.familiar.ui.component.UpdatePermissionDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 更新检查 UI 状态机。fromAuto：自动检查的静默结果（不弹关于弹窗）；发现新版仍弹窗 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class AvailableShow(val check: UpdateCheckResult.Available) : UpdateUiState
    data class UpToDate(val fromAuto: Boolean = false) : UpdateUiState

    /** canOpenProjectPage：手动检查的网络类失败才给「打开项目主页」出口 */
    data class CheckFailed(
        val reason: UpdateCheckResult.Failed.Reason,
        val canOpenProjectPage: Boolean,
        val fromAuto: Boolean = false,
    ) : UpdateUiState

    data class Downloading(val percent: Int) : UpdateUiState
    data class ReadyToInstall(val file: File) : UpdateUiState
    data object VerifyFailed : UpdateUiState
    data class NeedPermission(val file: File) : UpdateUiState
    data object DownloadFailed : UpdateUiState
}

/**
 * 检查更新 ViewModel：Activity 级单实例（MainActivity 与 ChatListScreen 溢出菜单
 * 各自经 Activity 的 ViewModelStore 取到同一对象）。
 *
 * 可测分支（版本比较/JSON/资产挑选/校验和/清理策略）在 data/update 包内独立，
 * 这里只做编排：协程调度 + DataStore 读写 + 状态机流转。
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Context = app.applicationContext

    private val repo = UpdateRepository(OkHttpReleaseHttp())
    private val downloader = UpdateDownloader(appContext)

    /** 本地版本名（裸 versionName，供比较/清理；展示用 versionLabel() 带 (code) 后缀） */
    private val versionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** 用户是否打开了「关于与更新」弹窗（自动检查发现新版时无需此标记也弹窗） */
    private val _showAbout = MutableStateFlow(false)
    val showAbout: StateFlow<Boolean> = _showAbout.asStateFlow()

    /** 自动检查开关（默认开；写 DataStore 后 Flow 自动刷新） */
    val autoCheckEnabled: StateFlow<Boolean> = appContext.dataStore.data
        .map { it[KEY_UPDATE_AUTO_CHECK] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 当前轮询协程与下载 id（内存现场；持久化以 DataStore 键为准） */
    private var pollJob: Job? = null
    private var currentId: Long? = null

    /** 本会话内下载时的检查结果（校验需要 SUMS 哈希；跨进程重启后为 null → 恢复路径现场补查） */
    private var activeCheck: UpdateCheckResult.Available? = null

    fun versionLabel(): String {
        val code = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode
        }.getOrDefault(0)
        return "v$versionName ($code)"
    }

    // ---------------- 弹窗入口 ----------------

    /** 溢出菜单「检查更新」：打开关于弹窗；有未完成下载则恢复进度轮询（不并发手动检查，
     *  两个协程交错写 _state 会让弹窗闪状态），否则立即手动检查 */
    fun openAboutAndCheck() {
        _showAbout.value = true
        viewModelScope.launch {
            val prefs = runCatching { appContext.dataStore.data.first() }.getOrNull()
            if (prefs?.get(KEY_UPDATE_DOWNLOAD_ID) != null) {
                resumeIfNeeded()
            } else {
                manualCheck()
            }
        }
    }

    /** 打开关于弹窗（不自动检查） */
    fun openAbout() {
        _showAbout.value = true
        resumeIfNeeded()
    }

    /** 进程重启后凭 DataStore 键恢复下载轮询（清理后仍存活的下载才可恢复） */
    private fun resumeIfNeeded() {
        cleanupOnLaunch()
        if (_state.value !is UpdateUiState.Idle) return
        viewModelScope.launch {
            val prefs = runCatching { appContext.dataStore.data.first() }.getOrNull() ?: return@launch
            val id = prefs[KEY_UPDATE_DOWNLOAD_ID] ?: return@launch
            currentId = id
            startPolling(id, activeCheck, prefs[KEY_UPDATE_PENDING_VERSION])
        }
    }

    // ---------------- 检查 ----------------

    /** 手动检查：不受/不产生 24h 节流 */
    fun manualCheck() {
        if (!canStartCheck()) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            val result = checkNow()
            _state.value = when (result) {
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(fromAuto = false)
                is UpdateCheckResult.Available -> UpdateUiState.AvailableShow(result)
                is UpdateCheckResult.Failed -> UpdateUiState.CheckFailed(
                    reason = result.reason,
                    canOpenProjectPage = result.reason != UpdateCheckResult.Failed.Reason.BAD_RESPONSE,
                    fromAuto = false,
                )
                null -> UpdateUiState.CheckFailed(
                    UpdateCheckResult.Failed.Reason.NETWORK,
                    canOpenProjectPage = true,
                    fromAuto = false,
                )
            }
        }
    }

    /** 自动检查：开关 + 在线 + 24h 节流三条件全过才发起；无更新与失败一律静默，发现新版仍弹窗 */
    fun maybeAutoCheck() {
        viewModelScope.launch {
            val prefs = runCatching { appContext.dataStore.data.first() }.getOrNull() ?: return@launch
            val autoEnabled = prefs[KEY_UPDATE_AUTO_CHECK] ?: true
            val lastCheck = prefs[KEY_UPDATE_LAST_CHECK_MILLIS] ?: 0L
            val online = isOnline()
            if (!autoEnabled || !online) return@launch
            if (lastCheck > 0 && System.currentTimeMillis() - lastCheck < THROTTLE_MILLIS) return@launch
            if (!canStartCheck()) return@launch
            _state.value = UpdateUiState.Checking
            val result = checkNow()
            _state.value = when (result) {
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(fromAuto = true)
                is UpdateCheckResult.Available -> UpdateUiState.AvailableShow(result)
                is UpdateCheckResult.Failed -> UpdateUiState.CheckFailed(result.reason, canOpenProjectPage = false, fromAuto = true)
                null -> UpdateUiState.CheckFailed(
                    UpdateCheckResult.Failed.Reason.NETWORK,
                    canOpenProjectPage = false,
                    fromAuto = true,
                )
            }
            // 节流基准只由自动检查写；失败也写，防止每次启动都撞限流
            runCatching {
                appContext.dataStore.edit { it[KEY_UPDATE_LAST_CHECK_MILLIS] = System.currentTimeMillis() }
            }
        }
    }

    private suspend fun checkNow(): UpdateCheckResult? =
        withContext(Dispatchers.IO) { runCatching { repo.check(versionName) }.getOrNull() }

    /** 仅瞬态结果态可发起新检查——Checking/下载中/弹窗/待装态不被点击打断 */
    private fun canStartCheck(): Boolean = when (_state.value) {
        is UpdateUiState.Idle,
        is UpdateUiState.UpToDate,
        is UpdateUiState.CheckFailed,
        is UpdateUiState.DownloadFailed,
        is UpdateUiState.VerifyFailed,
        -> true
        else -> false
    }

    // ---------------- 下载 ----------------

    /** 应用内下载：持久化 downloadId + pendingVersion 后进入 1s 轮询 */
    fun startDownload() {
        val s = _state.value as? UpdateUiState.AvailableShow ?: return
        val check = s.check
        // 防线：canInstall=false 时主按钮已隐藏，这里再守一道——宁可不装
        val asset = check.asset?.takeIf { check.canInstall } ?: return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { downloader.enqueue(asset) }.getOrNull()
            }
            if (id == null) {
                _state.value = UpdateUiState.DownloadFailed
                return@launch
            }
            runCatching {
                appContext.dataStore.edit {
                    it[KEY_UPDATE_DOWNLOAD_ID] = id
                    it[KEY_UPDATE_PENDING_VERSION] = check.release.tagName
                }
            }
            currentId = id
            activeCheck = check
            startPolling(id, check, check.release.tagName)
        }
    }

    /** 取消 = DownloadManager.remove：停轮询、删记录、清持久化键 */
    fun cancelDownload() {
        pollJob?.cancel()
        val id = currentId
        currentId = null
        activeCheck = null
        if (id != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { downloader.cancel(id) } }
        }
        clearDownloadKeys()
        if (_state.value is UpdateUiState.Downloading) _state.value = UpdateUiState.Idle
    }

    /** 1s 轮询循环。[check] 为 null = 恢复模式（进程重启后无内存现场，SUCCESSFUL 时补查校验） */
    private fun startPolling(id: Long, check: UpdateCheckResult.Available?, pendingTag: String?) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var nullStreak = 0   // enqueue 刚落库 DM 可能查无记录：连续多次才算真失败
            while (isActive) {
                val st = withContext(Dispatchers.IO) { runCatching { downloader.query(id) }.getOrNull() }
                when {
                    st == null -> {
                        nullStreak++
                        if (nullStreak >= MAX_QUERY_NULL_STREAK) {
                            clearDownloadIdKey()
                            _state.value = UpdateUiState.DownloadFailed
                            return@launch
                        }
                    }
                    st.status == DownloadManager.STATUS_SUCCESSFUL -> {
                        settleSuccessful(st, check, pendingTag)
                        return@launch
                    }
                    st.status == DownloadManager.STATUS_FAILED -> {
                        clearDownloadIdKey()
                        _state.value = UpdateUiState.DownloadFailed
                        return@launch
                    }
                    else -> {
                        nullStreak = 0
                        val pct = if (st.totalBytes > 0) {
                            ((st.bytesSoFar * 100) / st.totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        _state.value = UpdateUiState.Downloading(pct)
                    }
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** 下载完成收口：取期望哈希 → SHA 校验 → ReadyToInstall / NeedPermission，否则删文件+清键+VerifyFailed */
    private suspend fun settleSuccessful(
        st: UpdateDmState,
        sessionCheck: UpdateCheckResult.Available?,
        pendingTag: String?,
    ) {
        val file = downloader.fileOf(st)
        // 恢复模式（进程重启后续查）：内存无 check，按 pendingTag 现场补一次检查取 SUMS
        val check = sessionCheck ?: fetchCheckFor(pendingTag)
        if (check == null) {
            // 补查失败（离线/限流/契约破坏）：无法校验 = 绝不安装。
            // 文件保留（清理策略②保护），仅清 downloadId 终止恢复，静默回 Idle
            clearDownloadIdKey()
            _state.value = UpdateUiState.Idle
            return
        }
        val expected = check.asset?.let { check.sums?.get(it.name) }
        val verified = file != null && expected != null &&
            withContext(Dispatchers.IO) { runCatching { Sha256.matches(file, expected) }.getOrDefault(false) }
        if (verified) {
            // downloadId 使命完成即清：否则重开弹窗会反复恢复轮询→反复拉起安装器
            clearDownloadIdKey()
            val f = file!!
            _state.value =
                if (grantedInstall()) UpdateUiState.ReadyToInstall(f) else UpdateUiState.NeedPermission(f)
        } else {
            // 校验失败：删文件 + 清持久化键 + VerifyFailed（文件已损坏无保留价值）
            file?.let { f -> withContext(Dispatchers.IO) { runCatching { f.delete() } } }
            clearDownloadKeys()
            _state.value = UpdateUiState.VerifyFailed
        }
    }

    /** 恢复模式补查：tag 必须与待装包一致且 canInstall（资产/SUMS 齐备），否则视为无法校验 */
    private suspend fun fetchCheckFor(pendingTag: String?): UpdateCheckResult.Available? {
        if (pendingTag.isNullOrBlank()) return null
        val fresh = checkNow()
        val avail = fresh as? UpdateCheckResult.Available ?: return null
        return avail.takeIf { it.release.tagName == pendingTag && it.canInstall }
    }

    // ---------------- 生命周期钩子 ----------------

    /** 启动清理：update/ 只留两类；pendingVersion ≤ 当前版本时删文件并清键 */
    fun cleanupOnLaunch() {
        viewModelScope.launch {
            val prefs = runCatching { appContext.dataStore.data.first() }.getOrNull() ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    downloader.cleanup(
                        pendingVersion = prefs[KEY_UPDATE_PENDING_VERSION],
                        currentVersion = versionName,
                        activeId = prefs[KEY_UPDATE_DOWNLOAD_ID],
                    )
                }
            }
        }
    }

    /** 宿主 onResume：NeedPermission 态复查授权——已授 → ReadyToInstall 续装 */
    fun onHostResumed() {
        val s = _state.value
        if (s is UpdateUiState.NeedPermission && grantedInstall()) {
            _state.value = UpdateUiState.ReadyToInstall(s.file)
        }
    }

    // ---------------- 弹窗动作与出口 ----------------

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { appContext.dataStore.edit { it[KEY_UPDATE_AUTO_CHECK] = enabled } }
        }
    }

    /** 「浏览器下载」：资产直链优先，无资产回落 releases 页 */
    fun openBrowser(context: Context) {
        val s = _state.value as? UpdateUiState.AvailableShow ?: return
        openUrl(context, s.check.asset?.url ?: s.check.release.htmlUrl)
    }

    fun openProjectPage(context: Context) {
        openUrl(context, PROJECT_PAGE_URL)
    }

    /** 「查看发布页」：Release 详情页（changelog 全文） */
    fun openReleasePage(context: Context) {
        val s = _state.value as? UpdateUiState.AvailableShow ?: return
        openUrl(context, s.check.release.htmlUrl)
    }

    /** 「复制下载链接」：写剪贴板 + Toast */
    fun copyLink(context: Context) {
        val s = _state.value as? UpdateUiState.AvailableShow ?: return
        val url = s.check.asset?.url ?: s.check.release.htmlUrl
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("url", url)) }
        Toast.makeText(context, "下载链接已复制", Toast.LENGTH_SHORT).show()
    }

    /** 关闭关于弹窗/更新弹窗：回 Idle。下载中允许关闭（后台继续，重开弹窗恢复进度） */
    fun dismissDialog() {
        when (_state.value) {
            is UpdateUiState.AvailableShow,
            is UpdateUiState.UpToDate,
            is UpdateUiState.CheckFailed,
            is UpdateUiState.DownloadFailed,
            is UpdateUiState.VerifyFailed,
            is UpdateUiState.NeedPermission,
            -> _state.value = UpdateUiState.Idle
            else -> Unit
        }
        _showAbout.value = false
    }

    /** 应用内安装意图（FileProvider content URI + 授权读 + 新任务栈） */
    fun installerIntent(file: File): Intent = downloader.installIntent(file)

    private fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun needPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${appContext.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** minSdk 26：直接查「安装未知应用」授权 */
    private fun grantedInstall(): Boolean =
        runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    private fun isOnline(): Boolean = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    private fun clearDownloadKeys() {
        viewModelScope.launch {
            runCatching {
                appContext.dataStore.edit {
                    it.remove(KEY_UPDATE_DOWNLOAD_ID)
                    it.remove(KEY_UPDATE_PENDING_VERSION)
                }
            }
        }
    }

    private fun clearDownloadIdKey() {
        viewModelScope.launch {
            runCatching { appContext.dataStore.edit { it.remove(KEY_UPDATE_DOWNLOAD_ID) } }
        }
    }

    // ---------------- 弹窗宿主（驻 MainActivity） ----------------

    /** 收集 state 渲染弹窗；ReadyToInstall 拉起系统安装器后回 Idle */
    @Composable
    fun DialogHost() {
        val s by _state.collectAsStateWithLifecycle()
        val show by _showAbout.collectAsStateWithLifecycle()
        val context = LocalContext.current

        // ReadyToInstall：无论弹窗是否打开都直接拉起系统安装器
        if (s is UpdateUiState.ReadyToInstall) {
            val file = (s as UpdateUiState.ReadyToInstall).file
            LaunchedEffect(file) {
                val ok = runCatching { context.startActivity(installerIntent(file)) }.isSuccess
                if (!ok) Toast.makeText(context, "无法启动安装器，请到发布页手动安装", Toast.LENGTH_LONG).show()
                _state.value = UpdateUiState.Idle
            }
        }

        val needsAttention = s is UpdateUiState.AvailableShow || s is UpdateUiState.NeedPermission
        if (!show && !needsAttention) return

        when (val st = s) {
            is UpdateUiState.NeedPermission -> UpdatePermissionDialog(
                onGrant = { runCatching { context.startActivity(needPermissionIntent()) } },
                onLater = { dismissDialog() },
            )
            else -> UpdateAboutDialog(
                state = st,
                versionLabel = versionLabel(),
                autoCheckEnabled = autoCheckEnabled.collectAsState().value,
                onCheck = { manualCheck() },
                onToggleAutoCheck = { setAutoCheck(it) },
                onInstall = { startDownload() },
                onCancelDownload = { cancelDownload() },
                onBrowser = { openBrowser(context) },
                onCopyLink = { copyLink(context) },
                onViewNotes = { openReleasePage(context) },
                onOpenProjectPage = { openProjectPage(context) },
                onDismiss = { dismissDialog() },
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L

        /** enqueue 落库窗口内 query 可能查无记录：连续 N 次才判 DownloadFailed */
        const val MAX_QUERY_NULL_STREAK = 3

        /** 自动检查节流：24 小时 */
        const val THROTTLE_MILLIS = 24L * 60 * 60 * 1000

        const val PROJECT_PAGE_URL = "https://github.com/liuyouwu1999/familiar"
    }
}
